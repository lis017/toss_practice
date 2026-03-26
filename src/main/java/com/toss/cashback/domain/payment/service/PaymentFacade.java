package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.payment.dto.request.PaymentRequest;
import com.toss.cashback.domain.payment.dto.response.PaymentResponse;
import com.toss.cashback.domain.payment.entity.PaymentIdempotency;
import com.toss.cashback.domain.payment.repository.PaymentIdempotencyRepository;

import java.time.LocalDateTime;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import com.toss.cashback.infrastructure.api.ExternalBankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 전체 흐름 조율. @Transactional 없이 각 단계별 트랜잭션을 분리하는 게 핵심입니다.
 *
 * 외부 API 호출 중 DB 커넥션을 잡고 있으면 동시 요청이 조금만 몰려도 커넥션 풀이 고갈됩니다.
 * Facade 패턴으로 이체(TX1 커밋 → 커넥션 반환) → 외부 API → 캐시백(TX3) 순으로 분리했습니다.
 * 외부 API 실패 시 TX1을 역방향으로 되돌리는 보상 트랜잭션(TX2)도 여기서 조율합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentService paymentService;
    private final ExternalBankService externalBankService;
    private final CashbackService cashbackService;
    private final PaymentIdempotencyRepository idempotencyRepository;

    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("[결제 시작] from={}, to={}, amount={}, key={}",
                request.getFromAccountId(), request.getToAccountId(), request.getAmount(), request.getIdempotencyKey());

        // 동일 키로 재요청 시 저장된 첫 번째 응답 그대로 반환 (24시간 TTL, 만료 후엔 새 결제로 처리)
        PaymentIdempotency existingRecord = idempotencyRepository
                .findByIdempotencyKeyAndExpiresAtAfter(request.getIdempotencyKey(), LocalDateTime.now())
                .orElse(null);

        if (existingRecord != null) {
            log.info("[멱등성] 중복 요청 - key={}, txId={}", request.getIdempotencyKey(), existingRecord.getTransactionId());
            return PaymentResponse.success(
                    existingRecord.getTransactionId(),
                    existingRecord.getAmount(),
                    existingRecord.getCashbackAmount()
            );
        }

        // STEP 1: 계좌 이체 (TX1 커밋 → DB 커넥션 즉시 반환)
        Long transactionId;
        try {
            transactionId = paymentService.executeTransfer(
                    request.getFromAccountId(), request.getToAccountId(), request.getAmount());
        } catch (CustomException e) {
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
                // 보상 트랜잭션 실패 = 데이터 불일치. 운영 알림 필요
                log.error("[심각] 보상 트랜잭션 실패 - txId={}, 수동 개입 필요", transactionId, compensationEx);
            }
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

        // 멱등성 레코드 저장 (결제 성공 후 저장 → 실패 시 재처리 가능)
        idempotencyRepository.save(PaymentIdempotency.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .transactionId(transactionId)
                .amount(request.getAmount())
                .cashbackAmount(cashbackAmount)
                .build());

        return PaymentResponse.success(transactionId, request.getAmount(), cashbackAmount);
    }
}
