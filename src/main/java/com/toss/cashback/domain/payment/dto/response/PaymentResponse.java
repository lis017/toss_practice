package com.toss.cashback.domain.payment.dto.response;

import com.toss.cashback.domain.payment.entity.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

/**
 * 결제 응답 DTO
 */
@Getter
@Builder
public class PaymentResponse {

    private Long transactionId;         // 결제 트랜잭션 ID (영수증 번호)
    private PaymentStatus status;       // 결제 최종 상태
    private Long amount;                // 결제 금액 (원)
    private Long cashbackAmount;        // 적립 캐시백 포인트 (0이면 예산 소진)
    private String message;             // 결과 안내 메시지

    public static PaymentResponse success(Long transactionId, Long amount, Long cashbackAmount) {
        return PaymentResponse.builder()
                .transactionId(transactionId)
                .status(PaymentStatus.SUCCESS)
                .amount(amount)
                .cashbackAmount(cashbackAmount)
                .message(cashbackAmount > 0
                        ? String.format("결제 성공! %,d포인트 캐시백이 적립되었습니다.", cashbackAmount)
                        : "결제 성공! (캐시백 이벤트 예산이 소진되었습니다)")
                .build();
    }
}
