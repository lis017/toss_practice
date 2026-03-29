package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.account.entity.Account;
import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.cashback.entity.CashbackBudget;
import com.toss.cashback.domain.cashback.repository.CashbackBudgetRepository;
import com.toss.cashback.infrastructure.redis.RedissonLockService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// ======= [9번] 캐시백 동시성 통합 테스트 =======
/**
 * =====================================================================
 * 캐시백 동시성 통합 테스트
 * =====================================================================
 *
 * 테스트 목적:
 * - 동시에 N명이 캐시백을 요청할 때 예산이 절대 초과되지 않음을 검증
 * - RedissonLockService가 실제로 직렬화하는지 확인
 *
 * 테스트 환경:
 * - @SpringBootTest: 실제 Spring Context (JPA, 서비스 등)
 * - @ActiveProfiles("test"): H2 인메모리 DB
 * - @MockBean RedissonLockService: 실제 Redis 없이 로컬 ReentrantLock으로 대체
 *   → CI/CD 환경 친화적 (Redis 인프라 불필요)
 *
 * [추후 개선] Testcontainers 적용:
 * @Container static GenericContainer redis = new GenericContainer("redis:7.2")
 * → 실제 Redis 분산 락 동작 테스트 (더 현실적인 시나리오)
 * =====================================================================
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("캐시백 동시성 통합 테스트")
class CashbackConcurrencyTest {

    @Autowired
    private CashbackService cashbackService;

    @Autowired
    private CashbackBudgetRepository cashbackBudgetRepository;

    @Autowired
    private AccountRepository accountRepository;

    @MockBean
    private RedissonClient redissonClient;                      // RedissonConfig의 실제 Redis 연결 차단

    @MockBean
    private RedissonLockService redissonLockService;            // 실제 Redis 대신 로컬 락으로 대체

    private final ReentrantLock localLock = new ReentrantLock(); // 분산 락 대체 로컬 락

    @AfterEach
    void cleanUpTestData() {
        // @Transactional 없이 실행되므로 각 테스트 후 직접 정리
        accountRepository.deleteAll();
        cashbackBudgetRepository.deleteAll();
    }

    @BeforeEach
    void setUpLockMock() throws Exception {
        // RedissonLockService.executeWithLock()을 로컬 ReentrantLock으로 모의
        doAnswer((InvocationOnMock invocation) -> {
            String lockKey = invocation.getArgument(0);
            long waitTime = invocation.getArgument(1);
            long leaseTime = invocation.getArgument(2);
            Callable<?> task = invocation.getArgument(3);

            boolean acquired = localLock.tryLock(waitTime, TimeUnit.SECONDS); // 로컬 락 시도
            if (!acquired) {
                throw new com.toss.cashback.global.error.CustomException(
                        com.toss.cashback.global.error.ErrorCode.LOCK_ACQUISITION_FAILED);
            }
            try {
                return task.call();     // 실제 비즈니스 로직 실행
            } finally {
                if (localLock.isHeldByCurrentThread()) localLock.unlock(); // 로컬 락 해제
            }
        }).when(redissonLockService).executeWithLock(anyString(), anyLong(), anyLong(), any(Callable.class));
    }

    @Test
    @DisplayName("동시 100건 요청 - 예산 1,000원에서 초과 지급 없음 검증")
    void concurrentCashback_budgetNotExceeded() throws InterruptedException {
        // 예산: 1,000원 / 요청: 100건 / 결제금액: 100원 → 캐시백 10원
        // 기대: 100건 모두 지급 가능 (총 1,000원), 초과 없음
        long testBudget = 1_000L;
        int totalRequests = 100;
        long paymentAmount = 100L;

        cashbackBudgetRepository.deleteAll();
        cashbackBudgetRepository.save(new CashbackBudget(testBudget));
        List<Long> accountIds = createTestAccounts(totalRequests);

        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch startLatch = new CountDownLatch(1);              // 모든 스레드 동시 시작 신호
        CountDownLatch endLatch = new CountDownLatch(totalRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Long> grantedAmounts = new CopyOnWriteArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            final Long accountId = accountIds.get(i);
            executor.submit(() -> {
                try {
                    startLatch.await();                                 // 동시 시작 대기
                    long granted = cashbackService.grantCashback(accountId, paymentAmount);
                    if (granted > 0) {
                        successCount.incrementAndGet();
                        grantedAmounts.add(granted);
                    }
                } catch (Exception e) {
                    log.warn("[테스트] 예외 - accountId={}, msg={}", accountId, e.getMessage());
                    log.warn("[test] exception - accountId={}, msg={}", accountId, e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();                                         // 모든 스레드 동시 시작
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();                                 // 30초 내 완료 확인

        long totalGranted = grantedAmounts.stream().mapToLong(Long::longValue).sum();
        log.info("[결과] 총 요청={}, 성공={}, 총 지급={}원, 예산={}원",
                totalRequests, successCount.get(), totalGranted, testBudget);
        log.info("[result] totalRequests={}, success={}, totalGranted={} KRW, budget={} KRW",
                totalRequests, successCount.get(), totalGranted, testBudget);

        // [핵심 검증] 총 지급 금액이 예산을 절대 초과하지 않아야 함
        assertThat(totalGranted).isLessThanOrEqualTo(testBudget);

        // DB 실제 값 확인
        CashbackBudget finalBudget = cashbackBudgetRepository.findTop().orElseThrow();
        assertThat(finalBudget.getUsedBudget()).isLessThanOrEqualTo(testBudget);
        assertThat(finalBudget.getUsedBudget()).isEqualTo(totalGranted);    // DB와 실제 합계 일치
    }

    @Test
    @DisplayName("예산 소진 시나리오 - 예산보다 많은 요청 들어올 때 초과 없음")
    void concurrentCashback_budgetExhausted_noOverGrant() throws InterruptedException {
        // 예산: 500원 / 요청: 100건 × 캐시백 10원 = 총 1,000원 요청 (예산 2배)
        // 기대: 50건만 지급 (500원), 나머지 50건은 미지급 (0원 반환)
        long testBudget = 500L;
        int totalRequests = 100;
        long paymentAmount = 100L;  // 캐시백 10원

        cashbackBudgetRepository.deleteAll();
        cashbackBudgetRepository.save(new CashbackBudget(testBudget));
        List<Long> accountIds = createTestAccounts(totalRequests);

        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalRequests);
        AtomicInteger grantedCount = new AtomicInteger(0);
        AtomicInteger exhaustedCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            final Long accountId = accountIds.get(i);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long granted = cashbackService.grantCashback(accountId, paymentAmount);
                    if (granted > 0) grantedCount.incrementAndGet();
                    else exhaustedCount.incrementAndGet();
                } catch (Exception e) {
                    log.warn("[테스트] 예외 - accountId={}", accountId);
                    log.warn("[test] exception - accountId={}", accountId);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        CashbackBudget finalBudget = cashbackBudgetRepository.findTop().orElseThrow();
        log.info("[결과] 지급={}, 소진={}, usedBudget={}", grantedCount.get(), exhaustedCount.get(), finalBudget.getUsedBudget());
        log.info("[result] granted={}, exhausted={}, usedBudget={}", grantedCount.get(), exhaustedCount.get(), finalBudget.getUsedBudget());

        assertThat(finalBudget.getUsedBudget()).isLessThanOrEqualTo(testBudget); // 예산 초과 없음
        assertThat(grantedCount.get() + exhaustedCount.get()).isEqualTo(totalRequests); // 전체 요청 처리 완료
    }

    private List<Long> createTestAccounts(int count) {
        List<Long> accountIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Account account = Account.builder()
                    .accountNumber("TEST-" + System.nanoTime() + "-" + i)
                    .ownerName("테스트유저" + i)
                    .balance(1_000_000L)
                    .build();
            accountIds.add(accountRepository.save(account).getId());
        }
        return accountIds;
    }
}
