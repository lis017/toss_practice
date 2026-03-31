package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.account.entity.Account;
import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.payment.repository.PaymentTransactionRepository;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import com.toss.cashback.infrastructure.redis.RedissonLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// ======= [5번] 계좌 이체 서비스 단위 테스트 =======
/**
 * =====================================================================
 * PaymentService 단위 테스트 (Mockito)
 * =====================================================================
 *
 * 테스트 전략:
 * - Spring Context 없이 순수 단위 테스트 (빠른 실행)
 * - Repository, RedissonLockService, TransactionTemplate을 Mock으로 대체
 * - 분산 락과 트랜잭션은 pass-through mock → 비즈니스 로직만 집중 검증
 *
 * C안 기준 executeTransfer 파라미터:
 * - fromAccountId   = 구매자 계좌 ID (A - 실제 출금 대상)
 * - merchantAccountId = 가맹점 계좌 ID (B - 기록용, 정산 수신처)
 * - virtualAccountId = 가상계좌 ID (C - 실제 입금 대상)
 * - amount
 *
 * 커버리지:
 * - 정상 이체 / 역순 ID 이체 (MultiLock 락 정렬 검증)
 * - 잔액 부족 / 동일 계좌 / 미존재 계좌 / 0원 / 음수 금액 예외
 * =====================================================================
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService 단위 테스트")
class PaymentServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PaymentTransactionRepository transactionRepository;

    @Mock
    private RedissonLockService redissonLockService;    // 실제 Redis 불필요 - pass-through

    @Mock
    private TransactionTemplate transactionTemplate;    // 실제 트랜잭션 불필요 - pass-through

    @InjectMocks
    private PaymentService paymentService;

    private Account buyerAccount;       // 구매자 계좌 (A, ID=1)
    private Account merchantAccount;    // 가맹점 계좌 (B, ID=2)
    private Account virtualAccount;     // 가상계좌 (C, ID=3)

    @BeforeEach
    void setUp() throws Exception {
        buyerAccount = Account.builder()
                .accountNumber("110-1234-5678")
                .ownerName("김철수")
                .balance(1_000_000L)            // 잔액 100만원
                .build();

        merchantAccount = Account.builder()
                .accountNumber("220-9876-5432")
                .ownerName("토스상점")
                .balance(0L)
                .build();

        virtualAccount = Account.builder()
                .accountNumber("TOSS-VIRTUAL-001")
                .ownerName("토스 가상계좌")
                .balance(0L)
                .build();

        // 분산 락 mock: 락 없이 task 바로 실행 (비즈니스 로직만 검증)
        // lenient: sameAccount 테스트처럼 락까지 도달하지 않는 케이스에서 미사용 경고 방지
        lenient().when(redissonLockService.executeWithMultiLock(anyList(), anyLong(), anyLong(), any()))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(3)).call());

        // TransactionTemplate mock: 트랜잭션 없이 콜백 바로 실행
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> {
                    org.springframework.transaction.support.TransactionCallback<?> callback =
                            inv.getArgument(0);
                    return callback.doInTransaction(
                            mock(org.springframework.transaction.TransactionStatus.class));
                });
    }

    // ================================================================
    // 정상 케이스
    // ================================================================

    @Test
    @DisplayName("정상 이체 - 구매자(A) 잔액 차감 + 가상계좌(C) 잔액 증가 확인")
    void executeTransfer_success_whenBalanceSufficient() {
        // given: from=1L(구매자), merchant=2L(가맹점-기록용), virtual=3L(실제 입금)
        when(accountRepository.findById(1L)).thenReturn(Optional.of(buyerAccount));
        when(accountRepository.findById(3L)).thenReturn(Optional.of(virtualAccount));

        PaymentTransaction mockTx = mock(PaymentTransaction.class);
        when(mockTx.getId()).thenReturn(1L);
        when(transactionRepository.save(any())).thenReturn(mockTx);

        // when
        Long txId = paymentService.executeTransfer(1L, 2L, 3L, 100_000L);

        // then: 구매자 차감, 가상계좌 입금
        assertThat(txId).isEqualTo(1L);
        assertThat(buyerAccount.getBalance()).isEqualTo(900_000L);    // 100만 - 10만 = 90만
        assertThat(virtualAccount.getBalance()).isEqualTo(100_000L);  // 0 + 10만 = 10만 (가상계좌 보관)
    }

    @Test
    @DisplayName("역순 ID 이체 - from > virtual ID 케이스도 정상 이체 (MultiLock 정렬 처리)")
    void executeTransfer_success_reverseIdOrder() {
        // given: fromId=3(큰), virtualId=1(작음) → 락은 항상 낮은 ID부터 획득
        when(accountRepository.findById(3L)).thenReturn(Optional.of(buyerAccount));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(virtualAccount));

        PaymentTransaction mockTx = mock(PaymentTransaction.class);
        when(mockTx.getId()).thenReturn(1L);
        when(transactionRepository.save(any())).thenReturn(mockTx);

        // when: from=3L(구매자), merchant=2L(가맹점-기록용), virtual=1L
        Long txId = paymentService.executeTransfer(3L, 2L, 1L, 100_000L);

        // then
        assertThat(txId).isNotNull();
        assertThat(buyerAccount.getBalance()).isEqualTo(900_000L);
    }

    // ================================================================
    // 예외 케이스
    // ================================================================

    @Test
    @DisplayName("잔액 부족 - INSUFFICIENT_BALANCE 예외 + 잔액 변경 없음")
    void executeTransfer_fail_insufficientBalance() {
        // given: 잔액 100만인데 200만 이체 시도
        when(accountRepository.findById(1L)).thenReturn(Optional.of(buyerAccount));
        lenient().when(accountRepository.findById(3L)).thenReturn(Optional.of(virtualAccount));

        // when & then
        assertThatThrownBy(() -> paymentService.executeTransfer(1L, 2L, 3L, 2_000_000L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

        assertThat(buyerAccount.getBalance()).isEqualTo(1_000_000L);   // 잔액 변경 없음
    }

    @Test
    @DisplayName("동일 계좌 이체 - SAME_ACCOUNT_TRANSFER 예외 + 락/DB 조회 없음")
    void executeTransfer_fail_sameAccount() {
        // from=1L, merchant=1L 동일 → 자기 자신에게 결제 불가
        assertThatThrownBy(() -> paymentService.executeTransfer(1L, 1L, 3L, 10_000L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SAME_ACCOUNT_TRANSFER);

        // 동일 계좌 체크에서 바로 예외 → 락 획득/DB 조회 없음
        verify(accountRepository, never()).findById(anyLong());
        verify(redissonLockService, never()).executeWithMultiLock(anyList(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("존재하지 않는 계좌 - ACCOUNT_NOT_FOUND 예외")
    void executeTransfer_fail_accountNotFound() {
        when(accountRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.executeTransfer(1L, 2L, 3L, 10_000L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("0원 이체 - INVALID_AMOUNT 예외")
    void executeTransfer_fail_zeroAmount() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(buyerAccount));
        lenient().when(accountRepository.findById(3L)).thenReturn(Optional.of(virtualAccount));

        assertThatThrownBy(() -> paymentService.executeTransfer(1L, 2L, 3L, 0L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("음수 금액 이체 - INVALID_AMOUNT 예외")
    void executeTransfer_fail_negativeAmount() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(buyerAccount));
        lenient().when(accountRepository.findById(3L)).thenReturn(Optional.of(virtualAccount));

        assertThatThrownBy(() -> paymentService.executeTransfer(1L, 2L, 3L, -10_000L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }
}
