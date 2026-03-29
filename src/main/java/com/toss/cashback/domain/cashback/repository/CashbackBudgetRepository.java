package com.toss.cashback.domain.cashback.repository;

import com.toss.cashback.domain.cashback.entity.CashbackBudget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

// ======= [2번] 캐시백 예산 레포지토리 =======
/**
 * =====================================================================
 * [설계 의도] 캐시백 예산 리포지토리
 * =====================================================================
 *
 * 동시성 전략:
 * - findTop() 호출 전 RedissonLockService가 분산 락을 선점
 * - 락 내부에서만 예산 조회 + 수정 → Lost Update 방지
 *
 * 추후 리팩터:
 * - Redis INCR/DECR로 예산 관리 시 DB 조회 불필요 → 이 Repository 단순화 가능
 * =====================================================================
 */
public interface CashbackBudgetRepository extends JpaRepository<CashbackBudget, Long> {

    /** 예산 레코드 조회 (항상 1개 행만 존재, Redisson 락 내에서 호출) */
    @Query("SELECT cb FROM CashbackBudget cb ORDER BY cb.id ASC")
    Optional<CashbackBudget> findTop();
}
