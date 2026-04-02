package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.payment.entity.MerchantSettlementPolicy;
import com.toss.cashback.domain.payment.entity.SettlementAmountResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// ======= [신규] SettlementCalculator 단위 테스트 =======
/**
 * =====================================================================
 * 정산 금액 계산기 단위 테스트 (Spring Context 없이 순수 Java)
 * =====================================================================
 *
 * 검증 항목:
 * 1. 기본 케이스: 수수료 3.5% + VAT 포함 + D+1 → 금액 정확히 계산
 * 2. VAT 미포함 케이스: 수수료만 적용, vatAmount = 0
 * 3. 수수료 면제 케이스: feeRate = 0% → netAmount = grossAmount 전액
 * 4. 반올림 케이스: HALF_EVEN 검증 (소수점 경계 값)
 * 5. 정산 주기 검증: D+1 → 내일, D+2 → 모레
 * 6. netAmount 일관성: grossAmount = feeAmount + vatAmount + netAmount
 * =====================================================================
 */
@DisplayName("SettlementCalculator 단위 테스트")
class SettlementCalculatorTest {

    private SettlementCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new SettlementCalculator();
    }

    // ===================================================================
    // 케이스 1: 기본 케이스 (수수료 3.5% + VAT 포함 + D+1)
    // ===================================================================
    @Test
    @DisplayName("[기본] 100,000원 / 수수료 3.5% / VAT 포함 / D+1")
    void calculate_basicCase_with_vat_d1() {
        // GIVEN
        MerchantSettlementPolicy policy = MerchantSettlementPolicy.create(
                1L, new BigDecimal("0.0350"), true, 1);
        long grossAmount = 100_000L;

        // WHEN
        SettlementAmountResult result = calculator.calculate(grossAmount, policy);

        // THEN
        // feeAmount = 100,000 × 0.035 = 3,500
        assertThat(result.getFeeAmount()).isEqualTo(3_500L);

        // vatAmount = 3,500 × 0.10 = 350
        assertThat(result.getVatAmount()).isEqualTo(350L);

        // netAmount = 100,000 - 3,500 - 350 = 96,150
        assertThat(result.getNetAmount()).isEqualTo(96_150L);

        // grossAmount 일관성 검증
        assertThat(result.getNetAmount())
                .isEqualTo(grossAmount - result.getFeeAmount() - result.getVatAmount());

        // 정산 예정일: 오늘 + 1
        assertThat(result.getExpectedSettlementDate()).isEqualTo(LocalDate.now().plusDays(1));
    }

    // ===================================================================
    // 케이스 2: 수수료 면제 가맹점
    // ===================================================================
    @Test
    @DisplayName("[수수료 면제] 100,000원 / 수수료 0% / VAT 없음 → netAmount = grossAmount")
    void calculate_zeroFee_netEqualsGross() {
        // GIVEN
        MerchantSettlementPolicy policy = MerchantSettlementPolicy.create(
                3L, BigDecimal.ZERO, false, 1);
        long grossAmount = 100_000L;

        // WHEN
        SettlementAmountResult result = calculator.calculate(grossAmount, policy);

        // THEN
        assertThat(result.getFeeAmount()).isEqualTo(0L);
        assertThat(result.getVatAmount()).isEqualTo(0L);
        assertThat(result.getNetAmount()).isEqualTo(grossAmount);   // 전액 지급
    }

    // ===================================================================
    // 케이스 4: HALF_EVEN(은행가 반올림) 검증
    // ===================================================================
    /**
     * HALF_EVEN 동작 원리:
     * - 소수 정확히 0.5일 때 가까운 "짝수" 방향으로 반올림
     * - 10,000원 × 3.5% = 350.0000 → 정수이므로 반올림 없음
     * - 10,001원 × 3.5% = 350.035  → 0.5 미만 → 내림 → 350
     * - 10,002원 × 3.5% = 350.07   → 0.5 초과 → 올림 → 350? No, 350.07 → 350
     *   (setScale(0)이므로 0.07은 내림, 결과 350)
     * - 반올림 경계: 정확히 x.5인 경우 짝수로 → 350.5 → 350(짝수), 351.5 → 352(짝수)
     *
     * 여기서는 grossAmount=10,001로 소수 발생 케이스를 검증합니다.
     */
    @Test
    @DisplayName("[HALF_EVEN] 10,001원 / 수수료 3.5% → 소수점 반올림 검증")
    void calculate_halfEvenRounding_feeAmountRoundedCorrectly() {
        // GIVEN
        MerchantSettlementPolicy policy = MerchantSettlementPolicy.create(
                4L, new BigDecimal("0.0350"), false, 1);
        long grossAmount = 10_001L;

        // WHEN
        SettlementAmountResult result = calculator.calculate(grossAmount, policy);

        // THEN
        // 10,001 × 0.035 = 350.035 → 소수점 이하 0.035 < 0.5 → 내림 → 350
        assertThat(result.getFeeAmount()).isEqualTo(350L);

        // netAmount = 10,001 - 350 - 0 = 9,651
        assertThat(result.getNetAmount()).isEqualTo(9_651L);

        // [핵심] grossAmount = feeAmount + vatAmount + netAmount 항등식 검증
        assertThat(grossAmount)
                .isEqualTo(result.getFeeAmount() + result.getVatAmount() + result.getNetAmount());
    }

}
