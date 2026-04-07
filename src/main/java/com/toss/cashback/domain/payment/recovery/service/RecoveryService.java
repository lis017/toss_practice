package com.toss.cashback.domain.payment.recovery.service;

import com.toss.cashback.domain.payment.entity.PaymentStatus;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.payment.recovery.entity.RecoveryAttempt;
import com.toss.cashback.domain.payment.recovery.entity.RecoveryResult;
import com.toss.cashback.domain.payment.recovery.entity.RecoveryTriggerType;
import com.toss.cashback.domain.payment.recovery.repository.RecoveryAttemptRepository;
import com.toss.cashback.domain.payment.repository.PaymentTransactionRepository;
import com.toss.cashback.domain.payment.service.PaymentService;
import com.toss.cashback.domain.settlement.repository.SettlementRepository;
import com.toss.cashback.domain.settlement.service.SettlementService;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// ======= [15번] POST_PROCESS_FAILED 복구 서비스 =======
/**
 * =====================================================================
 * [설계 의도] 결제 후처리 실패 건 복구 - "장애 복구를 제품 기능으로 설계"
 * =====================================================================
 *
 * 복구 조건:
 * - PaymentTransaction.status = POST_PROCESS_FAILED인 건만 복구 대상
 * - 이미 복구된 건(PENDING_SETTLEMENT/SUCCESS)은 no-op (멱등성)
 *
 * 복구 단계:
 * 1. 정산 레코드 미존재 시 재생성 (DataIntegrityViolationException → 이미 존재로 간주)
 * 2. 트랜잭션 상태를 PENDING_SETTLEMENT로 복구
 * 3. RecoveryAttempt 이력 저장 (성공/실패 모두)
 *
 * 트리거:
 * - AUTO: PostProcessRecoveryScheduler (10분 주기 자동)
 * - MANUAL: POST /api/v1/admin/payments/{id}/recover (운영자 수동)
 *
 * 설계 원칙:
 * - 이 서비스는 @Transactional 없음 → 각 단계별 독립 트랜잭션
 * - RecoveryAttempt 저장은 복구 성공/실패와 무관하게 항상 실행
 * =====================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryService {

    private final PaymentTransactionRepository transactionRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementService settlementService;
    private final PaymentService paymentService;
    private final RecoveryAttemptRepository recoveryAttemptRepository;

    /**
     * 단건 복구 실행.
     *
     * @param txId        복구 대상 PaymentTransaction ID
     * @param triggerType AUTO(스케줄러) 또는 MANUAL(운영자)
     * @throws CustomException 복구 불가 시 (PAYMENT_NOT_FOUND 또는 INTERNAL_SERVER_ERROR)
     */
    public void recover(Long txId, RecoveryTriggerType triggerType) {
        PaymentTransaction tx = transactionRepository.findById(txId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_FOUND));

        // 이미 복구된 건이나 정상 상태인 경우 no-op (멱등성)
        if (tx.getStatus() != PaymentStatus.POST_PROCESS_FAILED) {
            log.info("[복구] 복구 대상 아님 - txId={}, currentStatus={} (no-op)",
                    txId, tx.getStatus());
            recoveryAttemptRepository.save(
                    RecoveryAttempt.success(txId, triggerType));  // no-op도 성공으로 기록
            return;
        }

        try {
            doRecover(tx);
            recoveryAttemptRepository.save(RecoveryAttempt.success(txId, triggerType));
            log.info("[복구 성공] txId={}, triggerType={}", txId, triggerType);

        } catch (Exception e) {
            recoveryAttemptRepository.save(
                    RecoveryAttempt.failure(txId, triggerType, e.getMessage()));
            log.error("[복구 실패] txId={}, triggerType={}, reason={}", txId, triggerType, e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 실제 복구 처리 (각 단계가 독립 트랜잭션)
     */
    private void doRecover(PaymentTransaction tx) {
        // 정산 레코드 재생성 (없을 때만)
        boolean settlementExists =
                !settlementRepository.findByPaymentTransactionId(tx.getId()).isEmpty();

        if (!settlementExists) {
            try {
                settlementService.createSettlementRecord(
                        tx.getId(),
                        tx.getVirtualAccountId(),
                        tx.getToAccountId(),
                        tx.getAmount()
                );
                log.info("[복구] 정산 레코드 재생성 완료 - txId={}", tx.getId());
            } catch (DataIntegrityViolationException e) {
                // 동시 복구 실행 등으로 이미 생성된 경우 → 안전하게 계속
                log.info("[복구] 정산 레코드 동시 생성 감지 (무시) - txId={}", tx.getId());
            }
        } else {
            log.info("[복구] 정산 레코드 이미 존재, 재생성 스킵 - txId={}", tx.getId());
        }

        // 트랜잭션 상태 PENDING_SETTLEMENT로 복구
        paymentService.markTransactionPendingSettlement(tx.getId());
    }

    /**
     * 특정 결제 건의 복구 이력 조회 (운영 확인용)
     */
    public java.util.List<RecoveryAttempt> getRecoveryHistory(Long txId) {
        return recoveryAttemptRepository.findByPaymentTransactionIdOrderByAttemptedAtDesc(txId);
    }

    /**
     * 전체 복구 실패 현황 조회 (운영 모니터링용)
     */
    public java.util.List<RecoveryAttempt> getFailedRecoveries() {
        return recoveryAttemptRepository.findByResult(RecoveryResult.FAILED);
    }
}
