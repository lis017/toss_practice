package com.toss.cashback.domain.payment.recovery.entity;

// ======= [15번] 복구 결과 =======
/** 복구 시도 결과: SUCCESS / FAILED */
public enum RecoveryResult {
    SUCCESS,  // 복구 완료 (PENDING_SETTLEMENT로 전환)
    FAILED    // 복구 실패 (운영팀 수동 처리 필요)
}
