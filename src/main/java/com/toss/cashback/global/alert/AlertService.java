package com.toss.cashback.global.alert;

/**
 * 운영 알림 서비스 인터페이스
 *
 * 보상 트랜잭션 실패처럼 수동 개입이 필요한 심각한 상황을 운영팀에 알립니다.
 * 구현체를 교체하는 것만으로 Slack / PagerDuty / Email 등 알림 채널을 전환할 수 있습니다.
 *
 * 운영 환경 전환 예:
 * - LogAlertService (현재) → Slack WebhookAlertService
 * - @ConditionalOnProperty로 환경별 구현체 자동 선택 가능
 */
public interface AlertService {

    /**
     * 보상 트랜잭션 실패 알림
     * 계좌 잔액이 불일치 상태이므로 운영팀이 수동으로 원복해야 합니다.
     *
     * @param transactionId 보상 실패한 결제 트랜잭션 ID
     * @param reason        실패 사유 (예외 메시지)
     */
    void sendCompensationFailureAlert(Long transactionId, String reason);
}
