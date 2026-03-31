package com.toss.cashback.domain.payment.scheduler;

import com.toss.cashback.domain.payment.repository.PaymentIdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// ======= [10번] 멱등성 레코드 정리 스케줄러 =======
/**
 * =====================================================================
 * [설계 의도] 멱등성 레코드 만료 정리 스케줄러
 * =====================================================================
 *
 * 문제:
 * - payment_idempotency 테이블에 TTL 없으면 → 시간이 지나도 레코드 무한 누적
 * - 결제량 많은 서비스에서 수개월 운영 시 수천만 건 쌓임 → 조회 성능 저하
 *
 * 해결:
 * - expiresAt 컬럼 기준으로 만료된 레코드 일괄 삭제 (배치 DELETE)
 * - 매일 새벽 3시 실행 (트래픽이 가장 적은 시간대)
 *
 * @Scheduled cron 형식: 초 분 시 일 월 요일
 * "0 0 3 * * *" → 매일 03:00:00에 실행
 *
 * [주의] @EnableScheduling이 CashbackSystemApplication에 있어야 동작합니다
 *
 * 다중 서버 주의:
 * - 현재 @Scheduled만 사용 → 서버 N대에서 동시에 실행됨 (중복 DELETE, 데이터 오염은 없으나 불필요한 DB 부하)
 * - 운영 환경 다중 서버 적용 시 ShedLock으로 1대만 실행되도록 제어 필요
 *   (@SchedulerLock 어노테이션 + DB/Redis 락 테이블로 단일 실행 보장)
 * - 보조 인프라이기에 트레이드오프를 고려한 설계.
 *
 * 추후 리팩터:
 * - 삭제 건수가 많을 경우 LIMIT를 걸어 분할 삭제 (DB 부하 분산)
 *   ex: WHERE expires_at < :now LIMIT 10000
 * - Slack/PagerDuty 알림으로 이상 대량 삭제 감지 (ex: 100만 건 이상 시 알림)
 * - Spring Batch로 전환 시 재처리/모니터링 강화 가능
 * =====================================================================
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyCleanupScheduler {

    private final PaymentIdempotencyRepository idempotencyRepository;

    /**
     * 매일 새벽 3시 만료된 멱등성 레코드 일괄 삭제
     * [변경] cron 표현식 수정으로 실행 주기 조정 가능
     *   - "0 0 3 * * *"   → 매일 새벽 3시
     *   - "0 0 3 * * SUN" → 매주 일요일 새벽 3시
     *   - "0 0 3 1 * *"   → 매월 1일 새벽 3시
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional  // DELETE 쿼리는 트랜잭션 필수 (@Modifying 사용하므로)
    public void deleteExpiredIdempotencyRecords() {
        LocalDateTime now = LocalDateTime.now();
        log.info("[멱등성 정리] 만료 레코드 삭제 시작 - 기준시각={}", now);

        int deletedCount = idempotencyRepository.deleteExpiredRecords(now);

        if (deletedCount > 0) {
            log.info("[멱등성 정리] 완료 - 삭제 건수={}", deletedCount);
        } else {
            log.debug("[멱등성 정리] 만료 레코드 없음");
        }
    }
}
