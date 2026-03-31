package com.toss.cashback.domain.payment.scheduler;

import com.toss.cashback.domain.payment.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// ======= [신규] 정산 스케줄러 =======
/**
 * PG 정산 스케줄러: 매일 새벽 2시에 PENDING 정산 건을 일괄 처리합니다.
 *
 * 실제 토스페이먼츠 정산 구조:
 * - D+1 정산: 오늘 결제 → 내일 가맹점 입금
 * - D+2 정산: 일부 결제 수단(카드 등)은 D+2 주기 적용
 *
 * 이 구현에서는 단순화하여 매일 새벽 2시에 전체 PENDING 건을 일괄 정산합니다.
 * 운영 환경에서는 정산 주기(D+1/D+2), 가맹점별 정산 조건 등을 추가로 구현합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementScheduler {

    private final SettlementService settlementService;

    /**
     * 매일 새벽 2시 정산 실행 (cron: 초 분 시 일 월 요일)
     * 실패 건은 SettlementService에서 PENDING 유지 → 다음 날 자동 재시도
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void runDailySettlement() {
        log.info("[정산 스케줄러] 일일 정산 시작 - {}", java.time.LocalDateTime.now());
        try {
            settlementService.processAllPendingSettlements();
        } catch (Exception e) {
            log.error("[정산 스케줄러] 정산 처리 중 예상치 못한 오류 - {}", e.getMessage(), e);
        }
        log.info("[정산 스케줄러] 일일 정산 종료");
    }
}
