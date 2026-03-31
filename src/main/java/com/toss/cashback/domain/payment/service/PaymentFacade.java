package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.payment.dto.request.PaymentRequest;
import com.toss.cashback.domain.payment.dto.response.PaymentResponse;
import com.toss.cashback.domain.payment.entity.PaymentIdempotency;
import com.toss.cashback.domain.payment.repository.PaymentIdempotencyRepository;

import java.time.LocalDateTime;
import com.toss.cashback.global.alert.AlertService;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import com.toss.cashback.infrastructure.api.ExternalBankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// ======= [10번] 결제 Facade (트랜잭션 분리 + 멱등성) =======
/**
 * 결제 전체 흐름 조율. @Transactional 없이 각 단계별 트랜잭션을 분리하는 게 핵심입니다.
 *
 * 외부 API 호출 중 DB 커넥션을 잡고 있으면 동시 요청이 조금만 몰려도 커넥션 풀이 고갈됩니다.
 * Facade 패턴으로 이체(TX1 커밋 → 커넥션 반환) → 외부 API → 캐시백(TX3) 순으로 분리했습니다.
 * 외부 API 실패 시 TX1을 역방향으로 되돌리는 보상 트랜잭션(TX2)도 여기서 조율합니다.
 *
 * 멱등성 처리 전략:
 * - 결제 시작 전 PROCESSING 상태로 선등록 (unique constraint로 동시 중복 요청 차단)
 * - 처리 완료 후 COMPLETED로 업데이트
 * - 이체 실패 or 보상 완료 시 레코드 삭제 → 같은 키로 재시도 허용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentService paymentService;
    private final ExternalBankService externalBankService;
    private final CashbackService cashbackService;
    private final PaymentIdempotencyRepository idempotencyRepository;
    private final AlertService alertService;  // 운영 알림 (보상 트랜잭션 실패 등 심각 장애 시)

    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("[결제 시작] from={}, to={}, amount={}, key={}",
                request.getFromAccountId(), request.getToAccountId(), request.getAmount(), request.getIdempotencyKey());

        // 멱등성 키 선등록: PROCESSING 상태로 먼저 저장 → 동시 중복 요청을 DB 제약으로 차단
        PaymentIdempotency idempotencyRecord = getOrCreateIdempotencyRecord(request.getIdempotencyKey());

        // COMPLETED 상태: 이미 완료된 동일 요청 → 저장된 결과 즉시 반환 (재처리 없음)
        if (idempotencyRecord.isCompleted()) {
            log.info("[멱등성] 완료된 중복 요청 반환 - key={}, txId={}",
                    request.getIdempotencyKey(), idempotencyRecord.getTransactionId());
            return PaymentResponse.success(
                    idempotencyRecord.getTransactionId(),
                    idempotencyRecord.getAmount(),
                    idempotencyRecord.getCashbackAmount()
            );
        }

        // STEP 1: 계좌 이체 (TX1 커밋 → DB 커넥션 즉시 반환)
        Long transactionId;
        try {
            transactionId = paymentService.executeTransfer(
                    request.getFromAccountId(), request.getToAccountId(), request.getAmount());
        } catch (CustomException e) {
            // 이체 실패(잔액 부족 등) → PROCESSING 레코드 삭제 → 같은 키로 재시도 허용
            idempotencyRepository.delete(idempotencyRecord);
            log.error("[STEP 1 실패] 이체 실패 - {}", e.getMessage());
            throw e;
        }
        log.info("[STEP 1 완료] 이체 성공 - txId={}", transactionId);

        // STEP 2: 외부 은행 승인 (커넥션 미점유 상태에서 호출)
        try {
            externalBankService.approve(request.getFromAccountId(), request.getAmount());
            log.info("[STEP 2 완료] 외부 은행 승인 - txId={}", transactionId);

        } catch (CustomException e) {
            // 외부 은행 실패 → TX1 역방향 원복 (보상 트랜잭션)
            log.error("[STEP 2 실패] 보상 트랜잭션 실행 - txId={}, error={}", transactionId, e.getMessage());
            try {
                paymentService.compensate(transactionId);
                log.warn("[TX2] 보상 트랜잭션 완료 - txId={}", transactionId);
            } catch (Exception compensationEx) {
                // 보상 트랜잭션 실패 = 계좌 불일치. 운영팀 즉시 개입 필요
                log.error("[심각] 보상 트랜잭션 실패 - txId={}, 수동 개입 필요", transactionId, compensationEx);
                paymentService.markTransactionCompensationFailed(transactionId, compensationEx.getMessage());
                alertService.sendCompensationFailureAlert(transactionId, compensationEx.getMessage());
                idempotencyRepository.delete(idempotencyRecord);
                throw new CustomException(ErrorCode.COMPENSATION_FAILED);
            }
            // 보상 완료 → PROCESSING 레코드 삭제 (같은 키로 재시도 허용)
            idempotencyRepository.delete(idempotencyRecord);
            throw e;
        }

        // STEP 3: 캐시백 지급 (Redisson 락 + 트랜잭션)
        long cashbackAmount = 0L;
        try {
            cashbackAmount = cashbackService.grantCashback(request.getFromAccountId(), request.getAmount());
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.LOCK_ACQUISITION_FAILED ||
                e.getErrorCode() == ErrorCode.CASHBACK_BUDGET_EXCEEDED) {
                // 캐시백 미지급이어도 결제 자체는 성공 처리
                log.warn("[캐시백 미지급] txId={}, reason={}", transactionId, e.getMessage());
            } else {
                throw e;
            }
        }

        // STEP 4: 트랜잭션 SUCCESS 상태 업데이트
        paymentService.markTransactionSuccess(transactionId, cashbackAmount);
        log.info("[결제 완료] txId={}, amount={}, cashback={}", transactionId, request.getAmount(), cashbackAmount);

        // 멱등성 레코드 COMPLETED로 업데이트 (다음 동일 요청은 이 결과를 바로 반환)
        idempotencyRecord.complete(transactionId, request.getAmount(), cashbackAmount);
        idempotencyRepository.save(idempotencyRecord);

        return PaymentResponse.success(transactionId, request.getAmount(), cashbackAmount);
    }

    /**
     * 멱등성 레코드 조회 또는 PROCESSING 상태로 신규 생성
     *
     * - 기존 COMPLETED: 캐시된 결과 반환용 레코드 그대로 반환
     * - 기존 PROCESSING: 이미 처리 중인 동일 요청 → DUPLICATE_PAYMENT_REQUEST 예외
     * - 신규: PROCESSING 상태로 saveAndFlush (unique constraint로 동시 중복 요청 차단)
     */
    private PaymentIdempotency getOrCreateIdempotencyRecord(String idempotencyKey) {
        PaymentIdempotency existing = idempotencyRepository
                .findByIdempotencyKeyAndExpiresAtAfter(idempotencyKey, LocalDateTime.now())
                .orElse(null);

        if (existing != null) {
            if (existing.isCompleted()) {
                return existing;
            }
            // PROCESSING 상태 → 이미 진행 중인 동일 요청
            log.warn("[멱등성] 처리 중인 중복 요청 - key={}", idempotencyKey);
            throw new CustomException(ErrorCode.DUPLICATE_PAYMENT_REQUEST);
        }

        try {
            // 새 요청: PROCESSING으로 먼저 저장 (saveAndFlush로 즉시 DB 반영)
            return idempotencyRepository.saveAndFlush(
                    PaymentIdempotency.createProcessing(idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 unique constraint 충돌 → 이미 처리 중
            log.warn("[멱등성] 동시 중복 요청 차단 - key={}", idempotencyKey);
            throw new CustomException(ErrorCode.DUPLICATE_PAYMENT_REQUEST);
        }
    }
}
