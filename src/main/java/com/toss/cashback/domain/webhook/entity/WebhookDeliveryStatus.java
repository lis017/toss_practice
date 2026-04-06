package com.toss.cashback.domain.webhook.entity;

// ======= [7번] 웹훅 발송 상태 =======
public enum WebhookDeliveryStatus {
    PENDING,    // 발송 전 (생성 직후)
    SUCCESS,    // 발송 성공 (1차 또는 재시도 성공)
    FAILED      // 최종 실패 (1회 재시도까지 소진)
}
