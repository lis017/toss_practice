package com.toss.cashback.domain.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

// ======= [2번] 결제 트랜잭션 엔티티 =======
/**
 * =====================================================================
 * [설계 의도] 결제 트랜잭션 이력 엔티티 (C안 - PG 가상계좌 정산 구조)
 * =====================================================================
 *
 * 핵심 역할:
 * 1. 결제 의도 기록: fromAccountId(구매자) → toAccountId(가맹점) 결제 요청 추적
 * 2. 중간 보관 기록: virtualAccountId - 정산 전까지 자금을 보관하는 토스 가상계좌
 * 3. 상태 추적: PENDING → PENDING_SETTLEMENT(가상계좌 보관 중) → SUCCESS(정산 완료)
 * 4. 감사 로그: createdAt, updatedAt 자동 기록 (법적 증빙)
 *
 * 자금 흐름:
 * - 1단계(즉시): 구매자(fromAccountId) → 가상계좌(virtualAccountId)
 * - 2단계(정산): 가상계좌(virtualAccountId) → 가맹점(toAccountId)
 *
 * 인덱스 설계:
 * - fromAccountId: "내 결제 이력 조회" 최적화 (마이페이지)
 * - status: PENDING_SETTLEMENT 건 정산 배치 처리 최적화
 * =====================================================================
 */
@Entity
@Table(
    name = "payment_transactions",
    indexes = {
        @Index(name = "idx_from_account_id", columnList = "fromAccountId"),
        @Index(name = "idx_status", columnList = "status")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long fromAccountId;             // 구매자 계좌 ID (출금 계좌)

    @Column(nullable = false)
    private Long toAccountId;               // 가맹점 계좌 ID (최종 수신처, 정산 후 입금)

    @Column(nullable = false)
    private Long virtualAccountId;          // 토스 가상계좌 ID (정산 전 자금 보관)

    @Column(nullable = false)
    private Long amount;                    // 결제 금액 (원)

    @Column(nullable = false)
    private Long cashbackAmount;            // 실제 적립된 캐시백 (0이면 예산 소진)

    @Enumerated(EnumType.STRING)            // "PENDING", "SUCCESS" 등 문자열 저장 (가독성)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(length = 500)
    private String failureReason;           // 실패 사유 (운영 디버깅용)

    @CreationTimestamp
    @Column(updatable = false)              // 생성 후 변경 불가 (감사 로그 무결성)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    public PaymentTransaction(Long fromAccountId, Long toAccountId, Long virtualAccountId, Long amount) {
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.virtualAccountId = virtualAccountId;
        this.amount = amount;
        this.cashbackAmount = 0L;
        this.status = PaymentStatus.PENDING;    // 초기 상태는 반드시 PENDING
    }

    /** 1단계 완료: 구매자 출금 성공 + 가상계좌 보관 중. 정산 스케줄러가 처리할 예정 */
    public void markPendingSettlement(Long cashbackAmount) {
        this.status = PaymentStatus.PENDING_SETTLEMENT;
        this.cashbackAmount = cashbackAmount;   // 캐시백은 1단계에서 이미 지급 완료
    }

    /** 2단계 완료: 가맹점 정산 완료 → 결제 전체 완료. cashbackAmount는 이미 설정되어 있음 */
    public void markSuccess() {
        this.status = PaymentStatus.SUCCESS;
    }

    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }
}
