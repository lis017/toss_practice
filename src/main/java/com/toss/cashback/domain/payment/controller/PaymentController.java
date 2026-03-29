package com.toss.cashback.domain.payment.controller;

import com.toss.cashback.domain.payment.dto.request.PaymentRequest;
import com.toss.cashback.domain.payment.dto.response.PaymentResponse;
import com.toss.cashback.domain.payment.service.PaymentFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// ======= [11번] 결제 API 컨트롤러 =======
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
 *   Body: { "fromAccountId": 1, "toAccountId": 2, "amount": 10000 }
 *   Response: { "transactionId": 1, "status": "SUCCESS", "cashbackAmount": 1000, ... }
 * =====================================================================
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")      // [변경] API 버전 변경 시 "/api/v2"로 수정
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentFacade paymentFacade;  // Facade 주입 (Service 직접 주입 지양)

    /**
     * 결제 요청 API
     * POST /api/v1/payments
     */
    @PostMapping("/payments")
    public ResponseEntity<PaymentResponse> pay(@RequestBody @Valid PaymentRequest request) {
        // @Valid: fromAccountId(NotNull), toAccountId(NotNull), amount(NotNull+Positive) 검증
        // 검증 실패 시 GlobalExceptionHandler.handleValidationException() 자동 호출
        log.info("[API] POST /api/v1/payments - from={}, to={}, amount={}",
                request.getFromAccountId(), request.getToAccountId(), request.getAmount());

        return ResponseEntity.ok(paymentFacade.processPayment(request));
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
