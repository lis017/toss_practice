package com.toss.cashback.global.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 로그 기반 알림 구현체 (현재 사용 중)
 *
 * 운영 환경에서는 이 구현체를 Slack / PagerDuty / Email 구현체로 교체하면 됩니다.
 * 예시:
 *   - Slack: RestTemplate으로 Incoming Webhook URL에 POST
 *   - PagerDuty: Events API v2 호출
 *   - Email: Spring Mail + JavaMailSender
 *
 * 과제 범위에서는 ERROR 레벨 로그로 남깁니다.
 * 실제 운영 환경에서는 모니터링 시스템(Datadog, Grafana 등)이 ERROR 로그를 감지해 알림을 보냅니다.
 */
@Slf4j
@Service
public class LogAlertService implements AlertService {

    @Override
    public void sendCompensationFailureAlert(Long transactionId, String reason) {
        // 실제 운영 환경에서는 여기서 Slack/PagerDuty API 호출
        log.error("========================================");
        log.error("[운영 알림] 보상 트랜잭션 실패 - 수동 개입 필요");
        log.error("[운영 알림] txId={}", transactionId);
        log.error("[운영 알림] 원인={}", reason);
        log.error("[운영 알림] 조치: payment_transactions 테이블에서 해당 txId 확인 후 계좌 수동 원복");
        log.error("========================================");
    }
}
