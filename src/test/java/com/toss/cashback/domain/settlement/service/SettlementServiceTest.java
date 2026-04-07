package com.toss.cashback.domain.settlement.service;

import com.toss.cashback.domain.account.entity.Account;
import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.payment.repository.PaymentTransactionRepository;
import com.toss.cashback.domain.settlement.entity.MerchantSettlementPolicy;
import com.toss.cashback.domain.settlement.entity.SettlementAmountResult;
import com.toss.cashback.domain.settlement.entity.SettlementRecord;
import com.toss.cashback.domain.settlement.entity.SettlementStatus;
import com.toss.cashback.domain.settlement.repository.MerchantSettlementPolicyRepository;
import com.toss.cashback.domain.settlement.repository.SettlementRepository;
import com.toss.cashback.infrastructure.api.ExternalBankService;
import com.toss.cashback.infrastructure.redis.RedissonLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.eq;

// ======= [11번] SettlementService 단위 테스트 =======
/**
 * =====================================================================
 * SettlementService 단위 테스트 (Mockito)
 * =====================================================================
 *
 * 테스트 전략:
 * - Spring Context 없이 순수 단위 테스트
 * - @Spy SettlementCalculator: 실제 계산 로직 사용 (계산 정확성 검증 포함)
 * - Repository, Lock, 외부 API를 Mock으로 대체
 *
 * 검증 시나리오:
 * 1. 정산 레코드 생성 - 등록된 가맹점 정책 적용 (수수료 3.5% / VAT / D+1)
 * 2. 정산 레코드 생성 - 미등록 가맹점 기본 정책 자동 적용
 * 3. 배치 정산 - PENDING 건 전체 성공 처리 (계좌 잔액 검증)
 * 4. 배치 정산 - 일부 실패 시 실패 사유 기록 후 나머지 건 계속 처리 (Fail-safe)
 * =====================================================================
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementService 단위 테스트")
class SettlementServiceTest {

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private MerchantSettlementPolicyRepository policyRepository;

    @Mock
    private PaymentTransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ExternalBankService externalBankService;

    @Mock
    private RedissonLockService redissonLockService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Spy
    private SettlementCalculator settlementCalculator;  // 실제 계산 로직 사용

    @InjectMocks
    private SettlementService settlementService;

    @BeforeEach
    void setUp() throws Exception {
        // 분산 락 pass-through: 락 없이 태스크 바로 실행
        lenient().when(redissonLockService.executeWithMultiLock(
                        anyList(), anyLong(), anyLong(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(3)).call());

        // 트랜잭션 pass-through: 트랜잭션 없이 콜백 바로 실행
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> {
                    org.springframework.transaction.support.TransactionCallback<?> callback =
                            inv.getArgument(0);
                    return callback.doInTransaction(
                            mock(org.springframework.transaction.TransactionStatus.class));
                });

        // saveAndFlush는 전달받은 객체 그대로 반환 (createSettlementRecord에서 saveAndFlush 사용)
        lenient().when(settlementRepository.saveAndFlush(any(SettlementRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ================================================================
    // 시나리오 1: 등록된 가맹점 정책 적용
    // ================================================================

    @Test
    @DisplayName("정산 레코드 생성 - 등록된 가맹점 정책(3.5% VAT포함) 적용 → 금액 정확성 검증")
    void createSettlementRecord_registeredPolicy_correctFeeAndNetAmount() {
        // given: 가맹점 수수료 3.5% + VAT 포함 정책
        MerchantSettlementPolicy policy = MerchantSettlementPolicy.create(
                2L, new BigDecimal("0.0350"), true, 1);
        when(policyRepository.findByMerchantAccountId(2L)).thenReturn(Optional.of(policy));

        // when: 100,000원 결제 정산 레코드 생성
        SettlementRecord result = settlementService.createSettlementRecord(1L, 100L, 2L, 100_000L);

        // then: 금액 계산 정확성 검증
        assertThat(result.getGrossAmount()).isEqualTo(100_000L);
        assertThat(result.getFeeAmount()).isEqualTo(3_500L);    // 100,000 × 3.5% = 3,500
        assertThat(result.getVatAmount()).isEqualTo(350L);      // 3,500 × 10% = 350
        assertThat(result.getNetAmount()).isEqualTo(96_150L);   // 100,000 - 3,500 - 350

        // 항등식: grossAmount = feeAmount + vatAmount + netAmount
        assertThat(result.getGrossAmount())
                .isEqualTo(result.getFeeAmount() + result.getVatAmount() + result.getNetAmount());

        assertThat(result.getStatus()).isEqualTo(SettlementStatus.PENDING);

        // [설계 의도] 정책 스냅샷 저장 - 이후 수수료율 변경되어도 당시 조건 역추적 가능
        assertThat(result.getPolicyFeeRate()).isEqualByComparingTo(new BigDecimal("0.0350"));
        assertThat(result.isPolicyVatIncluded()).isTrue();

        verify(policyRepository).findByMerchantAccountId(2L);
        verify(settlementRepository).saveAndFlush(any());
    }

    // ================================================================
    // 시나리오 2: 미등록 가맹점 기본 정책 자동 적용
    // ================================================================

    @Test
    @DisplayName("정산 레코드 생성 - 미등록 가맹점에 기본 정책(3.5%/VAT/D+1) 자동 적용")
    void createSettlementRecord_unregisteredMerchant_defaultPolicyApplied() {
        // given: 정책 미등록 가맹점 (Optional.empty 반환)
        when(policyRepository.findByMerchantAccountId(999L)).thenReturn(Optional.empty());

        // when
        SettlementRecord result = settlementService.createSettlementRecord(1L, 100L, 999L, 100_000L);

        // then: 기본 정책(3.5% / VAT포함 / D+1)과 동일한 계산 결과
        assertThat(result.getFeeAmount()).isEqualTo(3_500L);
        assertThat(result.getVatAmount()).isEqualTo(350L);
        assertThat(result.getNetAmount()).isEqualTo(96_150L);
        assertThat(result.getPolicyFeeRate()).isEqualByComparingTo(new BigDecimal("0.0350"));
    }

    // ================================================================
    // 시나리오 3: 배치 정산 - 전체 성공
    // ================================================================

    @Test
    @DisplayName("배치 정산 - PENDING 건 전체 성공: 계좌 잔액 이동 + SETTLED 상태 변경")
    void processAllPendingSettlements_allSuccess_balanceTransferredAndSettled() {
        // given: 정산 대상 레코드 1건
        SettlementRecord record = buildTestSettlementRecord(1L, 1L, 100L, 2L);
        when(settlementRepository.findByStatusAndExpectedSettlementDateLessThanEqual(
                eq(SettlementStatus.PENDING), any(LocalDate.class))).thenReturn(List.of(record));
        when(settlementRepository.findById(1L)).thenReturn(Optional.of(record));

        // 가상계좌(virtualAccountId=100L), 가맹점(merchantAccountId=2L) 계좌
        Account virtualAccount = buildAccount("TOSS-VIRTUAL-001", 1_000_000L);
        Account merchantAccount = buildAccount("MERCHANT-001", 0L);
        when(accountRepository.findById(100L)).thenReturn(Optional.of(virtualAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(merchantAccount));

        PaymentTransaction paymentTx = mock(PaymentTransaction.class);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(paymentTx));

        // when
        settlementService.processAllPendingSettlements();

        // then: 외부 은행 승인 호출
        verify(externalBankService).approve(2L, 96_150L);

        // 가상계좌 → 가맹점 이체 (netAmount = 96,150)
        assertThat(virtualAccount.getBalance()).isEqualTo(1_000_000L - 96_150L);
        assertThat(merchantAccount.getBalance()).isEqualTo(96_150L);

        // 상태 SETTLED + PaymentTransaction SUCCESS
        assertThat(record.getStatus()).isEqualTo(SettlementStatus.SETTLED);
        verify(paymentTx).markSuccess();
    }

    // ================================================================
    // 시나리오 4: 배치 정산 - 일부 실패 (Fail-safe 설계 검증)
    // ================================================================

    @Test
    @DisplayName("배치 정산 - record1 실패 시 실패 사유 기록 후 record2 계속 처리 (Fail-safe)")
    void processAllPendingSettlements_partialFailure_failureRecordedAndOthersContinue() {
        // given: record1(실패 예정, merchantId=2), record2(성공 예정, merchantId=3)
        SettlementRecord record1 = buildTestSettlementRecord(1L, 10L, 100L, 2L);
        SettlementRecord record2 = buildTestSettlementRecord(2L, 20L, 100L, 3L);
        when(settlementRepository.findByStatusAndExpectedSettlementDateLessThanEqual(
                eq(SettlementStatus.PENDING), any(LocalDate.class)))
                .thenReturn(List.of(record1, record2));

        // record1: externalBankService.approve 첫 번째 호출 시 실패
        doThrow(new RuntimeException("외부 은행 오류"))
                .doNothing()
                .when(externalBankService).approve(anyLong(), anyLong());

        // 실패 처리 시 record1.recordFailure() 호출을 위한 findById 모킹
        when(settlementRepository.findById(1L)).thenReturn(Optional.of(record1));

        // record2 정산 처리를 위한 모킹
        when(settlementRepository.findById(2L)).thenReturn(Optional.of(record2));
        Account virtualAccount = buildAccount("TOSS-VIRTUAL-001", 1_000_000L);
        Account merchantAccount2 = buildAccount("MERCHANT-002", 0L);
        when(accountRepository.findById(100L)).thenReturn(Optional.of(virtualAccount));
        when(accountRepository.findById(3L)).thenReturn(Optional.of(merchantAccount2));
        PaymentTransaction paymentTx2 = mock(PaymentTransaction.class);
        when(transactionRepository.findById(20L)).thenReturn(Optional.of(paymentTx2));

        // when
        settlementService.processAllPendingSettlements();

        // then: record1 - 실패 사유 기록, PENDING 유지 (다음 정산 주기 자동 재시도 설계)
        assertThat(record1.getFailureReason()).isNotNull();
        assertThat(record1.getStatus()).isEqualTo(SettlementStatus.PENDING);

        // then: record2 - 정상 정산 완료
        assertThat(record2.getStatus()).isEqualTo(SettlementStatus.SETTLED);
        verify(paymentTx2).markSuccess();
    }

    // ================================================================
    // 시나리오 5: 정산 예정일 필터 검증 (D+1/D+2 설계 일치)
    // ================================================================

    @Test
    @DisplayName("정산 배치 - expectedSettlementDate <= 오늘인 건만 처리 (D+2 건은 내일 배치에서 처리)")
    void processAllPendingSettlements_onlySettlesRecordsDueToday() {
        // given: 오늘 처리 대상 D+1 건
        SettlementRecord dueToday = buildTestSettlementRecord(1L, 1L, 100L, 2L);
        ReflectionTestUtils.setField(dueToday, "expectedSettlementDate", LocalDate.now());

        // D+2 건: 정산 예정일이 내일 → 오늘 배치에서 제외 대상
        SettlementRecord dueTomorrow = buildTestSettlementRecord(2L, 2L, 100L, 3L);
        ReflectionTestUtils.setField(dueTomorrow, "expectedSettlementDate", LocalDate.now().plusDays(1));

        // processAllPendingSettlements는 날짜 조건 쿼리 사용
        when(settlementRepository.findByStatusAndExpectedSettlementDateLessThanEqual(
                eq(SettlementStatus.PENDING), any(LocalDate.class)))
                .thenReturn(List.of(dueToday));  // dueToday만 반환 (D+2 건 제외됨)

        when(settlementRepository.findById(1L)).thenReturn(Optional.of(dueToday));
        Account virtualAccount = buildAccount("TOSS-VIRTUAL-001", 1_000_000L);
        Account merchantAccount = buildAccount("MERCHANT-001", 0L);
        when(accountRepository.findById(100L)).thenReturn(Optional.of(virtualAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(merchantAccount));
        PaymentTransaction paymentTx = mock(PaymentTransaction.class);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(paymentTx));

        // when
        settlementService.processAllPendingSettlements();

        // then: D+1 건(dueToday)만 정산됨
        assertThat(dueToday.getStatus()).isEqualTo(SettlementStatus.SETTLED);

        // D+2 건(dueTomorrow)은 쿼리에서 제외됐으므로 여전히 PENDING
        assertThat(dueTomorrow.getStatus()).isEqualTo(SettlementStatus.PENDING);

        // [핵심 검증] findByStatusAndExpectedSettlementDateLessThanEqual 호출 확인
        verify(settlementRepository).findByStatusAndExpectedSettlementDateLessThanEqual(
                eq(SettlementStatus.PENDING), eq(LocalDate.now()));
    }

    // ================================================================
    // 헬퍼 메서드
    // ================================================================

    /**
     * 테스트용 SettlementRecord 생성 헬퍼
     * - 100,000원 결제, 수수료 3.5% + VAT 포함 기준으로 계산 (netAmount = 96,150)
     */
    private SettlementRecord buildTestSettlementRecord(Long id, Long paymentTxId,
                                                        Long virtualAccountId, Long merchantAccountId) {
        MerchantSettlementPolicy policy = MerchantSettlementPolicy.create(
                merchantAccountId, new BigDecimal("0.0350"), true, 1);
        SettlementAmountResult calculation = new SettlementCalculator().calculate(100_000L, policy);
        SettlementRecord record = SettlementRecord.createPending(
                paymentTxId, virtualAccountId, merchantAccountId, calculation);
        ReflectionTestUtils.setField(record, "id", id);
        return record;
    }

    /** 테스트용 Account 생성 헬퍼 */
    private Account buildAccount(String accountNumber, long balance) {
        return Account.builder()
                .accountNumber(accountNumber)
                .ownerName("테스트계좌")
                .balance(balance)
                .build();
    }
}
