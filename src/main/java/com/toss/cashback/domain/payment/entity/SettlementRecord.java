package com.toss.cashback.domain.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

// ======= [2번] 정산 레코드 엔티티 =======
/**
 * =====================================================================
 * [설계 의도] PG 정산 레코드 - 가상계좌 보관 → 가맹점 입금 이력 관리
 * =====================================================================
 *
 * 핵심 역할:
 * 1. 정산 대상 추적: 가상계좌(C)에서 가맹점(B)으로 보내야 할 금액 기록
 * 2. 재시도 안전성: 정산 실패 시 PENDING 유지 → 다음 정산 주기 자동 재시도
 * 3. 원본 연결: paymentTransactionId로 구매자 결제와 연결 (감사 추적)
 *
 * 실제 PG 정산 구조:
 * - 구매자 결제 즉시: SettlementRecord 생성 (PENDING)
 * - 매일 정산 시간: SettlementScheduler가 PENDING 건 일괄 처리 → SETTLED
 * - 정산 실패 시:  PENDING 유지 → 다음 날 재시도 (구매자 환불 없음)
 *
 * 실제 PG(토스페이먼츠)에서는 D+1, D+2 정산 주기로 운영됩니다.
 * =====================================================================
 */
@Entity
@Table(
    name = "settlement_records",
    indexes = {
        @Index(name = "idx_settlement_status", columnList = "status"),
        @Index(name = "idx_settlement_merchant", columnList = "merchantAccountId")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long paymentTransactionId;      // 원본 결제 트랜잭션 ID (역추적용)

    @Column(nullable = false)
    private Long virtualAccountId;          // 토스 가상계좌 ID (자금 보관 중)

    @Column(nullable = false)
    private Long merchantAccountId;         // 가맹점 계좌 ID (정산 대상)

    @Column(nullable = false)
    private Long amount;                    // 정산 금액

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    @Column(length = 500)
    private String failureReason;           // 마지막 실패 사유 (재시도 시 덮어씀)

    private LocalDateTime settledAt;        // 정산 완료 시각

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** 구매자 결제 완료 시 정산 레코드 생성 (PENDING 상태로 시작) */
    public static SettlementRecord createPending(Long paymentTransactionId, Long virtualAccountId,
                                                  Long merchantAccountId, Long amount) {
        SettlementRecord record = new SettlementRecord();
        record.paymentTransactionId = paymentTransactionId;
        record.virtualAccountId = virtualAccountId;
        record.merchantAccountId = merchantAccountId;
        record.amount = amount;
        record.status = SettlementStatus.PENDING;
        return record;
    }

    /** 정산 완료 처리 (가맹점 입금 확인 후 호출) */
    public void markSettled() {
        this.status = SettlementStatus.SETTLED;
        this.settledAt = LocalDateTime.now();
        this.failureReason = null;
    }

    /** 정산 실패 기록 (상태는 PENDING 유지 → 다음 정산 주기 재시도) */
    public void recordFailure(String reason) {
        this.failureReason = reason;    // 상태는 PENDING 유지 (자동 재시도)
    }
}
