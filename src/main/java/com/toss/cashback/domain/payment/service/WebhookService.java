package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.payment.entity.MerchantSettlementPolicy;
import com.toss.cashback.domain.payment.entity.WebhookDelivery;
import com.toss.cashback.domain.payment.entity.WebhookDeliveryStatus;
import com.toss.cashback.domain.payment.entity.WebhookEventType;
import com.toss.cashback.domain.payment.repository.MerchantSettlementPolicyRepository;
import com.toss.cashback.domain.payment.repository.WebhookDeliveryRepository;
import com.toss.cashback.infrastructure.webhook.WebhookClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// ======= [신규] 웹훅 발송 서비스 =======
/**
 * =====================================================================
 * [설계 의도] 웹훅 발송 - 가맹점에게 결제 완료 이벤트 전달
 * =====================================================================
 *
 * 발송 흐름:
 * 1. MerchantSettlementPolicy에서 가맹점 webhookUrl 조회
 * 2. WebhookDelivery 레코드 생성 (PENDING 상태)
 * 3. WebhookClient.send() 호출 → 성공이면 SUCCESS 기록
 * 4. 실패 시 1회 재시도 → 성공이면 SUCCESS, 또 실패하면 FAILED 기록
 *
 * 중요: 이 서비스는 예외를 외부로 전파하지 않습니다.
 * 웹훅 실패가 결제 응답에 영향을 주지 않도록 PaymentFacade에서 try-catch로 호출합니다.
 *
 * 추후 확장 포인트:
 * - FAILED 건 자동 재발송 배치 (현재는 운영팀 수동 재발송)
 * - 웹훅 서명(HMAC-SHA256) 추가로 가맹점 검증 강화
 * =====================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookClient webhookClient;
    private final MerchantSettlementPolicyRepository policyRepository;

    /**
     * PAYMENT_COMPLETED 웹훅 발송.
     * 가맹점 webhookUrl이 미등록이면 조용히 스킵합니다.
     *
     * @param transactionId     결제 트랜잭션 ID
     * @param merchantAccountId 가맹점 계좌 ID (webhookUrl 조회 키)
     * @param amount            결제 금액
     */
    public void sendPaymentCompleted(Long transactionId, Long merchantAccountId, Long amount) {
        // 가맹점 웹훅 URL 조회 - 미등록이면 발송 스킵
        String webhookUrl = policyRepository.findByMerchantAccountId(merchantAccountId)
                .map(MerchantSettlementPolicy::getWebhookUrl)
                .orElse(null);

        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("[웹훅] 가맹점 webhookUrl 미등록 - 발송 스킵 (merchantId={}, txId={})",
                    merchantAccountId, transactionId);
            return;
        }

        // 발송 이력 레코드 PENDING 상태로 생성
        WebhookDelivery delivery = WebhookDelivery.createPending(
                transactionId, webhookUrl, WebhookEventType.PAYMENT_COMPLETED);
        deliveryRepository.save(delivery);

        String payload = buildPayload(transactionId, amount);
        attemptWithRetry(delivery, payload);
    }

    /**
     * 1차 시도 + 실패 시 1회 재시도.
     * 두 번 모두 실패하면 FAILED 상태로 기록합니다.
     */
    private void attemptWithRetry(WebhookDelivery delivery, String payload) {
        try {
            webhookClient.send(delivery.getWebhookUrl(), payload);
            delivery.markSuccess();
            log.info("[웹훅] 발송 성공 - txId={}", delivery.getPaymentTransactionId());

        } catch (Exception firstEx) {
            log.warn("[웹훅] 1차 실패 → 재시도 중 - txId={}, reason={}",
                    delivery.getPaymentTransactionId(), firstEx.getMessage());
            delivery.incrementRetryAndFail(firstEx.getMessage());

            try {
                webhookClient.send(delivery.getWebhookUrl(), payload);
                delivery.markSuccess();
                log.info("[웹훅] 재시도 성공 - txId={}", delivery.getPaymentTransactionId());

            } catch (Exception retryEx) {
                delivery.markFailed(retryEx.getMessage());
                log.error("[웹훅] 최종 실패 (재시도 1회 소진) - txId={}, reason={}",
                        delivery.getPaymentTransactionId(), retryEx.getMessage());
                log.error("[복구 가이드] webhook_deliveries 테이블에서 FAILED 건 조회 후 수동 재발송 가능");
            }
        }

        // 최종 상태(SUCCESS or FAILED) 저장
        deliveryRepository.save(delivery);
    }

    /** JSON 페이로드 생성 (간단한 문자열 포맷 - 실제 운영에서는 ObjectMapper 사용 권장) */
    private String buildPayload(Long transactionId, Long amount) {
        return String.format(
                "{\"eventType\":\"PAYMENT_COMPLETED\",\"transactionId\":%d,\"amount\":%d}",
                transactionId, amount);
    }
}
