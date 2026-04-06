package com.toss.cashback.domain.webhook.entity;

// ======= [7번] 웹훅 이벤트 타입 =======
public enum WebhookEventType {
    PAYMENT_COMPLETED   // 구매자 결제 완료 (가상계좌 보관 완료 → 가맹점에게 알림)
}
