package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.account.entity.Account;
import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.payment.dto.request.PaymentRequest;
import com.toss.cashback.domain.payment.entity.PaymentIdempotency;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.payment.repository.PaymentIdempotencyRepository;
import com.toss.cashback.domain.payment.repository.PaymentTransactionRepository;
import com.toss.cashback.domain.payment.repository.SettlementRepository;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import com.toss.cashback.infrastructure.api.ExternalBankService;
import com.toss.cashback.infrastructure.redis.RedissonLockService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// ======= [11번] PaymentFacade 통합 테스트 =======
/**
 * =====================================================================
 * PaymentFacade 통합 테스트 - 3가지 핵심 시나리오 검증
 * =====================================================================
 *
 * 테스트 환경:
 * - @SpringBootTest: 실제 Spring Context (JPA, PaymentService, SettlementService 등)
 * - @ActiveProfiles("test"): H2 인메모리 DB (MySQL 불필요)
 * - @MockBean RedissonClient: RedissonConfig의 실제 Redis 연결 차단
 * - @MockBean RedissonLockService: 로컬 ReentrantLock으로 대체 (분산 락 시뮬레이션)
 * - @MockBean ExternalBankService: 외부 API 승인 성공/실패 제어
 *
 * 검증 시나리오:
 * 1. [멱등성] 동일 key 동시 2요청 → DB에 PaymentTransaction 1건만 생성
 * 2. [원자성] 외부 승인 실패 → 계좌 변동 없음 + 멱등성 레코드 삭제 (재시도 허용)
 * 3. [이중청구 방지] 외부 승인 성공 + 이체 실패 → 멱등성 PROCESSING 유지 (재시도 차단)
 * =====================================================================
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PaymentFacade 통합 테스트")
class PaymentFacadeIntegrationTest {

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private PaymentIdempotencyRepository idempotencyRepository;

    @Autowired
    private SettlementRepository settlementRepository;

    @MockBean
    private RedissonClient redissonClient;              // RedissonConfig의 실제 Redis 연결 차단

    @MockBean
    private RedissonLockService redissonLockService;    // 로컬 ReentrantLock으로 대체

    @MockBean
    private ExternalBankService externalBankService;    // 외부 API 승인 결과 제어

    private final ReentrantLock localLock = new ReentrantLock();

    private Long buyerAccountId;
    private Long merchantAccountId;

    @BeforeEach
    void setUp() throws Exception {
        // executeWithMultiLock → 로컬 ReentrantLock으로 동작 (분산 락 시뮬레이션)
        doAnswer(inv -> {
            long waitTime = inv.getArgument(1);
            Callable<?> task = inv.getArgument(3);
            boolean acquired = localLock.tryLock(waitTime, TimeUnit.SECONDS);
            if (!acquired) {
                throw new CustomException(ErrorCode.LOCK_ACQUISITION_FAILED);
            }
            try {
                return task.call();
            } finally {
                if (localLock.isHeldByCurrentThread()) localLock.unlock();
            }
        }).when(redissonLockService).executeWithMultiLock(anyList(), anyLong(), anyLong(), any(Callable.class));

        // 기본값: 외부 API 승인 성공 (각 테스트에서 필요 시 override)
        doNothing().when(externalBankService).approve(anyLong(), anyLong());

        // 테스트 전용 계좌 생성 (UUID suffix로 테스트 간 accountNumber 충돌 방지)
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        buyerAccountId = accountRepository.save(Account.builder()
                .accountNumber("TEST-BUYER-" + suffix)
                .ownerName("테스트구매자")
                .balance(1_000_000L)
                .build()).getId();
        merchantAccountId = accountRepository.save(Account.builder()
                .accountNumber("TEST-MERCHANT-" + suffix)
                .ownerName("테스트가맹점")
                .balance(0L)
                .build()).getId();
    }

    @AfterEach
    void tearDown() {
        settlementRepository.deleteAll();
        transactionRepository.deleteAll();
        idempotencyRepository.deleteAll();
        accountRepository.deleteById(buyerAccountId);
        accountRepository.deleteById(merchantAccountId);
    }

    // ===================================================================
    // 시나리오 1: 동일 key 동시 2요청 → 실제 1건만 처리
    // ===================================================================
    @Test
    @DisplayName("[멱등성] 동일 idempotencyKey 동시 2요청 → PaymentTransaction 1건, 잔액 1회 차감")
    void concurrentDuplicateRequest_onlyOneTransactionCreated() throws InterruptedException {
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = buildRequest(idempotencyKey, buyerAccountId, merchantAccountId, 100_000L);

        AtomicInteger completedThreadCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    paymentFacade.processPayment(request);
                } catch (Exception e) {
                    log.info("[테스트1] 스레드 예외 (정상 케이스): {}", e.getMessage());
                } finally {
                    completedThreadCount.incrementAndGet();
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        boolean allCompleted = doneLatch.await(10, TimeUnit.SECONDS);

        assertThat(allCompleted).isTrue();
        assertThat(completedThreadCount.get()).isEqualTo(2);

        // [핵심 검증] PaymentTransaction은 정확히 1건만 생성
        List<PaymentTransaction> transactions =
                transactionRepository.findByFromAccountIdOrderByCreatedAtDesc(buyerAccountId);
        assertThat(transactions).hasSize(1);

        // [핵심 검증] 구매자 잔액은 1회만 차감
        Account buyer = accountRepository.findById(buyerAccountId).orElseThrow();
        assertThat(buyer.getBalance()).isEqualTo(900_000L);
    }

    // ===================================================================
    // 시나리오 2: 외부 승인 실패 → 계좌 변동 없음 + 멱등성 레코드 삭제
    // ===================================================================
    @Test
    @DisplayName("[원자성] 외부 승인 실패 → 잔액 미변경 + 멱등성 레코드 삭제 (재시도 허용)")
    void externalApprovalFail_noDbChanges_idempotencyDeleted() {
        doThrow(new CustomException(ErrorCode.EXTERNAL_BANK_TIMEOUT))
                .when(externalBankService).approve(anyLong(), anyLong());

        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = buildRequest(idempotencyKey, buyerAccountId, merchantAccountId, 100_000L);

        assertThatThrownBy(() -> paymentFacade.processPayment(request))
                .isInstanceOf(CustomException.class);

        // 잔액 변동 없음
        Account buyer = accountRepository.findById(buyerAccountId).orElseThrow();
        assertThat(buyer.getBalance()).isEqualTo(1_000_000L);

        // PaymentTransaction 미생성
        assertThat(transactionRepository.findByFromAccountIdOrderByCreatedAtDesc(buyerAccountId)).isEmpty();

        // 멱등성 레코드 삭제 → 동일 key 재시도 허용
        Optional<PaymentIdempotency> record =
                idempotencyRepository.findByIdempotencyKeyAndExpiresAtAfter(idempotencyKey, LocalDateTime.now());
        assertThat(record).isEmpty();
    }

    // ===================================================================
    // 시나리오 3: 외부 승인 성공 + 이체 실패 → 멱등성 PROCESSING 유지
    // ===================================================================
    @Test
    @DisplayName("[이중청구 방지] 외부 승인 성공 + 이체 실패 → 멱등성 PROCESSING 유지 (재시도 차단)")
    void externalApprovalSuccess_transferFail_idempotencyStaysProcessing() {
        String idempotencyKey = UUID.randomUUID().toString();
        // 구매자 잔액 100만, 요청 금액 200만 → INSUFFICIENT_BALANCE → STEP2 실패
        PaymentRequest request = buildRequest(idempotencyKey, buyerAccountId, merchantAccountId, 2_000_000L);

        assertThatThrownBy(() -> paymentFacade.processPayment(request))
                .isInstanceOf(CustomException.class);

        // 이체 롤백 → 잔액 변동 없음
        Account buyer = accountRepository.findById(buyerAccountId).orElseThrow();
        assertThat(buyer.getBalance()).isEqualTo(1_000_000L);

        // [핵심 검증] 멱등성 레코드가 PROCESSING 상태로 남아 있음 → 이중 청구 방지
        Optional<PaymentIdempotency> record =
                idempotencyRepository.findByIdempotencyKeyAndExpiresAtAfter(idempotencyKey, LocalDateTime.now());
        assertThat(record).isPresent();
        assertThat(record.get().isCompleted()).isFalse();
    }

    // ===================================================================
    // 헬퍼 메서드
    // ===================================================================

    private PaymentRequest buildRequest(String idempotencyKey, Long fromAccountId,
                                        Long toAccountId, Long amount) {
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "idempotencyKey", idempotencyKey);
        ReflectionTestUtils.setField(request, "fromAccountId", fromAccountId);
        ReflectionTestUtils.setField(request, "toAccountId", toAccountId);
        ReflectionTestUtils.setField(request, "amount", amount);
        return request;
    }
}
