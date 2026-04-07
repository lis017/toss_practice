package com.toss.cashback.domain.payment.dto.response;

import com.toss.cashback.domain.payment.entity.PaymentStatus;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.settlement.entity.SettlementRecord;
import com.toss.cashback.domain.settlement.entity.SettlementStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

// ======= [15번] 결제 조회 응답 DTO =======
/**
 * 결제 상태 조회 응답 DTO
 *
 * "클라이언트가 500 받았는데 결제가 실제로 됐는지 어떻게 확인하나요?" 질문에 대한 답.
 * paymentStatus + settlementStatus를 함께 제공 → 어느 단계까지 처리됐는지 정확히 파악 가능.
 */
@Getter
@Builder
public class PaymentQueryResponse {

    private Long transactionId;             // 결제 트랜잭션 ID

    private PaymentStatus paymentStatus;    // 결제 처리 상태 (PENDING/PENDING_SETTLEMENT/SUCCESS/FAILED/POST_PROCESS_FAILED)

    private SettlementStatus settlementStatus;  // 정산 상태 (null = 정산 레코드 미생성)

    private Long amount;                    // 결제 금액

    private Long fromAccountId;             // 구매자 계좌 ID

    private Long toAccountId;               // 가맹점 계좌 ID

    private String failureReason;           // 결제 실패/불일치 사유 (정상 완료 시 null)

    private String settlementFailureReason; // 정산 실패 사유 (정산 중 오류 시)

    private LocalDate expectedSettlementDate; // 정산 예정일 (null = 정산 레코드 미생성)

    private LocalDateTime createdAt;        // 결제 생성 시각

    private LocalDateTime updatedAt;        // 마지막 상태 변경 시각

    /**
     * PaymentTransaction + SettlementRecord로 응답 조합
     * settlementRecord가 null이면 정산 관련 필드는 null 처리
     */
    public static PaymentQueryResponse of(PaymentTransaction tx, SettlementRecord settlement) {
        return PaymentQueryResponse.builder()
                .transactionId(tx.getId())
                .paymentStatus(tx.getStatus())
                .settlementStatus(settlement != null ? settlement.getStatus() : null)
                .amount(tx.getAmount())
                .fromAccountId(tx.getFromAccountId())
                .toAccountId(tx.getToAccountId())
                .failureReason(tx.getFailureReason())
                .settlementFailureReason(settlement != null ? settlement.getFailureReason() : null)
                .expectedSettlementDate(settlement != null ? settlement.getExpectedSettlementDate() : null)
                .createdAt(tx.getCreatedAt())
                .updatedAt(tx.getUpdatedAt())
                .build();
    }
}
