package com.toss.cashback.domain.settlement.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

// ======= [6번] 정산 금액 계산 결과 (Value Object) =======
/**
 * SettlementCalculator.calculate()의 결과를 담는 불변 값 객체.
 *
 * 금액 단위: 원(KRW), long 타입 사용 이유:
 * - 최대 결제 금액 수십억 원 → int 오버플로 위험 → long 사용
 * - DB 계산은 BigDecimal로 처리 후 long으로 저장 (소수점 없는 원 단위)
 *
 * [정책 스냅샷]
 * appliedFeeRate, vatIncluded는 계산 당시의 정산 정책 값을 그대로 보존합니다.
 * SettlementRecord에 함께 저장하여, 이후 정책이 변경되더라도 당시 조건을 역추적할 수 있습니다.
 * (참고: 토스페이먼츠 정산 개편기 - 설정 정보 스냅샷 패턴)
 */
@Getter
@RequiredArgsConstructor
public class SettlementAmountResult {

    private final long grossAmount;                  // 결제 원금 (구매자가 낸 금액)
    private final long feeAmount;                    // 수수료 금액 (grossAmount × feeRate)
    private final long vatAmount;                    // 부가세 금액 (vatIncluded=false면 0)
    private final long netAmount;                    // 실제 정산 금액 (grossAmount - feeAmount - vatAmount)
    private final LocalDate expectedSettlementDate;  // 정산 예정일 (오늘 + settlementCycleDays)

    // ============================================================
    // 정책 스냅샷: 계산에 사용된 정책 값을 그대로 보존
    // 정산 후 수수료율이 변경되어도 당시 조건 역추적 가능
    // ============================================================
    private final BigDecimal appliedFeeRate;         // 적용된 수수료율 (예: 0.0350)
    private final boolean vatIncluded;               // 적용된 VAT 포함 여부
}
