package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.account.entity.Account;
import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.payment.dto.request.PaymentRequest;
import com.toss.cashback.domain.payment.dto.response.PaymentResponse;
import com.toss.cashback.domain.payment.entity.PaymentIdempotency;
import com.toss.cashback.domain.payment.entity.PaymentStatus;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.payment.repository.PaymentIdempotencyRepository;
import com.toss.cashback.domain.payment.repository.PaymentTransactionRepository;
import com.toss.cashback.domain.payment.recovery.entity.RecoveryAttempt;
import com.toss.cashback.domain.payment.recovery.entity.RecoveryResult;
import com.toss.cashback.domain.payment.recovery.entity.RecoveryTriggerType;
import com.toss.cashback.domain.payment.recovery.repository.RecoveryAttemptRepository;
import com.toss.cashback.domain.payment.recovery.service.RecoveryService;
import com.toss.cashback.domain.settlement.repository.SettlementRepository;
import com.toss.cashback.domain.settlement.service.SettlementService;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import com.toss.cashback.infrastructure.api.ExternalBankService;
import com.toss.cashback.infrastructure.redis.RedissonLockService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
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

// ======= [16번] PaymentFacade 통합 테스트 =======
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

    @Autowired
    private RecoveryService recoveryService;

    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;

    @MockBean
    private RedissonClient redissonClient;              // RedissonConfig의 실제 Redis 연결 차단

    @MockBean
    private RedissonLockService redissonLockService;    // 로컬 ReentrantLock으로 대체

    @MockBean
    private ExternalBankService externalBankService;    // 외부 API 승인 결과 제어

    @SpyBean
    private SettlementService settlementService;        // 기본은 실제 동작, 특정 테스트에서 실패 주입

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

        // @SpyBean 이전 테스트의 stub 초기화 (실패 주입 stub이 다음 테스트로 누출 방지)
        Mockito.reset(settlementService);

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
        recoveryAttemptRepository.deleteAll();
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
    // 시나리오 4: 이체 성공 + 후처리(정산 레코드 생성) 실패 → POST_PROCESS_FAILED
    // ===================================================================
    @Test
    @DisplayName("[후처리 실패] 이체 성공 + 정산 레코드 생성 실패 → POST_PROCESS_FAILED 기록 + 멱등성 PROCESSING 유지")
    void transferSuccess_settlementFail_postProcessFailedAndIdempotencyStaysProcessing() {
        // SettlementService.createSettlementRecord가 DB 오류로 실패하는 상황 주입
        doThrow(new RuntimeException("정산 DB 오류"))
                .when(settlementService).createSettlementRecord(any(), any(), any(), any());

        String idempotencyKey = UUID.randomUUID().toString();
        // 잔액 100만 → 10만 이체 (STEP 2 이체는 성공, STEP 3 정산 레코드 생성에서 실패)
        PaymentRequest request = buildRequest(idempotencyKey, buyerAccountId, merchantAccountId, 100_000L);

        assertThatThrownBy(() -> paymentFacade.processPayment(request))
                .isInstanceOf(CustomException.class);

        // [핵심 검증 1] 이체는 완료됐으므로 구매자 잔액 차감 (가상계좌로 이동)
        Account buyer = accountRepository.findById(buyerAccountId).orElseThrow();
        assertThat(buyer.getBalance()).isEqualTo(900_000L);

        // [핵심 검증 2] PaymentTransaction이 POST_PROCESS_FAILED 상태로 기록
        // → 운영팀이 식별 가능 + PostProcessRecoveryScheduler가 자동 복구 대상으로 감지
        List<PaymentTransaction> transactions =
                transactionRepository.findByFromAccountIdOrderByCreatedAtDesc(buyerAccountId);
        assertThat(transactions).hasSize(1);
        assertThat(transactions.get(0).getStatus()).isEqualTo(PaymentStatus.POST_PROCESS_FAILED);

        // [핵심 검증 3] 멱등성 레코드가 PROCESSING 유지 → 동일 key 재결제 차단 (이중 청구 방지)
        // (은행 출금은 완료됐으나 내부 후처리 미완료 상태이므로 재시도 허용하면 이중 청구 위험)
        Optional<PaymentIdempotency> record =
                idempotencyRepository.findByIdempotencyKeyAndExpiresAtAfter(idempotencyKey, LocalDateTime.now());
        assertThat(record).isPresent();
        assertThat(record.get().isCompleted()).isFalse();
    }

    // ===================================================================
    // 시나리오 5: 완료된 키로 동일 요청 재전송 → 캐시된 응답 반환 (잔액 1회만 차감)
    // ===================================================================
    @Test
    @DisplayName("[멱등성 캐시] 완료된 키로 동일 요청 재전송 → 동일 transactionId 반환, 잔액 1회 차감")
    void completedKey_sameRequest_returnsCachedResponse() {
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = buildRequest(idempotencyKey, buyerAccountId, merchantAccountId, 100_000L);

        PaymentResponse first = paymentFacade.processPayment(request);
        PaymentResponse second = paymentFacade.processPayment(request);   // 동일 요청 재전송

        // [핵심] 두 번째도 동일 transactionId 반환 (재처리 없이 캐시에서 즉시)
        assertThat(second.getTransactionId()).isEqualTo(first.getTransactionId());

        // [핵심] 잔액은 1회만 차감 (멱등성 보장)
        Account buyer = accountRepository.findById(buyerAccountId).orElseThrow();
        assertThat(buyer.getBalance()).isEqualTo(900_000L);

        // PaymentTransaction도 1건만 생성
        assertThat(transactionRepository.findByFromAccountIdOrderByCreatedAtDesc(buyerAccountId)).hasSize(1);
    }

    // ===================================================================
    // 시나리오 6: 완료된 키로 다른 금액 재요청 → 409 IDEMPOTENCY_REQUEST_MISMATCH
    // ===================================================================
    @Test
    @DisplayName("[멱등성 불일치] 완료된 키로 다른 금액 재요청 → 409 IDEMPOTENCY_REQUEST_MISMATCH")
    void completedKey_differentAmount_idempotencyRequestMismatch() {
        // 첫 번째 결제 완료 (amount = 100,000)
        String idempotencyKey = UUID.randomUUID().toString();
        paymentFacade.processPayment(
                buildRequest(idempotencyKey, buyerAccountId, merchantAccountId, 100_000L));

        // 같은 키 + 다른 금액으로 재요청 (클라이언트 버그 또는 위조)
        assertThatThrownBy(() -> paymentFacade.processPayment(
                buildRequest(idempotencyKey, buyerAccountId, merchantAccountId, 200_000L)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.IDEMPOTENCY_REQUEST_MISMATCH);

        // 잔액은 첫 번째 결제만 차감 (두 번째 요청 차단됨)
        Account buyer = accountRepository.findById(buyerAccountId).orElseThrow();
        assertThat(buyer.getBalance()).isEqualTo(900_000L);
    }

    // ===================================================================
    // 시나리오 7: PROCESSING 상태 키로 다른 요청 → 409 IDEMPOTENCY_REQUEST_MISMATCH
    // ===================================================================
    @Test
    @DisplayName("[멱등성 불일치] PROCESSING 중인 키로 다른 계좌 재요청 → 409 IDEMPOTENCY_REQUEST_MISMATCH")
    void processingKey_differentToAccount_idempotencyRequestMismatch() {
        String idempotencyKey = UUID.randomUUID().toString();

        // PROCESSING 레코드 직접 삽입 (toAccountId = merchantAccountId, amount = 100_000)
        idempotencyRepository.save(
                PaymentIdempotency.createProcessing(
                        idempotencyKey, buyerAccountId, merchantAccountId, 100_000L));

        // 같은 키 + 다른 toAccountId로 요청
        assertThatThrownBy(() -> paymentFacade.processPayment(
                buildRequest(idempotencyKey, buyerAccountId, buyerAccountId + 999L, 100_000L)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.IDEMPOTENCY_REQUEST_MISMATCH);
    }

    // ===================================================================
    // 시나리오 8: POST_PROCESS_FAILED → 자동 복구 → PENDING_SETTLEMENT 전이
    // ===================================================================
    @Test
    @DisplayName("[복구 통합] POST_PROCESS_FAILED → AUTO 복구 → PENDING_SETTLEMENT + RecoveryAttempt SUCCESS 저장")
    void postProcessFailed_autoRecovery_transitionsToPendingSettlement() {
        // [1] 정산 레코드 생성을 강제로 실패시켜 POST_PROCESS_FAILED 유발
        doThrow(new RuntimeException("정산 DB 오류"))
                .when(settlementService).createSettlementRecord(any(), any(), any(), any());

        assertThatThrownBy(() -> paymentFacade.processPayment(
                buildRequest(UUID.randomUUID().toString(), buyerAccountId, merchantAccountId, 100_000L)))
                .isInstanceOf(CustomException.class);

        // [2] SpyBean 초기화 → 이후 복구 시 정산 레코드 실제 생성 가능
        Mockito.reset(settlementService);
        doNothing().when(externalBankService).approve(anyLong(), anyLong());

        Long txId = transactionRepository.findByFromAccountIdOrderByCreatedAtDesc(buyerAccountId)
                .get(0).getId();
        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.POST_PROCESS_FAILED);

        // [3] 자동 복구 실행 (PostProcessRecoveryScheduler가 호출하는 방식과 동일)
        recoveryService.recover(txId, RecoveryTriggerType.AUTO);

        // [핵심 검증 1] 상태 전이: POST_PROCESS_FAILED → PENDING_SETTLEMENT
        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING_SETTLEMENT);

        // [핵심 검증 2] 정산 레코드 재생성 확인
        assertThat(settlementRepository.findByPaymentTransactionId(txId)).hasSize(1);

        // [핵심 검증 3] RecoveryAttempt AUTO SUCCESS 이력 저장
        List<RecoveryAttempt> attempts =
                recoveryAttemptRepository.findByPaymentTransactionIdOrderByAttemptedAtDesc(txId);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getTriggerType()).isEqualTo(RecoveryTriggerType.AUTO);
        assertThat(attempts.get(0).getResult()).isEqualTo(RecoveryResult.SUCCESS);
        assertThat(attempts.get(0).getFailureReason()).isNull();
    }

    // ===================================================================
    // 시나리오 9: POST_PROCESS_FAILED → 수동 복구 → MANUAL RecoveryAttempt + 재복구 no-op
    // ===================================================================
    @Test
    @DisplayName("[복구 통합] POST_PROCESS_FAILED → MANUAL 복구 → MANUAL RecoveryAttempt + 재복구 no-op 멱등성")
    void postProcessFailed_manualRecovery_savesManualAttemptAndNoOpOnSecondCall() {
        // [1] POST_PROCESS_FAILED 유발
        doThrow(new RuntimeException("정산 DB 오류"))
                .when(settlementService).createSettlementRecord(any(), any(), any(), any());

        assertThatThrownBy(() -> paymentFacade.processPayment(
                buildRequest(UUID.randomUUID().toString(), buyerAccountId, merchantAccountId, 100_000L)))
                .isInstanceOf(CustomException.class);

        Mockito.reset(settlementService);
        doNothing().when(externalBankService).approve(anyLong(), anyLong());

        Long txId = transactionRepository.findByFromAccountIdOrderByCreatedAtDesc(buyerAccountId)
                .get(0).getId();

        // [2] 수동 복구 실행 (운영자 API 호출과 동일)
        recoveryService.recover(txId, RecoveryTriggerType.MANUAL);

        // MANUAL RecoveryAttempt 저장 확인
        List<RecoveryAttempt> attempts =
                recoveryAttemptRepository.findByPaymentTransactionIdOrderByAttemptedAtDesc(txId);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getTriggerType()).isEqualTo(RecoveryTriggerType.MANUAL);
        assertThat(attempts.get(0).getResult()).isEqualTo(RecoveryResult.SUCCESS);

        // [3] 이미 복구된 건 재복구 요청 → no-op + SUCCESS attempt 추가 저장 (멱등성)
        recoveryService.recover(txId, RecoveryTriggerType.MANUAL);

        // no-op도 attempt 기록 (감사 로그)
        List<RecoveryAttempt> attemptsAfterNoOp =
                recoveryAttemptRepository.findByPaymentTransactionIdOrderByAttemptedAtDesc(txId);
        assertThat(attemptsAfterNoOp).hasSize(2);

        // 두 번째 attempt: no-op이지만 SUCCESS로 기록
        assertThat(attemptsAfterNoOp.get(0).getResult()).isEqualTo(RecoveryResult.SUCCESS);

        // [핵심] 트랜잭션 상태는 PENDING_SETTLEMENT 그대로 (no-op이 상태 바꾸지 않음)
        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING_SETTLEMENT);
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
