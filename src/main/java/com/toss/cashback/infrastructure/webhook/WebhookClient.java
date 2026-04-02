package com.toss.cashback.infrastructure.webhook;

// ======= [신규] 웹훅 HTTP 발송 인터페이스 =======
/**
 * 가맹점 웹훅 발송 추상화 인터페이스.
 *
 * 실제 운영 환경에서는 RestTemplate 또는 WebClient 기반 구현체로 교체합니다.
 * 현재는 MockWebhookClient가 기본 구현체입니다.
 */
public interface WebhookClient {

    /**
     * 웹훅 발송
     *
     * @param webhookUrl  가맹점 수신 URL (예: https://merchant.com/webhook)
     * @param jsonPayload JSON 형식의 이벤트 데이터
     * @throws RuntimeException 발송 실패 시 (WebhookService에서 재시도 처리)
     */
    void send(String webhookUrl, String jsonPayload);
}
