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
 * PaymentFacade 통합 테스트 - 4가지 핵심 시나리오 검증
 * =====================================================================
 *
 * 테스트 환경:
 * - @SpringBootTest: 실제 Spring Context (JPA, PaymentService, SettlementService 등)
 * - @ActiveProfiles("test"): H2 인메모리 DB (MySQL 불필요)
 * - @MockBean RedissonClient: RedissonConfig의 실제 Redis 연결 차단
 * - @MockBean RedissonLockService: 로컬 ReentrantLock으로 대체 (분산 락 시뮬레이션)
 * - @MockBean ExternalBankService: 외부 API 승인 성공/실패 제어
 * - @MockBean CashbackService: 캐시백 성공/실패 제어
 *
 * 검증 시나리오:
 * 1. [멱등성] 동일 key 동시 2요청 → DB에 PaymentTransaction 1건만 생성
 * 2. [원자성] 외부 승인 실패 → 계좌 변동 없음 + 멱등성 레코드 삭제 (재시도 허용)
 * 3. [이중청구 방지] 외부 승인 성공 + 이체 실패 → 멱등성 PROCESSING 유지 (재시도 차단)
 * 4. [캐시백 비임계성] 외부 승인 성공 + 캐시백 실패 → 결제 PENDING_SETTLEMENT, cashback=0
 *
 * [주의] CashbackConcurrencyTest와 다른 @MockBean 조합
 *        → Spring이 별도 Context를 생성하므로 DataInitializer가 독립 실행됨
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

    @MockBean
    private CashbackService cashbackService;            // 캐시백 지급 결과 제어

    // 분산 락을 대체하는 로컬 재진입 락 (테스트 간 격리를 위해 @BeforeEach에서 참조만)
    private final ReentrantLock localLock = new ReentrantLock();

    private Long buyerAccountId;
    private Long merchantAccountId;

    @BeforeEach
    void setUp() throws Exception {
        // executeWithMultiLock → 로컬 ReentrantLock으로 동작 (PaymentService.executeTransfer에서 사용)
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
                if (localLock.isHeldByCurrentThread()) {
                    localLock.unlock();
                }
            }
        }).when(redissonLockService).executeWithMultiLock(anyList(), anyLong(), anyLong(), any(Callable.class));

        // 기본값: 외부 API 승인 성공 (각 테스트에서 필요 시 override)
        doNothing().when(externalBankService).approve(anyLong(), anyLong());

        // 기본값: 캐시백 1,000원 지급 (각 테스트에서 필요 시 override)
        when(cashbackService.grantCashback(anyLong(), anyLong())).thenReturn(1_000L);

        // 테스트 전용 계좌 생성 (UUID suffix로 테스트 간 accountNumber 충돌 방지)
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        buyerAccountId = accountRepository.save(Account.builder()
                .accountNumber("TEST-BUYER-" + suffix)
                .ownerName("테스트구매자")
                .balance(1_000_000L)    // 100만원 초기 잔액
                .build()).getId();
        merchantAccountId = accountRepository.save(Account.builder()
                .accountNumber("TEST-MERCHANT-" + suffix)
                .ownerName("테스트가맹점")
                .balance(0L)
                .build()).getId();
    }

    @AfterEach
    void tearDown() {
        // 테스트 데이터 정리 (FK 없으므로 순서 무관하나 명시적으로 역순 삭제)
        settlementRepository.deleteAll();
        transactionRepository.deleteAll();
        idempotencyRepository.deleteAll();
        // 가상계좌(DataInitializer 생성)는 유지, 이번 테스트에서 생성한 계좌만 삭제
        accountRepository.deleteById(buyerAccountId);
        accountRepository.deleteById(merchantAccountId);
    }

    // ===================================================================
    // 시나리오 1: 동일 key 동시 2요청 → 실제 1건만 처리
    // ===================================================================
    /**
     * 검증 포인트:
     * - PaymentIdempotency의 unique constraint가 동시 중복 요청을 DB 레벨에서 차단
     * - 두 스레드가 동시에 saveAndFlush를 호출해도 1건만 INSERT 성공
     * - 구매자 잔액은 정확히 1회만 차감
     */
    @Test
    @DisplayName("[멱등성] 동일 idempotencyKey 동시 2요청 → PaymentTransaction 1건, 잔액 1회 차감")
    void concurrentDuplicateRequest_onlyOneTransactionCreated() throws InterruptedException {
        // GIVEN
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = buildRequest(idempotencyKey, buyerAccountId, merchantAccountId, 100_000L);

        AtomicInteger completedThreadCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);  // 두 스레드 동시 출발 신호
        CountDownLatch doneLatch = new CountDownLatch(2);   // 두 스레드 완료 대기

        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();                             // 동시 출발 대기
                    paymentFacade.processPayment(request);          // 중복 요청 시도
                } catch (Exception e) {
                    log.info("[테스트1] 스레드 예외 (정상 케이스): {}", e.getMessage());
                } finally {
                    completedThreadCount.incrementAndGet();
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();                                     // 두 스레드 동시 출발
        boolean allCompleted = doneLatch.await(10, TimeUnit.SECONDS);

        // THEN: 두 스레드 모두 10초 내 완료 (데드락 없음)
        assertThat(allCompleted).isTrue();
        assertThat(completedThreadCount.get()).isEqualTo(2);

        // [핵심 검증] PaymentTransaction은 정확히 1건만 생성
        List<PaymentTransaction> transactions =
                transactionRepository.findByFromAccountIdOrderByCreatedAtDesc(buyerAccountId);
        assertThat(transactions).hasSize(1);

        // [핵심 검증] 구매자 잔액은 1회만 차감 (100,000원 차감 → 900,000원)
        Account buyer = accountRepository.findById(buyerAccountId).orElseThrow();
        assertThat(buyer.getBalance()).isEqualTo(900_000L);
    }

    // ===================================================================
    // 시나리오 2: 외부 승인 실패 → 계좌 변동 없음 + 멱등성 레코드 삭제
    // ===================================================================
    /**
     * 검증 포인트:
     * - 외부 승인 실패 시 DB 이체 미실행 (원자성 보장)
     * - 멱등성 레코드 삭제 → 동일 키로 재시도 허용 (사용자가 다시 결제 가능)
     * - COMPENSATED 없는 이유: C안에서 외부 승인 전에는 DB 변경이 없어 보상 불필요
     */
    @Test
    @DisplayName("[원자성] 외부 승인 실패 → 잔액 미변경 + 멱등성 레코드 삭제 (재시도 허용)")
    void externalApprovalFail_noDbChanges_idempotencyDeleted() {
        // GIVEN: 외부 API가 타임아웃으로 실패하도록 설정
        doThrow(new CustomException(ErrorCode.EXTERNAL_BANK_TIMEOUT))
                .when(externalBankService).approve(anyLong(), anyLong());

        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = buildRequest(idempotencyKey, buyerAccountId, merchantAccountId, 100_000L);

        // WHEN: 결제 요청 → 외부 승인 실패로 CustomException 발생
        assertThatThrownBy(() -> paymentFacade.processPayment(request))
                .isInstanceOf(CustomException.class);

        // THEN: 구매자 잔액 변동 없음 (이체 미실행)
        Account buyer = accountRepository.findById(buyerAccountId).orElseThrow();
        assertThat(buyer.getBalance()).isEqualTo(1_000_000L);

        // THEN: PaymentTransaction 미생성 (승인 전에는 DB 이체 없음)
        assertThat(transactionRepository.findByFromAccountIdOrderByCreatedAtDesc(buyerAccountId))
                .isEmpty();

        // THEN: 멱등성 레코드 삭제 → 동일 key로 재시도 허용 (사용자 재결제 가능)
        Optional<PaymentIdempotency> record =
                idempotencyRepository.findByIdempotencyKeyAndExpiresAtAfter(idempotencyKey, LocalDateTime.now());
        assertThat(record).isEmpty();
    }

    // ===================================================================
    // 시나리오 3: 외부 승인 성공 + 이체 실패 → 멱등성 PROCESSING 유지
    // ===================================================================
    /**
     * 검증 포인트:
     * - 외부 API 승인 완료 후 내부 이체 실패 시 멱등성 레코드를 PROCESSING 상태로 유지
     * - 이유: 외부 은행은 이미 승인 처리했을 수 있어, 동일 key 재결제 허용 시 이중 청구 위험
     * - 운영팀이 수동으로 상태 확인 후 처리해야 하는 POST_PROCESS_FAILED 케이스
     *
     * 실패 시뮬레이션 방법:
     * - 구매자 잔액(100만)보다 큰 금액(200만)으로 요청 → executeTransfer에서 INSUFFICIENT_BALANCE 발생
     */
    @Test
    @DisplayName("[이중청구 방지] 외부 승인 성공 + 이체 실패 → 멱등성 PROCESSING 유지 (재시도 차단)")
    void externalApprovalSuccess_transferFail_idempotencyStaysProcessing() {
        // GIVEN: 외부 API 승인 성공 (기본값) + 잔액 초과 금액으로 요청
        String idempotencyKey = UUID.randomUUID().toString();
        // 구매자 잔액 100만, 요청 금액 200만 → executeTransfer에서 INSUFFICIENT_BALANCE → STEP2 실패
        PaymentRequest request = buildRequest(idempotencyKey, buyerAccountId, merchantAccountId, 2_000_000L);

        // WHEN: STEP1(외부 승인) 성공 후 STEP2(이체) 실패 → INTERNAL_SERVER_ERROR
        assertThatThrownBy(() -> paymentFacade.processPayment(request))
                .isInstanceOf(CustomException.class);

        // THEN: 이체 롤백 → 구매자 잔액 변동 없음
        Account buyer = accountRepository.findById(buyerAccountId).orElseThrow();
        assertThat(buyer.getBalance()).isEqualTo(1_000_000L);

        // THEN: [핵심 검증] 멱등성 레코드가 PROCESSING 상태로 남아 있음 (삭제되지 않음)
        //       → 동일 key 재결제 차단 (외부 은행 이중 청구 방지)
        //       → 운영팀이 외부 은행 승인 여부 수동 확인 후 처리해야 함
        Optional<PaymentIdempotency> record =
                idempotencyRepository.findByIdempotencyKeyAndExpiresAtAfter(idempotencyKey, LocalDateTime.now());
        assertThat(record).isPresent();
        assertThat(record.get().isCompleted()).isFalse();    // PROCESSING 상태 유지 확인
    }

    // ===================================================================
    // 시나리오 4: 외부 승인 성공 + 캐시백 실패 → 결제는 PENDING_SETTLEMENT
    // ===================================================================
    /**
     * 검증 포인트:
     * - 캐시백은 부가 서비스이므로 실패해도 결제 자체는 성공
     * - cashbackAmount = 0 반환 (캐시백 미지급 처리)
     * - PaymentTransaction 상태는 PENDING_SETTLEMENT (정산 대기 중)
     * - 구매자 잔액은 정상 차감 (결제는 완료)
     *
     * 실무 근거:
     * - 캐시백 예산 고갈, 캐시백 서버 장애 등 다양한 이유로 실패 가능
     * - 캐시백 실패가 결제 취소로 이어지면 UX 최악 (구매자가 결제했는데 취소?)
     * - 캐시백은 별도 retry 배치로 사후 지급 가능
     */
    @Test
    @DisplayName("[캐시백 비임계성] 외부 승인 성공 + 캐시백 실패 → 결제 PENDING_SETTLEMENT, cashback=0")
    void externalApprovalSuccess_cashbackFail_paymentStillPendingSettlement() {
        // GIVEN: 캐시백 서비스가 DB 장애로 예외를 던지도록 설정
        when(cashbackService.grantCashback(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("캐시백 예산 DB 연결 장애"));

        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = buildRequest(idempotencyKey, buyerAccountId, merchantAccountId, 100_000L);

        // WHEN: 결제 요청
        PaymentResponse response = paymentFacade.processPayment(request);

        // THEN: 결제 응답 정상 반환 (캐시백 0원으로 처리)
        assertThat(response).isNotNull();
        assertThat(response.getCashbackAmount()).isEqualTo(0L);

        // THEN: PaymentTransaction은 PENDING_SETTLEMENT 상태 (정산 스케줄러 대기 중)
        List<PaymentTransaction> transactions =
                transactionRepository.findByFromAccountIdOrderByCreatedAtDesc(buyerAccountId);
        assertThat(transactions).hasSize(1);
        assertThat(transactions.get(0).getStatus()).isEqualTo(PaymentStatus.PENDING_SETTLEMENT);
        assertThat(transactions.get(0).getCashbackAmount()).isEqualTo(0L);  // 캐시백 미지급

        // THEN: 구매자 잔액 정상 차감 (결제 완료)
        Account buyer = accountRepository.findById(buyerAccountId).orElseThrow();
        assertThat(buyer.getBalance()).isEqualTo(900_000L);
    }

    // ===================================================================
    // 헬퍼 메서드
    // ===================================================================

    /**
     * PaymentRequest 생성 헬퍼.
     * PaymentRequest가 @NoArgsConstructor만 가지므로 ReflectionTestUtils로 필드 주입.
     * (빈 생성자 + @Setter를 추가하거나 @Builder를 붙이면 프로덕션 코드 노출 위험 → 테스트에서만 reflection 사용)
     */
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
