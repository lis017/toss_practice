package com.toss.cashback.domain.payment.entity;

/**
 * 결제 트랜잭션 상태 열거형
 *
 * 상태 전이:
 * PENDING → SUCCESS              (정상 흐름: 이체 완료 → 외부 승인 완료)
 * PENDING → COMPENSATED          (외부 API 실패 → 보상 트랜잭션 실행 완료)
 * PENDING → COMPENSATION_FAILED  (보상 트랜잭션 자체도 실패 → 수동 개입 필요)
 * PENDING → FAILED               (비즈니스 예외 발생 시)
 */
public enum PaymentStatus {
    PENDING,              // 이체 완료, 외부 은행 승인 대기 중
    SUCCESS,              // 외부 승인까지 완료된 정상 결제
    FAILED,               // 비즈니스 예외(잔액 부족 등)로 인한 실패
    COMPENSATED,          // 외부 API 실패로 보상 트랜잭션 실행 완료 (계좌 원복)
    COMPENSATION_FAILED   // 보상 트랜잭션 자체 실패 - 계좌 불일치 상태, 수동 개입 필요
}
