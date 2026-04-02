package com.toss.cashback.infrastructure.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// ======= [신규] 웹훅 Mock 발송 구현체 =======
/**
 * 실제 HTTP 호출 없이 로그만 남기는 Mock 구현체.
 *
 * 실제 운영 전환 시 이 클래스를 RestTemplate/WebClient 기반 구현체로 교체합니다.
 * application.yml의 webhook.simulate-error: true로 설정하면 실패 시나리오를 테스트할 수 있습니다.
 */
@Slf4j
@Component
public class MockWebhookClient implements WebhookClient {

    // [변경] true로 설정하면 발송 실패 시뮬레이션 (재시도 로직 테스트용)
    @Value("${webhook.simulate-error:false}")
    private boolean simulateError;

    @Override
    public void send(String webhookUrl, String jsonPayload) {
        if (simulateError) {
            throw new RuntimeException("[Mock] 웹훅 발송 실패 시뮬레이션 (webhook.simulate-error=true)");
        }
        log.info("[웹훅 Mock] 발송 완료 - url={}, payload={}", webhookUrl, jsonPayload);
    }
}
