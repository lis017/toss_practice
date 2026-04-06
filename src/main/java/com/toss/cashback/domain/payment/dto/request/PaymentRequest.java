package com.toss.cashback.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

// ======= [15번] 결제 요청 DTO =======
/**
 * 결제 요청 DTO
 *
 * Bean Validation 사용 이유:
 * - Controller 진입 전에 유효성 검사 → 잘못된 요청이 Service/DB까지 도달하지 않음
 *
 * idempotencyKey 사용법:
 * - 클라이언트가 UUID를 생성해서 매 결제 요청마다 포함 (ex: "550e8400-e29b-41d4-a716-446655440000")
 * - 네트워크 오류로 재시도 시 동일 key 재사용 → 서버가 첫 번째 응답을 그대로 반환
 * - key가 다르면 새 결제로 처리
 */
@Getter
@NoArgsConstructor
public class PaymentRequest {

    @NotBlank(message = "멱등성 키는 필수입니다")
    @Size(max = 100, message = "멱등성 키 길이는 100자를 초과할 수 없습니다")
    @Pattern(
        regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        message = "멱등성 키는 UUID 형식이어야 합니다"
    )
    private String idempotencyKey;      // 중복 요청 방지 키 (클라이언트가 UUID로 생성)

    @NotNull(message = "송금 계좌 ID는 필수입니다")
    private Long fromAccountId;         // 송금자 계좌 ID

    @NotNull(message = "수신 계좌 ID는 필수입니다")
    private Long toAccountId;           // 수신자(상점) 계좌 ID

    @NotNull(message = "결제 금액은 필수입니다")
    @Positive(message = "결제 금액은 0보다 커야 합니다")
    private Long amount;                // 결제 금액 (원, 양수만 허용)
}
