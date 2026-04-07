package com.toss.cashback.domain.payment.controller;

import com.toss.cashback.domain.payment.dto.request.PaymentRequest;
import com.toss.cashback.domain.payment.dto.response.PaymentQueryResponse;
import com.toss.cashback.domain.payment.dto.response.PaymentResponse;
import com.toss.cashback.domain.payment.service.PaymentFacade;
import com.toss.cashback.domain.payment.service.PaymentQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// ======= [15번] 결제 API 컨트롤러 =======
/**
 * =====================================================================
 * [설계 의도] 결제 API 컨트롤러 - 얇은 컨트롤러(Thin Controller) 원칙
 * =====================================================================
 *
 * 역할:
 * - HTTP 요청/응답 처리만 담당 (비즈니스 로직 없음)
 * - @Valid로 입력값 검증을 진입 전에 처리
 * - 모든 비즈니스 로직은 PaymentFacade로 완전 위임
 *
 * API 명세:
 * POST /api/v1/payments
 *   Body: { "idempotencyKey": "uuid", "fromAccountId": 1, "toAccountId": 2, "amount": 10000 }
 * GET /api/v1/payments/{transactionId}
 *   Response: paymentStatus, settlementStatus, amount, failureReason, ...
 * GET /api/v1/payments?idempotencyKey={key}
 *   Response: 완료된 결제의 상태 조회 (COMPLETED 건만)
 * =====================================================================
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")      // [변경] API 버전 변경 시 "/api/v2"로 수정
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentFacade paymentFacade;
    private final PaymentQueryService paymentQueryService;

    /**
     * 결제 요청 API
     * POST /api/v1/payments
     */
    @PostMapping("/payments")
    public ResponseEntity<PaymentResponse> pay(@RequestBody @Valid PaymentRequest request) {
        // @Valid: idempotencyKey(NotBlank/UUID), fromAccountId(NotNull), toAccountId(NotNull), amount(NotNull+Positive) 검증
        // 검증 실패 시 GlobalExceptionHandler.handleValidationException() 자동 호출
        log.info("[API] POST /api/v1/payments - from={}, to={}, amount={}",
                request.getFromAccountId(), request.getToAccountId(), request.getAmount());
        return ResponseEntity.ok(paymentFacade.processPayment(request));
    }

    /**
     * 결제 상태 조회 API (transactionId 기준)
     * GET /api/v1/payments/{transactionId}
     *
     * "클라이언트가 500 받았는데 결제가 실제로 됐는지" 확인용.
     * paymentStatus + settlementStatus 함께 제공.
     */
    @GetMapping("/payments/{transactionId}")
    public ResponseEntity<PaymentQueryResponse> getPayment(@PathVariable Long transactionId) {
        log.info("[API] GET /api/v1/payments/{}", transactionId);
        return ResponseEntity.ok(paymentQueryService.queryByTransactionId(transactionId));
    }

    /**
     * 결제 상태 조회 API (idempotencyKey 기준)
     * GET /api/v1/payments?idempotencyKey={key}
     *
     * 완료된 결제의 idempotencyKey로 결과 재조회 가능.
     */
    @GetMapping("/payments")
    public ResponseEntity<PaymentQueryResponse> getPaymentByIdempotencyKey(
            @RequestParam String idempotencyKey) {
        log.info("[API] GET /api/v1/payments?idempotencyKey={}", idempotencyKey);
        return ResponseEntity.ok(paymentQueryService.queryByIdempotencyKey(idempotencyKey));
    }

    /**
     * 헬스체크 API (로드 밸런서, k8s liveness probe용)
     * GET /api/v1/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
