package com.toss.cashback.domain.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

// ======= [2번] 정산 레코드 엔티티 =======
/**
 * =====================================================================
 * [설계 의도] PG 정산 레코드 - 가상계좌 보관 → 가맹점 입금 이력 관리
 * =====================================================================
 *
 * 핵심 역할:
 * 1. 정산 대상 추적: 가상계좌(C)에서 가맹점(B)으로 보내야 할 금액 기록
 * 2. 금액 내역 저장: grossAmount / feeAmount / vatAmount / netAmount 분리 보관
 * 3. 재시도 안전성: 정산 실패 시 PENDING 유지 → 다음 정산 주기 자동 재시도
 * 4. 원본 연결: paymentTransactionId로 구매자 결제와 연결 (감사 추적)
 *
 * 금액 흐름:
 *   구매자 결제 → grossAmount 전액이 가상계좌에 보관
 *   정산 시 → netAmount만 가맹점에 입금 (feeAmount + vatAmount는 PG 수익)
 *
 * 실제 PG(토스페이먼츠)에서는 D+1, D+2 정산 주기로 운영됩니다.
 * =====================================================================
 */
@Entity
@Table(
    name = "settlement_records",
    indexes = {
        @Index(name = "idx_settlement_status", columnList = "status"),
        @Index(name = "idx_settlement_merchant", columnList = "merchantAccountId"),
        @Index(name = "idx_settlement_expected_date", columnList = "expectedSettlementDate")
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

    // =====================================================================
    // [정산 금액 내역] SettlementCalculator.calculate()로 생성되며, 변경 불가
    // =====================================================================

    @Column(nullable = false)
    private Long grossAmount;               // 결제 원금 (구매자가 낸 전체 금액)

    @Column(nullable = false)
    private Long feeAmount;                 // 수수료 금액 (grossAmount × feeRate)

    @Column(nullable = false)
    private Long vatAmount;                 // 부가세 금액 (feeAmount × 10%, vatIncluded=false면 0)

    @Column(nullable = false)
    private Long netAmount;                 // 실지급액 (grossAmount - feeAmount - vatAmount, 가맹점 수령액)

    @Column(nullable = false)
    private LocalDate expectedSettlementDate;  // 정산 예정일 (오늘 + settlementCycleDays)

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

    /**
     * 구매자 결제 완료 시 정산 레코드 생성 (PENDING 상태로 시작)
     * 금액 내역은 SettlementCalculator.calculate()의 결과를 그대로 저장합니다.
     */
    public static SettlementRecord createPending(Long paymentTransactionId, Long virtualAccountId,
                                                  Long merchantAccountId, SettlementAmountResult calculation) {
        SettlementRecord record = new SettlementRecord();
        record.paymentTransactionId = paymentTransactionId;
        record.virtualAccountId = virtualAccountId;
        record.merchantAccountId = merchantAccountId;
        record.grossAmount = calculation.getGrossAmount();
        record.feeAmount = calculation.getFeeAmount();
        record.vatAmount = calculation.getVatAmount();
        record.netAmount = calculation.getNetAmount();
        record.expectedSettlementDate = calculation.getExpectedSettlementDate();
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
