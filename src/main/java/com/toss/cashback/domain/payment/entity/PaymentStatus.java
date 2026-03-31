package com.toss.cashback.domain.payment.entity;

/**
 * 결제 트랜잭션 상태 열거형
 *
 * C안 (PG 가상계좌 정산) 상태 전이:
 * PENDING → PENDING_SETTLEMENT  (구매자 출금 완료, 가상계좌 보관 중 - 정산 대기)
 * PENDING_SETTLEMENT → SUCCESS  (가맹점 정산 완료 - 결제 전체 완료)
 * PENDING → FAILED              (비즈니스 예외 - 잔액 부족 등)
 */
public enum PaymentStatus {
    PENDING,              // 결제 생성 초기 상태
    PENDING_SETTLEMENT,   // 구매자 출금 완료 + 가상계좌 보관 중 (정산 스케줄러 대기)
    SUCCESS,              // 가맹점 정산 완료 (결제 전체 완료)
    FAILED                // 비즈니스 예외로 인한 실패 (잔액 부족 등)
}
