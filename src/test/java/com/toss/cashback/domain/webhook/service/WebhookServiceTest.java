package com.toss.cashback.domain.webhook.service;

import com.toss.cashback.domain.settlement.entity.MerchantSettlementPolicy;
import com.toss.cashback.domain.settlement.repository.MerchantSettlementPolicyRepository;
import com.toss.cashback.domain.webhook.entity.WebhookDelivery;
import com.toss.cashback.domain.webhook.entity.WebhookDeliveryStatus;
import com.toss.cashback.domain.webhook.repository.WebhookDeliveryRepository;
import com.toss.cashback.infrastructure.webhook.WebhookClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// ======= [13번] 웹훅 서비스 단위 테스트 =======
/**
 * =====================================================================
 * WebhookService 단위 테스트 (Mockito)
 * =====================================================================
 *
 * 테스트 전략:
 * - Spring Context 없이 순수 단위 테스트
 * - WebhookClient, Repository를 Mock으로 대체
 *
 * 핵심 설계 검증:
 * - 웹훅 실패가 예외를 외부로 전파하지 않음 (결제 응답에 영향 없음)
 * - 1차 실패 시 자동 재시도 1회 동작
 * - 모든 실패 시 FAILED 상태 기록 (운영팀 수동 재발송 기반)
 *
 * 검증 시나리오:
 * 1. 가맹점 webhookUrl 미등록 → 발송 스킵 (DB 저장 없음)
 * 2. 1차 발송 성공 → SUCCESS, retryCount=0
 * 3. 1차 실패 → 재시도 성공 → SUCCESS, retryCount=1
 * 4. 1차·재시도 모두 실패 → FAILED, 예외 전파 없음
 * =====================================================================
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookService 단위 테스트")
class WebhookServiceTest {

    @Mock
    private WebhookDeliveryRepository deliveryRepository;

    @Mock
    private WebhookClient webhookClient;

    @Mock
    private MerchantSettlementPolicyRepository policyRepository;

    @InjectMocks
    private WebhookService webhookService;

    private static final Long TRANSACTION_ID = 1L;
    private static final Long MERCHANT_ACCOUNT_ID = 10L;
    private static final Long AMOUNT = 100_000L;
    private static final String WEBHOOK_URL = "https://merchant.example.com/webhook";

    // ================================================================
    // 시나리오 1: URL 미등록 스킵
    // ================================================================

    @Test
    @DisplayName("가맹점 webhookUrl 미등록 → 발송 없이 스킵 (DB 저장도 없음)")
    void sendPaymentCompleted_noWebhookUrl_skipWithNoSave() {
        when(policyRepository.findByMerchantAccountId(MERCHANT_ACCOUNT_ID))
                .thenReturn(Optional.empty());

        webhookService.sendPaymentCompleted(TRANSACTION_ID, MERCHANT_ACCOUNT_ID, AMOUNT);

        verify(webhookClient, never()).send(anyString(), anyString());
        verify(deliveryRepository, never()).save(any());
    }

    // ================================================================
    // 시나리오 2: 1차 발송 성공
    // ================================================================

    @Test
    @DisplayName("1차 발송 성공 → WebhookDelivery SUCCESS 기록, retryCount=0")
    void sendPaymentCompleted_firstAttemptSuccess_successStatusWithZeroRetry() {
        MerchantSettlementPolicy policy = createPolicyWithWebhook(MERCHANT_ACCOUNT_ID, WEBHOOK_URL);
        when(policyRepository.findByMerchantAccountId(MERCHANT_ACCOUNT_ID))
                .thenReturn(Optional.of(policy));
        when(deliveryRepository.save(any(WebhookDelivery.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(webhookClient).send(anyString(), anyString());

        webhookService.sendPaymentCompleted(TRANSACTION_ID, MERCHANT_ACCOUNT_ID, AMOUNT);

        // save 2번: PENDING 생성 시 + 최종 상태(SUCCESS) 저장 시
        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository, times(2)).save(captor.capture());

        WebhookDelivery finalDelivery = captor.getValue();
        assertThat(finalDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(finalDelivery.getRetryCount()).isEqualTo(0);   // 재시도 없이 성공
    }

    // ================================================================
    // 시나리오 3: 1차 실패 → 재시도 성공
    // ================================================================

    @Test
    @DisplayName("1차 실패 → 재시도 성공 → SUCCESS, retryCount=1")
    void sendPaymentCompleted_firstFail_retrySuccess_successWithRetryCountOne() {
        MerchantSettlementPolicy policy = createPolicyWithWebhook(MERCHANT_ACCOUNT_ID, WEBHOOK_URL);
        when(policyRepository.findByMerchantAccountId(MERCHANT_ACCOUNT_ID))
                .thenReturn(Optional.of(policy));
        when(deliveryRepository.save(any(WebhookDelivery.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 1차 실패, 2차(재시도) 성공
        doThrow(new RuntimeException("연결 타임아웃"))
                .doNothing()
                .when(webhookClient).send(anyString(), anyString());

        webhookService.sendPaymentCompleted(TRANSACTION_ID, MERCHANT_ACCOUNT_ID, AMOUNT);

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository, times(2)).save(captor.capture());

        WebhookDelivery finalDelivery = captor.getValue();
        assertThat(finalDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(finalDelivery.getRetryCount()).isEqualTo(1);   // 1회 재시도 후 성공
    }

    // ================================================================
    // 시나리오 4: 1차·재시도 모두 실패
    // ================================================================

    @Test
    @DisplayName("1차·재시도 모두 실패 → FAILED 기록, 예외 외부 전파 없음 (결제 응답 영향 없음)")
    void sendPaymentCompleted_allAttemptsFail_failedStatusWithNoExceptionPropagated() {
        MerchantSettlementPolicy policy = createPolicyWithWebhook(MERCHANT_ACCOUNT_ID, WEBHOOK_URL);
        when(policyRepository.findByMerchantAccountId(MERCHANT_ACCOUNT_ID))
                .thenReturn(Optional.of(policy));
        when(deliveryRepository.save(any(WebhookDelivery.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 1차, 재시도 모두 실패
        doThrow(new RuntimeException("서버 불응"))
                .when(webhookClient).send(anyString(), anyString());

        // [핵심 설계 검증] 예외가 외부로 전파되지 않음 → 결제 응답에 영향 없음
        webhookService.sendPaymentCompleted(TRANSACTION_ID, MERCHANT_ACCOUNT_ID, AMOUNT);

        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository, times(2)).save(captor.capture());

        WebhookDelivery finalDelivery = captor.getValue();
        assertThat(finalDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(finalDelivery.getRetryCount()).isEqualTo(1);   // 재시도 횟수 기록
        assertThat(finalDelivery.getLastFailureReason()).isNotBlank();  // 실패 사유 보관
    }

    // ================================================================
    // 헬퍼 메서드
    // ================================================================

    /** webhookUrl이 설정된 가맹점 정산 정책 생성 헬퍼 */
    private MerchantSettlementPolicy createPolicyWithWebhook(Long merchantAccountId, String webhookUrl) {
        MerchantSettlementPolicy policy = MerchantSettlementPolicy.create(
                merchantAccountId, new BigDecimal("0.0350"), true, 1);
        policy.updateWebhookUrl(webhookUrl);
        return policy;
    }
}
