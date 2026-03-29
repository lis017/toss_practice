package com.toss.cashback.domain.account.repository;

import com.toss.cashback.domain.account.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// ======= [2번] 계좌 레포지토리 =======
/**
 * =====================================================================
 * [설계 의도] 계좌 리포지토리
 * =====================================================================
 *
 * 동시성 전략:
 * - 캐시백 예산은 Redisson 분산 락으로 보호 (CashbackService)
 * - 계좌 이체는 @Transactional + Dirty Checking으로 처리 (PaymentService)
 *
 * 추후 리팩터:
 * - 계좌 이체 동시성이 문제될 경우 Redisson 계좌별 분산 락 도입 가능
 *   (lockKey = "lock:account:" + accountId)
 * =====================================================================
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 계좌번호로 계좌 조회 */
    Optional<Account> findByAccountNumber(String accountNumber);
}
