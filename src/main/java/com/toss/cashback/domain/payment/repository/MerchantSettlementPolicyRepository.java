package com.toss.cashback.domain.payment.repository;

import com.toss.cashback.domain.payment.entity.MerchantSettlementPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// ======= [신규] 가맹점 정산 정책 레포지토리 =======
/**
 * 가맹점 정산 정책 리포지토리.
 * merchantAccountId 기준으로 정책을 조회합니다.
 * 정책 미등록 가맹점은 SettlementCalculator에서 기본 정책(3.5% / VAT / D+1)으로 처리됩니다.
 */
public interface MerchantSettlementPolicyRepository extends JpaRepository<MerchantSettlementPolicy, Long> {

    /** 가맹점 계좌 ID로 정산 정책 조회 (없으면 기본 정책 적용) */
    Optional<MerchantSettlementPolicy> findByMerchantAccountId(Long merchantAccountId);
}
