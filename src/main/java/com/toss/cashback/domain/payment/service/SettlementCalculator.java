package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.payment.entity.MerchantSettlementPolicy;
import com.toss.cashback.domain.payment.entity.SettlementAmountResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

// ======= [신규] 정산 금액 계산기 =======
/**
 * =====================================================================
 * [설계 의도] 정산 금액 계산 - 수수료 / 부가세 / 실지급액
 * =====================================================================
 *
 * 계산 규칙:
 *   feeAmount  = grossAmount × feeRate   (HALF_EVEN 반올림)
 *   vatAmount  = feeAmount  × 0.10       (vatIncluded=true 일 때만, HALF_EVEN 반올림)
 *   netAmount  = grossAmount - feeAmount - vatAmount
 *   expectedSettlementDate = 오늘 + settlementCycleDays
 *
 * HALF_EVEN(은행가 반올림, Banker's Rounding) 선택 이유:
 *   - 소수점 정확히 0.5인 경계를 "가까운 짝수" 방향으로 반올림
 *   - 예) 3500.5원 → 3500원 (짝수), 3501.5원 → 3502원 (짝수)
 *   - 대량 정산 건 처리 시 반올림 오차가 위/아래로 균등하게 상쇄됨
 *   - 금융권 표준(IEEE 754) 및 회계 관련 시스템에서 권장
 *   - HALF_UP(일반 반올림)은 항상 올림이라 PG가 손해 or 가맹점이 손해
 *
 * netAmount 음수 방지:
 *   - feeRate가 100%를 넘지 않는 한 발생하지 않음
 *   - MerchantSettlementPolicy의 feeRate 최대값은 DB 컬럼 제약(precision=5,scale=4)으로 9.9999
 *   - 실무 수수료율 상한 5% 이하이므로 netAmount 음수 불가
 * =====================================================================
 */
@Slf4j
@Component
public class SettlementCalculator {

    // 부가세율 10% (부가가치세법 고정)
    private static final BigDecimal VAT_RATE = new BigDecimal("0.10");

    /**
     * 정산 금액 계산
     *
     * @param grossAmount 결제 원금 (구매자가 낸 전체 금액)
     * @param policy      가맹점 정산 정책 (수수료율, VAT 여부, 정산 주기)
     * @return 계산 결과 (feeAmount, vatAmount, netAmount, expectedSettlementDate)
     */
    public SettlementAmountResult calculate(long grossAmount, MerchantSettlementPolicy policy) {
        BigDecimal gross = BigDecimal.valueOf(grossAmount);

        // 수수료 계산: 원 단위 절사 기준 HALF_EVEN
        BigDecimal fee = gross
                .multiply(policy.getFeeRate())
                .setScale(0, RoundingMode.HALF_EVEN);

        // 부가세 계산: vatIncluded=false면 0 (부가세 면제 가맹점)
        BigDecimal vat = policy.isVatIncluded()
                ? fee.multiply(VAT_RATE).setScale(0, RoundingMode.HALF_EVEN)
                : BigDecimal.ZERO;

        // 실지급액 = 원금 - 수수료 - 부가세
        long netAmount = gross.subtract(fee).subtract(vat).longValue();

        // 정산 예정일 = 오늘 + 정산 주기
        LocalDate expectedDate = LocalDate.now().plusDays(policy.getSettlementCycleDays());

        log.debug("[정산 계산] merchantId={}, gross={}, fee={}, vat={}, net={}, expectedDate={}, D+{}",
                policy.getMerchantAccountId(), grossAmount, fee.longValue(), vat.longValue(),
                netAmount, expectedDate, policy.getSettlementCycleDays());

        return new SettlementAmountResult(
                grossAmount,
                fee.longValue(),
                vat.longValue(),
                netAmount,
                expectedDate,
                policy.getFeeRate(),        // 정책 스냅샷: 계산에 사용된 수수료율 그대로 보존
                policy.isVatIncluded()      // 정책 스냅샷: 계산에 사용된 VAT 여부 그대로 보존
        );
    }
}
