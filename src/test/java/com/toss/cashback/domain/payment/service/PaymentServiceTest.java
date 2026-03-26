package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.account.entity.Account;
import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.payment.repository.PaymentTransactionRepository;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * =====================================================================
 * PaymentService 단위 테스트 (Mockito)
 * =====================================================================
 *
 * 테스트 전략:
 * - Spring Context 없이 순수 단위 테스트 (빠른 실행)
 * - Repository를 Mock으로 대체 → 실제 DB 의존성 제거
 *
 * 커버리지:
 * - 정상 이체 / 역순 ID 이체
 * - 잔액 부족 / 동일 계좌 / 미존재 계좌 / 0원 / 음수 금액 예외
 * - 보상 트랜잭션 정상 동작 (계좌 원복 확인)
 * =====================================================================
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService 단위 테스트")
class PaymentServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PaymentTransactionRepository transactionRepository;

    @InjectMocks
    private PaymentService paymentService;

    private Account senderAccount;
    private Account receiverAccount;

    @BeforeEach
    void setUp() {
        senderAccount = Account.builder()
                .accountNumber("110-1234-5678")
                .ownerName("김철수")
                .balance(1_000_000L)            // 잔액 100만원
                .build();

        receiverAccount = Account.builder()
                .accountNumber("220-9876-5432")
                .ownerName("토스상점")
                .balance(0L)
                .build();
    }

    // ================================================================
    // 정상 케이스
    // ================================================================

    @Test
    @DisplayName("정상 이체 - 잔액 충분한 경우 이체 성공 및 잔액 변경 확인")
    void executeTransfer_success_whenBalanceSufficient() {
        // given
        when(accountRepository.findById(1L)).thenReturn(Optional.of(senderAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(receiverAccount));

        PaymentTransaction mockTx = mock(PaymentTransaction.class);
        when(mockTx.getId()).thenReturn(1L);
        when(transactionRepository.save(any())).thenReturn(mockTx);

        // when
        Long txId = paymentService.executeTransfer(1L, 2L, 100_000L);

        // then
        assertThat(txId).isEqualTo(1L);
        assertThat(senderAccount.getBalance()).isEqualTo(900_000L);     // 100만 - 10만 = 90만
        assertThat(receiverAccount.getBalance()).isEqualTo(100_000L);   // 0 + 10만 = 10만
    }

    @Test
    @DisplayName("역순 ID 이체 - fromId > toId 케이스도 정상 이체")
    void executeTransfer_success_reverseIdOrder() {
        // given: fromId=2(큰), toId=1(작음)
        when(accountRepository.findById(2L)).thenReturn(Optional.of(senderAccount));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(receiverAccount));

        PaymentTransaction mockTx = mock(PaymentTransaction.class);
        when(mockTx.getId()).thenReturn(1L);
        when(transactionRepository.save(any())).thenReturn(mockTx);

        // when
        Long txId = paymentService.executeTransfer(2L, 1L, 100_000L);

        // then
        assertThat(txId).isNotNull();
        assertThat(senderAccount.getBalance()).isEqualTo(900_000L);
    }

    // ================================================================
    // 예외 케이스
    // ================================================================

    @Test
    @DisplayName("잔액 부족 - INSUFFICIENT_BALANCE 예외 + 잔액 변경 없음")
    void executeTransfer_fail_insufficientBalance() {
        // given: 잔액 100만인데 200만 이체 시도
        when(accountRepository.findById(1L)).thenReturn(Optional.of(senderAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(receiverAccount));

        // when & then
        assertThatThrownBy(() -> paymentService.executeTransfer(1L, 2L, 2_000_000L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

        assertThat(senderAccount.getBalance()).isEqualTo(1_000_000L);   // 잔액 변경 없음
    }

    @Test
    @DisplayName("동일 계좌 이체 - SAME_ACCOUNT_TRANSFER 예외 + DB 조회 없음")
    void executeTransfer_fail_sameAccount() {
        assertThatThrownBy(() -> paymentService.executeTransfer(1L, 1L, 10_000L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SAME_ACCOUNT_TRANSFER);

        // 동일 계좌 체크에서 바로 예외 → DB 조회 없음
        verify(accountRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("존재하지 않는 계좌 - ACCOUNT_NOT_FOUND 예외")
    void executeTransfer_fail_accountNotFound() {
        when(accountRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.executeTransfer(1L, 2L, 10_000L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("0원 이체 - INVALID_AMOUNT 예외")
    void executeTransfer_fail_zeroAmount() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(senderAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(receiverAccount));

        assertThatThrownBy(() -> paymentService.executeTransfer(1L, 2L, 0L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("음수 금액 이체 - INVALID_AMOUNT 예외")
    void executeTransfer_fail_negativeAmount() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(senderAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(receiverAccount));

        assertThatThrownBy(() -> paymentService.executeTransfer(1L, 2L, -10_000L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    // ================================================================
    // 보상 트랜잭션 테스트
    // ================================================================

    @Test
    @DisplayName("보상 트랜잭션 - 이체 후 외부 API 실패 시 계좌 잔액 원복 확인")
    void compensate_success_restoresBalances() {
        // given: 이체 완료된 상태 (sender: 90만, receiver: 10만)
        Account compensateSender = Account.builder()
                .accountNumber("110-1111-1111").ownerName("김철수").balance(900_000L).build();
        Account compensateReceiver = Account.builder()
                .accountNumber("220-2222-2222").ownerName("상점").balance(100_000L).build();

        PaymentTransaction mockTx = mock(PaymentTransaction.class);
        when(mockTx.getFromAccountId()).thenReturn(1L);
        when(mockTx.getToAccountId()).thenReturn(2L);
        when(mockTx.getAmount()).thenReturn(100_000L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(mockTx));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(compensateSender));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(compensateReceiver));

        // when
        paymentService.compensate(1L);

        // then: 잔액 원복 확인
        assertThat(compensateSender.getBalance()).isEqualTo(1_000_000L);    // 90만 + 10만 = 100만
        assertThat(compensateReceiver.getBalance()).isEqualTo(0L);           // 10만 - 10만 = 0원
        verify(mockTx).markCompensated(anyString());                        // COMPENSATED 상태 변경 확인
    }
}
