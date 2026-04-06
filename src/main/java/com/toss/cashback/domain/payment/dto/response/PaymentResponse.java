package com.toss.cashback.domain.payment.dto.response;

import com.toss.cashback.domain.payment.entity.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

// ======= [15번] 결제 응답 DTO =======
/**
 * 결제 응답 DTO
 */
@Getter
@Builder
public class PaymentResponse {

    private Long transactionId;         // 결제 트랜잭션 ID (영수증 번호)
    private PaymentStatus status;       // 결제 최종 상태
    private Long amount;                // 결제 금액 (원)
    private String message;             // 결과 안내 메시지

    public static PaymentResponse success(Long transactionId, Long amount) {
        return PaymentResponse.builder()
                .transactionId(transactionId)
                .status(PaymentStatus.SUCCESS)
                .amount(amount)
                .message("결제 성공! 정산은 D+1 ~ D+2 내에 가맹점으로 입금됩니다.")
                .build();
    }
}
