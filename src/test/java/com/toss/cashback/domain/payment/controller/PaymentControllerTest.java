package com.toss.cashback.domain.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toss.cashback.domain.payment.dto.request.PaymentRequest;
import com.toss.cashback.domain.payment.dto.response.PaymentResponse;
import com.toss.cashback.domain.payment.service.PaymentFacade;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * =====================================================================
 * PaymentController API 통합 테스트 (MockMvc)
 * =====================================================================
 *
 * 테스트 전략:
 * - @WebMvcTest: Web 계층만 로드 (빠른 실행, PaymentFacade는 MockBean)
 * - MockMvc로 실제 HTTP 요청/응답 검증
 *
 * 커버리지:
 * - 정상 결제 / 예산 소진 시 응답 검증
 * - 멱등성: 동일 key 재요청 시 캐시된 응답 반환 확인
 * - 입력값 유효성 실패 (400) 확인 (idempotencyKey 누락 포함)
 * - 잔액 부족 / 외부 API 타임아웃 에러 코드 확인
 * =====================================================================
 */
@WebMvcTest(PaymentController.class)
@DisplayName("PaymentController API 통합 테스트")
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;                    // HTTP 요청/응답 시뮬레이션

    @Autowired
    private ObjectMapper objectMapper;          // JSON 직렬화/역직렬화

    @MockBean
    private PaymentFacade paymentFacade;        // PaymentFacade 모의 객체

    // ================================================================
    // 정상 케이스
    // ================================================================

    @Test
    @DisplayName("정상 결제 + 캐시백 지급 - 200 OK, 응답 필드 확인")
    void pay_success_withCashback() throws Exception {
        // given
        PaymentResponse mockResponse = PaymentResponse.success(1L, 10_000L, 1_000L);
        when(paymentFacade.processPayment(any(PaymentRequest.class))).thenReturn(mockResponse);

        // when & then
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("uuid-1111", 1L, 2L, 10_000L)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(1L))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(10_000L))
                .andExpect(jsonPath("$.cashbackAmount").value(1_000L));
    }

    @Test
    @DisplayName("정상 결제 + 예산 소진 - 200 OK, cashbackAmount=0 확인")
    void pay_success_budgetExhausted() throws Exception {
        // given: 예산 소진 (cashback=0)
        PaymentResponse mockResponse = PaymentResponse.success(2L, 10_000L, 0L);
        when(paymentFacade.processPayment(any())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("uuid-2222", 1L, 2L, 10_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashbackAmount").value(0L))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    // ================================================================
    // 멱등성 케이스
    // ================================================================

    @Test
    @DisplayName("멱등성 - 동일 idempotencyKey 재요청 시 Facade가 캐시된 응답 반환")
    void pay_idempotency_returnsCachedResponse() throws Exception {
        // given: 최초 요청과 동일한 응답 (Facade 내부에서 멱등성 레코드 조회 후 반환)
        PaymentResponse cachedResponse = PaymentResponse.success(1L, 10_000L, 1_000L);
        when(paymentFacade.processPayment(any())).thenReturn(cachedResponse);

        String sameRequest = createRequestJson("same-uuid-9999", 1L, 2L, 10_000L);

        // when: 동일 요청 2번 전송 (실제 멱등성 체크는 Facade Mock이므로 응답이 동일한지 확인)
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sameRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(1L));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sameRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(1L)); // 동일 응답 확인
    }

    // ================================================================
    // 유효성 검증 실패 케이스 (Bean Validation)
    // ================================================================

    @Test
    @DisplayName("idempotencyKey 누락 - 400 Bad Request")
    void pay_fail_missingIdempotencyKey() throws Exception {
        String missingKey = "{\"fromAccountId\": 1, \"toAccountId\": 2, \"amount\": 10000}";

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingKey))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("fromAccountId 누락 - 400 Bad Request")
    void pay_fail_missingFromAccountId() throws Exception {
        String missingFrom = "{\"idempotencyKey\": \"uuid-test\", \"toAccountId\": 2, \"amount\": 10000}";

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingFrom))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("amount = 0 - 400 Bad Request (@Positive 위반)")
    void pay_fail_zeroAmount() throws Exception {
        String zeroAmount = "{\"idempotencyKey\": \"uuid-test\", \"fromAccountId\": 1, \"toAccountId\": 2, \"amount\": 0}";

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(zeroAmount))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("amount 음수 - 400 Bad Request (@Positive 위반)")
    void pay_fail_negativeAmount() throws Exception {
        String negativeAmount = "{\"idempotencyKey\": \"uuid-test\", \"fromAccountId\": 1, \"toAccountId\": 2, \"amount\": -1000}";

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(negativeAmount))
                .andExpect(status().isBadRequest());
    }

    // ================================================================
    // 비즈니스 예외 케이스
    // ================================================================

    @Test
    @DisplayName("잔액 부족 - 400 Bad Request, 에러 코드 A002 확인")
    void pay_fail_insufficientBalance() throws Exception {
        when(paymentFacade.processPayment(any()))
                .thenThrow(new CustomException(ErrorCode.INSUFFICIENT_BALANCE));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("uuid-3333", 1L, 2L, 999_999_999L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A002"))
                .andExpect(jsonPath("$.message").value("잔액이 부족합니다"));
    }

    @Test
    @DisplayName("외부 은행 타임아웃 - 504 Gateway Timeout, 에러 코드 E001 확인")
    void pay_fail_externalBankTimeout() throws Exception {
        when(paymentFacade.processPayment(any()))
                .thenThrow(new CustomException(ErrorCode.EXTERNAL_BANK_TIMEOUT));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("uuid-4444", 1L, 2L, 10_000L)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("E001"));
    }

    @Test
    @DisplayName("동일 계좌 이체 - 400 Bad Request, 에러 코드 A003 확인")
    void pay_fail_sameAccount() throws Exception {
        when(paymentFacade.processPayment(any()))
                .thenThrow(new CustomException(ErrorCode.SAME_ACCOUNT_TRANSFER));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("uuid-5555", 1L, 1L, 10_000L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A003"));
    }

    // ================================================================
    // 헬퍼 메서드
    // ================================================================

    /** idempotencyKey를 포함한 결제 요청 JSON 생성 */
    private String createRequestJson(String idempotencyKey, Long fromId, Long toId, Long amount) {
        return String.format(
                "{\"idempotencyKey\": \"%s\", \"fromAccountId\": %d, \"toAccountId\": %d, \"amount\": %d}",
                idempotencyKey, fromId, toId, amount);
    }
}
