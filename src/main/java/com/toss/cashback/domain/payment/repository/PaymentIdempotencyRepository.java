package com.toss.cashback.domain.payment.repository;

import com.toss.cashback.domain.payment.entity.PaymentIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

// ======= [5번] 멱등성 레코드 레포지토리 =======
/**
 * =====================================================================
 * [설계 의도] 멱등성 레코드 리포지토리
 * =====================================================================
 *
 * findByIdempotencyKeyAndExpiresAtAfter:
 * - 만료되지 않은 레코드만 조회 (expiresAt > 현재시각)
 * - 만료된 레코드는 없는 것처럼 처리 → 새 결제로 진행
 *
 * deleteExpiredRecords:
 * - @Modifying: DML(DELETE) 쿼리 실행 시 필수
 * - bulk delete로 만료 레코드 한 번에 삭제 (N+1 방지)
 * - IdempotencyCleanupScheduler가 매일 새벽 3시 호출
 * =====================================================================
 */
public interface PaymentIdempotencyRepository extends JpaRepository<PaymentIdempotency, Long> {

    /**
     * 만료되지 않은 멱등성 레코드 조회
     * expiresAt이 현재 시각 이후인 레코드만 반환 (만료된 키는 새 결제로 처리)
     */
    Optional<PaymentIdempotency> findByIdempotencyKeyAndExpiresAtAfter(
            String idempotencyKey, LocalDateTime now);

    /**
     * 만료된 레코드 일괄 삭제 (스케줄러에서 호출)
     * DELETE FROM payment_idempotency WHERE expires_at < :now
     * @return 삭제된 행 수
     */
    @Modifying
    @Query("DELETE FROM PaymentIdempotency p WHERE p.expiresAt < :now")
    int deleteExpiredRecords(@Param("now") LocalDateTime now);
}
