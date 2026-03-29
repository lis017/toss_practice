package com.toss.cashback.domain.cashback.entity;

import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// ======= [2번] 캐시백 예산 엔티티 =======
/**
 * =====================================================================
 * [설계 의도] 캐시백 이벤트 예산 엔티티 (DB 싱글턴 레코드)
 * =====================================================================
 *
 * 동시성 보호 설계:
 * - Redisson 분산 락 (RedissonLockService) → 분산 환경 직렬화 (단일 보호)
 *
 * 추후 리팩터:
 * - usedBudget을 Redis INCR/DECR로 관리 시 DB 부하 없이 초고속 처리
 *   (단, Redis 장애 시 DB와 불일치 → Redis AOF + 주기적 DB 동기화 전략 필요)
 * - 이벤트 여러 개 시 eventId 컬럼 추가 → 다수 예산 동시 관리 가능
 * =====================================================================
 */
@Entity
@Table(name = "cashback_budget")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashbackBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                        // 예산 레코드 ID (DB에 항상 1개 행만 존재)

    @Column(nullable = false)
    private Long totalBudget;               // 이벤트 총 예산 (1억 = 100,000,000원)

    @Column(nullable = false)
    private Long usedBudget;                // 현재까지 지급된 캐시백 총액

    public CashbackBudget(Long totalBudget) {
        this.totalBudget = totalBudget;
        this.usedBudget = 0L;
    }

    /**
     * [비즈니스 로직] 캐시백 예산 사용
     * [주의] 반드시 RedissonLockService 락 내에서 호출해야 합니다
     */
    public void use(Long amount) {
        if (this.usedBudget + amount > this.totalBudget) {
            throw new CustomException(ErrorCode.CASHBACK_BUDGET_EXCEEDED);
        }
        this.usedBudget += amount;          // Dirty Checking → 자동 UPDATE
    }

    /** 캐시백 지급 가능 여부 (canGrant + use는 반드시 락 내에서 원자적 실행) */
    public boolean canGrant(Long amount) {
        return this.usedBudget + amount <= this.totalBudget;
    }

    /** 잔여 예산 조회 (모니터링/로그용) */
    public Long getRemainingBudget() {
        return this.totalBudget - this.usedBudget;
    }
}
