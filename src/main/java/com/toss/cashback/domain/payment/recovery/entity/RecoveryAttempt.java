package com.toss.cashback.domain.payment.recovery.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

// ======= [15번] 복구 시도 이력 엔티티 =======
/**
 * =====================================================================
 * [설계 의도] POST_PROCESS_FAILED 복구 시도 이력 - 운영 감사 로그
 * =====================================================================
 *
 * 저장 목적:
 * - 자동/수동 복구 시도를 DB에 기록 → 운영팀이 복구 이력 추적 가능
 * - "몇 번 시도했는데 모두 실패한 건"을 파악 → 수동 개입 우선순위 결정
 * - 복구 성공 시점 기록 → 불일치 기간(출금은 됐는데 정산 대기 전까지) 산출
 *
 * 인덱스 설계:
 * - paymentTransactionId: 특정 결제 건의 모든 복구 이력 조회
 * - triggerType + result: 자동 복구 실패 현황 모니터링
 * =====================================================================
 */
@Entity
@Table(
    name = "recovery_attempts",
    indexes = {
        @Index(name = "idx_recovery_tx", columnList = "paymentTransactionId"),
        @Index(name = "idx_recovery_result", columnList = "triggerType, result")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecoveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long paymentTransactionId;      // 복구 대상 결제 트랜잭션 ID

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RecoveryTriggerType triggerType; // AUTO(스케줄러) / MANUAL(운영자)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RecoveryResult result;           // SUCCESS / FAILED

    @Column(length = 500)
    private String failureReason;           // 복구 실패 사유 (성공 시 null)

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime attemptedAt;      // 복구 시도 시각

    public static RecoveryAttempt success(Long txId, RecoveryTriggerType triggerType) {
        RecoveryAttempt attempt = new RecoveryAttempt();
        attempt.paymentTransactionId = txId;
        attempt.triggerType = triggerType;
        attempt.result = RecoveryResult.SUCCESS;
        return attempt;
    }

    public static RecoveryAttempt failure(Long txId, RecoveryTriggerType triggerType,
                                           String failureReason) {
        RecoveryAttempt attempt = new RecoveryAttempt();
        attempt.paymentTransactionId = txId;
        attempt.triggerType = triggerType;
        attempt.result = RecoveryResult.FAILED;
        attempt.failureReason = failureReason;
        return attempt;
    }
}
