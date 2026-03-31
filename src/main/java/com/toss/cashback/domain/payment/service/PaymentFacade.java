package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.payment.dto.request.PaymentRequest;
import com.toss.cashback.domain.payment.dto.response.PaymentResponse;
import com.toss.cashback.domain.payment.entity.PaymentIdempotency;
import com.toss.cashback.domain.payment.repository.PaymentIdempotencyRepository;

import java.time.LocalDateTime;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import com.toss.cashback.infrastructure.api.ExternalBankService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// ======= [10번] 결제 Facade (C안 - PG 가상계좌 정산 구조) =======
/**
 * 결제 전체 흐름 조율. @Transactional 없이 각 단계별 트랜잭션을 분리합니다.
 *
 * C안 (PG 가상계좌 정산) 흐름:
 * 1단계(즉시): 외부 승인 → 구매자(A) 출금 → 가상계좌(C) 입금 → 정산 레코드 생성
 * 2단계(정산): SettlementScheduler가 매일 PENDING 정산 건을 일괄 처리 → 가맹점(B) 입금
 *
 * 실제 PG(토스페이먼츠) 구조:
 * - 구매자는 결제 즉시 완료 응답을 받음 (1단계 완료 = 구매자 입장에서는 결제 완료)
 * - 가맹점은 D+1, D+2 등 정산 주기에 따라 실제 입금받음
 * - 정산 실패 시 구매자 환불 없음. 가상계좌에 자금 보관 → 다음 정산 주기 재시도
 *
 * DB 커넥션 풀 고갈 방지:
 * - 외부 API 호출 구간에는 열린 트랜잭션 없음 → 커넥션 미점유
 * - 각 STEP이 독립 트랜잭션으로 커밋 후 즉시 반환
 *
 * 멱등성 처리 전략:
 * - 결제 시작 전 PROCESSING 상태로 선등록 (unique constraint로 동시 중복 요청 차단)
 * - 처리 완료 후 COMPLETED로 업데이트
 * - 외부 API 실패 or 이체 실패 시 레코드 삭제 → 같은 키로 재시도 허용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentService paymentService;
    private final ExternalBankService externalBankService;
    private final CashbackService cashbackService;
    private final SettlementService settlementService;
    private final PaymentIdempotencyRepository idempotencyRepository;
    private final AccountRepository accountRepository;

    @Value("${cashback.virtual-account.account-number:TOSS-VIRTUAL-001}")
    private String virtualAccountNumber;

    private Long virtualAccountId;  // 서버 시작 시 DB에서 조회해 캐싱 (매 요청마다 조회 방지)

    /** 서버 시작 시 가상계좌 ID를 조회해 캐싱 */
    @PostConstruct
    private void loadVirtualAccountId() {
        virtualAccountId = accountRepository.findByAccountNumber(virtualAccountNumber)
                .orElseThrow(() -> new IllegalStateException(
                        "[초기화 실패] 가상계좌를 찾을 수 없습니다. account-number=" + virtualAccountNumber))
                .getId();
        log.info("[초기화] 가상계좌 ID 캐싱 완료 - virtualAccountId={}", virtualAccountId);
    }

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

        // STEP 1: 외부 은행 승인 (구매자 계좌 출금 승인 - DB 커넥션 미점유 상태)
        // 승인 전 DB 미변경 → 실패 시 원복할 것 없음
        try {
            externalBankService.approve(request.getFromAccountId(), request.getAmount());
            log.info("[STEP 1 완료] 구매자 출금 승인 - from={}", request.getFromAccountId());
        } catch (CustomException e) {
            idempotencyRepository.delete(idempotencyRecord);
            log.error("[STEP 1 실패] 구매자 출금 승인 실패 - {}", e.getMessage());
            throw e;
        }

        // STEP 2: 구매자(A) → 가상계좌(C) 이체 (TX1 커밋 → DB 커넥션 즉시 반환)
        // 자금은 가상계좌에 보관, PaymentTransaction에는 최종 수신처(가맹점 B) 기록
        Long transactionId;
        try {
            transactionId = paymentService.executeTransfer(
                    request.getFromAccountId(),
                    request.getToAccountId(),     // 가맹점 B (정산 수신처, 기록용)
                    virtualAccountId,              // 가상계좌 C (실제 자금 이동 대상)
                    request.getAmount());
        } catch (CustomException e) {
            idempotencyRepository.delete(idempotencyRecord);
            log.error("[STEP 2 실패] 구매자→가상계좌 이체 실패 - {}", e.getMessage());
            throw e;
        }
        log.info("[STEP 2 완료] 가상계좌 보관 - txId={}", transactionId);

        // STEP 3: 정산 레코드 생성 (PENDING 상태로 등록 → 스케줄러가 처리)
        settlementService.createSettlementRecord(
                transactionId, virtualAccountId, request.getToAccountId(), request.getAmount());
        log.info("[STEP 3 완료] 정산 레코드 등록 - txId={}, merchant={}", transactionId, request.getToAccountId());

        // STEP 4: 캐시백 지급 (Redisson 락 + 트랜잭션) - 결제 성공 시에만 지급
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

        // STEP 5: 트랜잭션 PENDING_SETTLEMENT 상태 업데이트 (1단계 완료 - 정산 대기 중)
        paymentService.markTransactionPendingSettlement(transactionId, cashbackAmount);
        log.info("[STEP 5 완료] 정산 대기 상태 - txId={}, cashback={}", transactionId, cashbackAmount);

        // 멱등성 레코드 COMPLETED로 업데이트 (다음 동일 요청은 이 결과를 바로 반환)
        idempotencyRecord.complete(transactionId, request.getAmount(), cashbackAmount);
        idempotencyRepository.save(idempotencyRecord);

        // 구매자 입장에서는 결제 완료 응답 반환 (가맹점 정산은 스케줄러가 처리)
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
            log.warn("[멱등성] 처리 중인 중복 요청 - key={}", idempotencyKey);
            throw new CustomException(ErrorCode.DUPLICATE_PAYMENT_REQUEST);
        }

        try {
            return idempotencyRepository.saveAndFlush(
                    PaymentIdempotency.createProcessing(idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            log.warn("[멱등성] 동시 중복 요청 차단 - key={}", idempotencyKey);
            throw new CustomException(ErrorCode.DUPLICATE_PAYMENT_REQUEST);
        }
    }
}
