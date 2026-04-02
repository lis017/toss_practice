package com.toss.cashback.domain.payment.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

// ======= [신규] 정산 금액 계산 결과 (Value Object) =======
/**
 * SettlementCalculator.calculate()의 결과를 담는 불변 값 객체.
 * JPA 엔티티가 아니며 SettlementRecord 생성 시 인자로 전달됩니다.
 *
 * 금액 단위: 원(KRW), long 타입 사용 이유:
 * - 최대 결제 금액 수십억 원 → int 오버플로 위험 → long 사용
 * - DB 계산은 BigDecimal로 처리 후 long으로 저장 (소수점 없는 원 단위)
 */
@Getter
@RequiredArgsConstructor
public class SettlementAmountResult {

    private final long grossAmount;             // 결제 원금 (구매자가 낸 금액)
    private final long feeAmount;              // 수수료 금액 (grossAmount × feeRate)
    private final long vatAmount;              // 부가세 금액 (vatIncluded=false면 0)
    private final long netAmount;              // 실제 정산 금액 (grossAmount - feeAmount - vatAmount)
    private final LocalDate expectedSettlementDate;  // 정산 예정일 (오늘 + settlementCycleDays)
}
