package com.toss.cashback.domain.payment.entity;

/**
 * 정산 레코드 상태 열거형
 *
 * 상태 전이:
 * PENDING → SETTLED  (정산 스케줄러 실행 → 가맹점 입금 완료)
 * PENDING → PENDING  (외부 API 실패 시 상태 유지 → 다음 정산 주기에 재시도)
 */
public enum SettlementStatus {
    PENDING,  // 정산 대기 중 (가상계좌에 자금 보관 중)
    SETTLED   // 정산 완료 (가맹점 입금 완료)
}
