package com.toss.cashback.domain.payment.recovery.entity;

// ======= [15번] 복구 트리거 타입 =======
/** 복구 실행 주체: AUTO(스케줄러 자동) / MANUAL(운영자 수동) */
public enum RecoveryTriggerType {
    AUTO,    // PostProcessRecoveryScheduler 자동 실행
    MANUAL   // POST /api/v1/admin/payments/{id}/recover 운영자 수동 호출
}
