package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.payment.dto.response.PaymentQueryResponse;
import com.toss.cashback.domain.payment.entity.PaymentIdempotency;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.payment.repository.PaymentIdempotencyRepository;
import com.toss.cashback.domain.payment.repository.PaymentTransactionRepository;
import com.toss.cashback.domain.settlement.entity.SettlementRecord;
import com.toss.cashback.domain.settlement.repository.SettlementRepository;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// ======= [15번] 결제 조회 서비스 =======
/**
 * =====================================================================
 * [설계 의도] 결제 상태 조회 - "결제가 실제로 됐는지" 확인용
 * =====================================================================
 *
 * 운영 핵심 시나리오:
 * - 클라이언트가 500을 받았는데 결제가 실제로 됐는지 확인
 * - POST_PROCESS_FAILED 건 현황 파악 (운영 대시보드)
 * - 정산 진행 상태 확인 (PENDING_SETTLEMENT → SETTLED)
 *
 * 조회 방법 2가지:
 * 1. transactionId로 직접 조회 (결제 성공 응답을 받은 경우)
 * 2. idempotencyKey로 조회 (결제 완료된 건의 키를 가진 경우)
 * =====================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentQueryService {

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentIdempotencyRepository idempotencyRepository;
    private final SettlementRepository settlementRepository;

    /**
     * transactionId로 결제 + 정산 상태 조회
     */
    public PaymentQueryResponse queryByTransactionId(Long transactionId) {
        PaymentTransaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_FOUND));

        SettlementRecord settlement = settlementRepository.findByPaymentTransactionId(transactionId)
                .stream().findFirst().orElse(null);

        log.info("[조회] txId={}, paymentStatus={}, settlementStatus={}",
                transactionId, tx.getStatus(),
                settlement != null ? settlement.getStatus() : "없음");

        return PaymentQueryResponse.of(tx, settlement);
    }

    /**
     * idempotencyKey로 완료된 결제 조회.
     * PROCESSING 중이거나 만료된 키는 조회 불가 (PAYMENT_NOT_FOUND).
     */
    public PaymentQueryResponse queryByIdempotencyKey(String idempotencyKey) {
        PaymentIdempotency idempotency = idempotencyRepository
                .findByIdempotencyKeyAndExpiresAtAfter(idempotencyKey, LocalDateTime.now())
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_FOUND));

        if (!idempotency.isCompleted() || idempotency.getTransactionId() == null) {
            log.info("[조회] 멱등성 키 처리 중 또는 미완료 - key={}", idempotencyKey);
            throw new CustomException(ErrorCode.PAYMENT_NOT_FOUND);
        }

        return queryByTransactionId(idempotency.getTransactionId());
    }
}
