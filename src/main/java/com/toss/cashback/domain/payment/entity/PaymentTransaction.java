package com.toss.cashback.domain.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * =====================================================================
 * [설계 의도] 결제 트랜잭션 이력 엔티티
 * =====================================================================
 *
 * 핵심 역할:
 * 1. 보상 트랜잭션 참조: 외부 은행 실패 시 원본 결제 정보 조회 → 역방향 이체
 * 2. 상태 추적: PENDING → SUCCESS/COMPENSATED 흐름 관리
 * 3. 감사 로그: createdAt, updatedAt 자동 기록 (법적 증빙)
 *
 * 인덱스 설계:
 * - fromAccountId: "내 결제 이력 조회" 최적화 (마이페이지)
 * - status: PENDING 건 배치 처리 최적화 (모니터링)
 *
 * 추후 리팩터:
 * - 이벤트 소싱(Event Sourcing) 도입 시 이 엔티티가 이벤트 스토어 역할
 * - 결제 방법(BANK_TRANSFER, CARD 등) 컬럼 추가로 다중 결제 수단 지원
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
    private Long fromAccountId;             // 송금자 계좌 ID

    @Column(nullable = false)
    private Long toAccountId;               // 수신자(상점) 계좌 ID

    @Column(nullable = false)
    private Long amount;                    // 결제 금액 (원)

    @Column(nullable = false)
    private Long cashbackAmount;            // 실제 적립된 캐시백 (0이면 예산 소진)

    @Enumerated(EnumType.STRING)            // "PENDING", "SUCCESS" 등 문자열 저장 (가독성)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(length = 500)
    private String failureReason;           // 실패/보상 사유 (운영 디버깅용)

    @CreationTimestamp
    @Column(updatable = false)              // 생성 후 변경 불가 (감사 로그 무결성)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    public PaymentTransaction(Long fromAccountId, Long toAccountId, Long amount) {
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.cashbackAmount = 0L;
        this.status = PaymentStatus.PENDING;    // 초기 상태는 반드시 PENDING
    }

    public void markSuccess(Long cashbackAmount) {
        this.status = PaymentStatus.SUCCESS;
        this.cashbackAmount = cashbackAmount;
    }

    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }

    public void markCompensated(String reason) {
        this.status = PaymentStatus.COMPENSATED;
        this.failureReason = reason;
    }
}
