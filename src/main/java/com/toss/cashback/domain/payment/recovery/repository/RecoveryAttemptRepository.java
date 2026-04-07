package com.toss.cashback.domain.payment.recovery.repository;

import com.toss.cashback.domain.payment.recovery.entity.RecoveryAttempt;
import com.toss.cashback.domain.payment.recovery.entity.RecoveryResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// ======= [15번] 복구 시도 이력 레포지토리 =======
public interface RecoveryAttemptRepository extends JpaRepository<RecoveryAttempt, Long> {

    /** 특정 결제 건의 모든 복구 시도 이력 (시간순) */
    List<RecoveryAttempt> findByPaymentTransactionIdOrderByAttemptedAtDesc(Long paymentTransactionId);

    /** 특정 결제 건의 복구 시도 횟수 */
    int countByPaymentTransactionId(Long paymentTransactionId);

    /** 복구 실패 건만 조회 (운영 모니터링용) */
    List<RecoveryAttempt> findByResult(RecoveryResult result);
}
