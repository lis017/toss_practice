package com.toss.cashback.domain.account.entity;

import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ======= [4번] Account 도메인 단위 테스트 =======
/**
 * =====================================================================
 * Account Entity 단위 테스트 - 도메인 모델 비즈니스 규칙 검증
 * =====================================================================
 *
 * 테스트 전략:
 * - Spring Context 없이 순수 Java 테스트 (가장 빠른 실행)
 * - Account 도메인 메서드(withdraw, deposit)의 비즈니스 규칙 집중 검증
 *
 * 검증 항목:
 * - 정상 출금: 잔액 차감 확인
 * - 잔액 부족 출금: INSUFFICIENT_BALANCE 예외 + 잔액 불변 확인
 * - 0원/음수 출금: INVALID_AMOUNT 예외
 * - 정상 입금: 잔액 증가 확인
 * - 0원/음수 입금: INVALID_AMOUNT 예외
 * =====================================================================
 */
@DisplayName("Account 도메인 단위 테스트")
class AccountTest {

    private Account account;

    @BeforeEach
    void setUp() {
        account = Account.builder()
                .accountNumber("110-1234-5678")
                .ownerName("김철수")
                .balance(100_000L)
                .build();
    }

    // ================================================================
    // 출금 (withdraw) 케이스
    // ================================================================

    @Test
    @DisplayName("출금 정상 - 잔액 차감 확인")
    void withdraw_success_balanceDecreased() {
        account.withdraw(30_000L);

        assertThat(account.getBalance()).isEqualTo(70_000L);
    }

    @Test
    @DisplayName("출금 - 잔액 전액 출금 성공 (경계값)")
    void withdraw_success_exactBalance() {
        account.withdraw(100_000L);    // 잔액과 동일한 금액

        assertThat(account.getBalance()).isEqualTo(0L);
    }

    @Test
    @DisplayName("출금 - 잔액 부족 시 INSUFFICIENT_BALANCE 예외, 잔액 변경 없음")
    void withdraw_fail_insufficientBalance_balanceUnchanged() {
        assertThatThrownBy(() -> account.withdraw(200_000L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);

        // [핵심] 예외 발생 시 잔액이 변경되지 않아야 함 (원자성 보장)
        assertThat(account.getBalance()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("출금 - 0원 INVALID_AMOUNT 예외")
    void withdraw_fail_zeroAmount() {
        assertThatThrownBy(() -> account.withdraw(0L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("출금 - 음수 금액 INVALID_AMOUNT 예외")
    void withdraw_fail_negativeAmount() {
        assertThatThrownBy(() -> account.withdraw(-1_000L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("출금 - null 금액 INVALID_AMOUNT 예외")
    void withdraw_fail_nullAmount() {
        assertThatThrownBy(() -> account.withdraw(null))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    // ================================================================
    // 입금 (deposit) 케이스
    // ================================================================

    @Test
    @DisplayName("입금 정상 - 잔액 증가 확인")
    void deposit_success_balanceIncreased() {
        account.deposit(50_000L);

        assertThat(account.getBalance()).isEqualTo(150_000L);
    }

    @Test
    @DisplayName("입금 - 0원 INVALID_AMOUNT 예외")
    void deposit_fail_zeroAmount() {
        assertThatThrownBy(() -> account.deposit(0L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("입금 - 음수 금액 INVALID_AMOUNT 예외")
    void deposit_fail_negativeAmount() {
        assertThatThrownBy(() -> account.deposit(-5_000L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }
}
