package com.toss.cashback.domain.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

// ======= [신규] 웹훅 발송 이력 엔티티 =======
/**
 * =====================================================================
 * [설계 의도] 웹훅 발송 이력 - 가맹점 수신 상태 추적
 * =====================================================================
 *
 * 저장 목적:
 * - 발송 성공/실패 추적 (감사 로그)
 * - 재시도 횟수 관리 (현재 최대 1회)
 * - 실패 사유 보관 (운영팀 디버깅)
 *
 * 추후 확장 포인트:
 * - MAX_RETRY 상수로 재시도 횟수 설정화
 * - ScheduledJob으로 FAILED 건 자동 재발송
 * =====================================================================
 */
@Entity
@Table(
    name = "webhook_deliveries",
    indexes = {
        @Index(name = "idx_webhook_tx", columnList = "paymentTransactionId"),
        @Index(name = "idx_webhook_status", columnList = "status")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long paymentTransactionId;          // 연결된 결제 트랜잭션 ID

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WebhookEventType eventType;         // PAYMENT_COMPLETED

    @Column(nullable = false, length = 500)
    private String webhookUrl;                  // 가맹점 웹훅 수신 URL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookDeliveryStatus status;       // 발송 상태

    @Column(nullable = false)
    private int retryCount;                     // 재시도 횟수 (0=첫 시도에 성공, 1=재시도 후 처리)

    @Column(length = 500)
    private String lastFailureReason;           // 마지막 실패 사유

    private LocalDateTime lastAttemptAt;        // 마지막 시도 시각

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** 발송 대기 상태로 신규 생성 */
    public static WebhookDelivery createPending(Long transactionId,
                                                 String webhookUrl,
                                                 WebhookEventType eventType) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.paymentTransactionId = transactionId;
        delivery.webhookUrl = webhookUrl;
        delivery.eventType = eventType;
        delivery.status = WebhookDeliveryStatus.PENDING;
        delivery.retryCount = 0;
        return delivery;
    }

    /** 발송 성공 처리 */
    public void markSuccess() {
        this.status = WebhookDeliveryStatus.SUCCESS;
        this.lastAttemptAt = LocalDateTime.now();
    }

    /** 재시도 카운트 증가 + 실패 사유 기록 (status는 아직 변경하지 않음) */
    public void incrementRetryAndFail(String reason) {
        this.retryCount++;
        this.lastFailureReason = reason;
        this.lastAttemptAt = LocalDateTime.now();
    }

    /** 최종 실패 처리 (재시도 소진 후 호출) */
    public void markFailed(String reason) {
        this.status = WebhookDeliveryStatus.FAILED;
        this.lastFailureReason = reason;
        this.lastAttemptAt = LocalDateTime.now();
    }
}
