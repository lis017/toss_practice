package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.account.entity.Account;
import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.cashback.entity.CashbackBudget;
import com.toss.cashback.domain.cashback.repository.CashbackBudgetRepository;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import com.toss.cashback.infrastructure.redis.RedissonLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// ======= [8번] 캐시백 지급 서비스 (동시성 핵심) =======
/**
 * 캐시백 지급 처리. 동시성이 핵심입니다.
 *
 * Redisson 분산 락으로 직렬화하되, 락 안에서 TransactionTemplate으로 커밋까지 완료합니다.
 * 락 해제 전 커밋을 보장해야 다음 스레드가 최신 예산을 읽을 수 있습니다.
 * (@Transactional을 메서드에 붙이면 락 해제 후 커밋되어 race condition이 생깁니다)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashbackService {

    private final AccountRepository accountRepository;
    private final CashbackBudgetRepository cashbackBudgetRepository;
    private final RedissonLockService redissonLockService;
    private final TransactionTemplate transactionTemplate;          // 락 내부에서 트랜잭션 경계 직접 제어

    private static final String CASHBACK_LOCK_KEY = "lock:cashback:budget";
    private static final long LOCK_WAIT_TIME = 5L;
    private static final long LOCK_LEASE_TIME = 3L;
    private static final double CASHBACK_RATE = 0.1;

    /** @return 지급된 캐시백 금액. 예산 소진 시 0 반환 */
    public long grantCashback(Long fromAccountId, Long paymentAmount) {
        long cashbackAmount = (long) (paymentAmount * CASHBACK_RATE); // 캐시백 10% 계산

        return redissonLockService.executeWithLock(
                CASHBACK_LOCK_KEY,
                LOCK_WAIT_TIME,
                LOCK_LEASE_TIME,
                () -> transactionTemplate.execute(status ->
                        processGrantInternal(fromAccountId, cashbackAmount))
        );
    }

    private long processGrantInternal(Long fromAccountId, Long cashbackAmount) {
        CashbackBudget budget = cashbackBudgetRepository.findTop()
                .orElseThrow(() -> new CustomException(ErrorCode.CASHBACK_BUDGET_NOT_FOUND));

        if (!budget.canGrant(cashbackAmount)) {
            log.info("[캐시백] 예산 소진 - accountId={}, 잔여예산={}", fromAccountId, budget.getRemainingBudget());
            return 0L;
        }

        Account account = accountRepository.findById(fromAccountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        account.addPoints(cashbackAmount);  // Dirty Checking → 커밋 시 자동 UPDATE
        budget.use(cashbackAmount);

        log.info("[캐시백] 적립 완료 - accountId={}, cashback={}, 잔여예산={}",
                fromAccountId, cashbackAmount, budget.getRemainingBudget());
        return cashbackAmount;
    }
}
