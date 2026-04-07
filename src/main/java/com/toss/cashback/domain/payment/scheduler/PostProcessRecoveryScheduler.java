package com.toss.cashback.domain.payment.scheduler;

import com.toss.cashback.domain.payment.entity.PaymentStatus;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.payment.recovery.entity.RecoveryTriggerType;
import com.toss.cashback.domain.payment.recovery.service.RecoveryService;
import com.toss.cashback.domain.payment.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// ======= [15번] POST_PROCESS_FAILED 자동 복구 스케줄러 =======
/**
 * POST_PROCESS_FAILED 건을 10분마다 자동 복구 시도.
 * 실제 복구 로직은 RecoveryService에 위임 (단일 책임 원칙).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostProcessRecoveryScheduler {

    private final PaymentTransactionRepository transactionRepository;
    private final RecoveryService recoveryService;

    /**
     * 10분마다 POST_PROCESS_FAILED 건 자동 복구.
     * fixedDelay: 이전 실행 완료 후 10분 대기 (겹침 방지).
     */
    @Scheduled(fixedDelay = 600_000)
    public void recoverPostProcessFailed() {
        List<PaymentTransaction> failedList =
                transactionRepository.findByStatus(PaymentStatus.POST_PROCESS_FAILED);

        if (failedList.isEmpty()) {
            return;
        }

        log.warn("[복구 스케줄러] POST_PROCESS_FAILED 건 감지 - 건수: {}", failedList.size());

        int successCount = 0;
        int failCount = 0;

        for (PaymentTransaction tx : failedList) {
            try {
                recoveryService.recover(tx.getId(), RecoveryTriggerType.AUTO);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("[복구 스케줄러] 복구 실패 - txId={}", tx.getId());
            }
        }

        log.warn("[복구 스케줄러] 완료 - 성공={}, 실패={}", successCount, failCount);
    }
}
