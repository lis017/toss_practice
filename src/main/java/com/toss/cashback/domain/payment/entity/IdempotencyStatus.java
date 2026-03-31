package com.toss.cashback.domain.payment.entity;

/**
 * 멱등성 레코드 처리 상태
 *
 * 상태 전이:
 * PROCESSING → COMPLETED (결제 성공 후 결과 저장)
 * PROCESSING → 삭제      (이체 실패 or 보상 완료 → 같은 키로 재시도 허용)
 */
public enum IdempotencyStatus {
    PROCESSING, // 결제 진행 중 (선등록 상태 - 중복 요청 차단용)
    COMPLETED   // 결제 완료 (캐시된 결과 반환용)
}
