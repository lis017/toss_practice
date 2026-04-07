package com.toss.cashback.domain.payment.controller;

import com.toss.cashback.domain.payment.recovery.entity.RecoveryAttempt;
import com.toss.cashback.domain.payment.recovery.entity.RecoveryTriggerType;
import com.toss.cashback.domain.payment.recovery.service.RecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// ======= [15번] 운영 관리 API 컨트롤러 =======
/**
 * =====================================================================
 * [설계 의도] 운영자용 수동 복구 API - "장애 복구를 제품 기능으로 설계"
 * =====================================================================
 *
 * 실제 운영 시나리오:
 * 1. 모니터링 알림: POST_PROCESS_FAILED 건 발생 감지
 * 2. 운영자가 GET /payments/{txId}로 현황 확인
 * 3. 자동 복구 이력 확인: GET /admin/payments/{txId}/recovery-history
 * 4. 수동 복구: POST /admin/payments/{txId}/recover
 *
 * 보안 주의:
 * - 실제 운영에서는 Spring Security로 ADMIN 역할만 접근 허용 필요
 * - 현재는 과제 환경이므로 인증 없이 구현
 * =====================================================================
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final RecoveryService recoveryService;

    /**
     * POST_PROCESS_FAILED 건 수동 복구
     * POST /api/v1/admin/payments/{transactionId}/recover
     */
    @PostMapping("/payments/{transactionId}/recover")
    public ResponseEntity<Map<String, Object>> manualRecover(
            @PathVariable Long transactionId) {
        log.info("[관리자 API] 수동 복구 요청 - txId={}", transactionId);
        recoveryService.recover(transactionId, RecoveryTriggerType.MANUAL);
        return ResponseEntity.ok(Map.of(
                "transactionId", transactionId,
                "message", "복구 요청이 처리됐습니다. GET /api/v1/payments/" + transactionId + " 로 결과를 확인하세요"
        ));
    }

    /**
     * 특정 결제 건의 복구 시도 이력 조회
     * GET /api/v1/admin/payments/{transactionId}/recovery-history
     */
    @GetMapping("/payments/{transactionId}/recovery-history")
    public ResponseEntity<List<RecoveryAttempt>> getRecoveryHistory(
            @PathVariable Long transactionId) {
        return ResponseEntity.ok(recoveryService.getRecoveryHistory(transactionId));
    }

    /**
     * 자동 복구 실패 전체 현황 조회 (운영 대시보드용)
     * GET /api/v1/admin/recoveries/failed
     */
    @GetMapping("/recoveries/failed")
    public ResponseEntity<List<RecoveryAttempt>> getFailedRecoveries() {
        return ResponseEntity.ok(recoveryService.getFailedRecoveries());
    }
}
