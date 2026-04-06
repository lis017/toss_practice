package com.toss.cashback.domain.account.repository;

import com.toss.cashback.domain.account.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// ======= [4번] 계좌 레포지토리 =======
/**
 * =====================================================================
 * [설계 의도] 계좌 리포지토리
 * =====================================================================
 *
 * 동시성 전략:
 * - 계좌 이체: Redisson MultiLock + Dirty Checking으로 처리 (PaymentService)
 * - 정산 이체: Redisson MultiLock + Dirty Checking으로 처리 (SettlementService)
 * =====================================================================
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 계좌번호로 계좌 조회 */
    Optional<Account> findByAccountNumber(String accountNumber);
}
