package com.toss.cashback.domain.webhook.repository;

import com.toss.cashback.domain.webhook.entity.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// ======= [7번] 웹훅 발송 이력 레포지토리 =======
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    /** 특정 결제 트랜잭션의 웹훅 발송 이력 조회 */
    List<WebhookDelivery> findByPaymentTransactionId(Long paymentTransactionId);
}
