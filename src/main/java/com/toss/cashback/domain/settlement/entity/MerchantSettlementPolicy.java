package com.toss.cashback.domain.settlement.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// ======= [6번] 가맹점 정산 정책 엔티티 =======
/**
 * =====================================================================
 * [설계 의도] 가맹점별 정산 정책 - 수수료/VAT/정산주기 관리
 * =====================================================================
 *
 * 실제 PG(토스페이먼츠) 정산 정책:
 * - 가맹점 업종/매출 규모에 따라 협의된 수수료율이 다름
 * - 영세 가맹점 우대 수수료 적용 (여신금융업법 규제)
 * - 부가세는 수수료 금액의 10% 별도 부과 (부가가치세법)
 * - 정산 주기: 일반 가맹점 D+2, 우대 가맹점 D+1 (협의에 따라 다름)
 *
 * 수수료 계산 규칙:
 * - feeAmount  = grossAmount × feeRate   (HALF_EVEN 반올림 - SettlementCalculator 참조)
 * - vatAmount  = feeAmount  × 0.10       (vatIncluded=true인 경우만)
 * - netAmount  = grossAmount - feeAmount - vatAmount
 *
 * 기본 정책 (정책 미등록 가맹점):
 * - feeRate = 3.5%, vatIncluded = true, settlementCycleDays = 1 (D+1)
 *
 * 추후 리팩터:
 * - 정책 변경 이력 테이블 (PolicyHistory) 분리 → 감사 추적
 * - 유효 기간(validFrom/validUntil) 추가 → 기간별 정책 적용
 * =====================================================================
 */
@Entity
@Table(
    name = "merchant_settlement_policies",
    indexes = {
        @Index(name = "idx_policy_merchant_account", columnList = "merchantAccountId", unique = true)
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantSettlementPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long merchantAccountId;         // 가맹점 계좌 ID (정책 조회 키)

    // precision=5, scale=4 → 최대 9.9999 (99.99%) 까지 표현 가능
    // 실무 범위 0.0000 ~ 0.0500 (0% ~ 5%) 충분히 커버
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal feeRate;             // 수수료율 (예: 0.0350 = 3.5%)

    @Column(nullable = false)
    private boolean vatIncluded;            // 수수료에 부가세(10%) 추가 부과 여부

    @Column(nullable = false)
    private int settlementCycleDays;        // 정산 주기 일수 (1=D+1, 2=D+2)

    @Column(length = 500)
    private String webhookUrl;              // 결제 완료 알림 수신 URL (null이면 웹훅 미발송)

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * 가맹점 정산 정책 생성
     *
     * @param merchantAccountId   가맹점 계좌 ID
     * @param feeRate             수수료율 (예: new BigDecimal("0.0350") = 3.5%)
     * @param vatIncluded         부가세 부과 여부 (true면 수수료의 10% 추가)
     * @param settlementCycleDays 정산 주기 (1 또는 2)
     */
    public static MerchantSettlementPolicy create(Long merchantAccountId, BigDecimal feeRate,
                                                   boolean vatIncluded, int settlementCycleDays) {
        MerchantSettlementPolicy policy = new MerchantSettlementPolicy();
        policy.merchantAccountId = merchantAccountId;
        policy.feeRate = feeRate;
        policy.vatIncluded = vatIncluded;
        policy.settlementCycleDays = settlementCycleDays;
        return policy;
    }

    /**
     * 기본 정책 (정책이 등록되지 않은 가맹점에 자동 적용)
     * - 수수료 3.5%, 부가세 포함, D+1 정산
     */
    public static MerchantSettlementPolicy createDefault(Long merchantAccountId) {
        return create(merchantAccountId, new BigDecimal("0.0350"), true, 1);
    }

    /** 가맹점 웹훅 URL 등록/변경 */
    public void updateWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }
}
