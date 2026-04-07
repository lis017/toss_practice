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
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import com.toss.cashback.infrastructure.api.ExternalBankService;
import com.toss.cashback.infrastructure.redis.RedissonLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;

// ======= [11번] 정산 서비스 =======
/**
 * 두 가지 역할:
 * 1. createSettlementRecord() - 결제 완료 시 정산 레코드 생성 (PaymentFacade 호출)
 * 2. processAllPendingSettlements() - PENDING 건 일괄 정산 (SettlementScheduler 호출)
 *
 * 실패한 정산 건은 PENDING 상태로 유지 → 다음 정산 주기에 자동 재시도
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final MerchantSettlementPolicyRepository policyRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final ExternalBankService externalBankService;
    private final RedissonLockService redissonLockService;
    private final TransactionTemplate transactionTemplate;
    private final SettlementCalculator settlementCalculator;

    @Value("${lock.account.wait-seconds:5}")
    private long lockWaitSeconds;

    @Value("${lock.account.lease-seconds:30}")
    private long lockLeaseSeconds;

    /**
     * 결제 완료 시 정산 레코드 생성 (PENDING 상태)
     *
     * 가맹점 정산 정책 조회 → 수수료/VAT/실지급액 계산 → SettlementRecord 저장
     * 정책 미등록 가맹점은 기본 정책(3.5% / VAT포함 / D+1) 자동 적용
     *
     * [정책 스냅샷 설계]
     * 계산에 사용된 feeRate, vatIncluded 값을 SettlementRecord에 함께 저장합니다.
     * 이후 가맹점 수수료율이 변경되어도 당시 정산 조건을 DB에서 바로 역추적할 수 있습니다.
     * (참고: 토스페이먼츠 정산 개편기 - https://toss.tech/article/payments-legacy-6)
     */
    @Transactional
    public SettlementRecord createSettlementRecord(Long paymentTransactionId, Long virtualAccountId,
                                                    Long merchantAccountId, Long grossAmount) {
        MerchantSettlementPolicy policy = policyRepository
                .findByMerchantAccountId(merchantAccountId)
                .orElseGet(() -> {
                    log.info("[정산] 미등록 가맹점 기본 정책 적용 - merchantId={}", merchantAccountId);
                    return MerchantSettlementPolicy.createDefault(merchantAccountId);
                });

        SettlementAmountResult calculation = settlementCalculator.calculate(grossAmount, policy);
        SettlementRecord newRecord = SettlementRecord.createPending(
                paymentTransactionId, virtualAccountId, merchantAccountId, calculation);

        SettlementRecord saved;
        try {
            saved = settlementRepository.saveAndFlush(newRecord);
        } catch (DataIntegrityViolationException e) {
            // 동일 txId 정산 레코드가 이미 존재 (복구 동시 실행 등) → 기존 레코드 반환
            log.info("[정산] paymentTransactionId {} 정산 레코드 이미 존재 (중복 방지) - 기존 레코드 반환",
                    paymentTransactionId);
            return settlementRepository.findByPaymentTransactionId(paymentTransactionId)
                    .stream().findFirst()
                    .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
        }

        log.info("[정산 등록] id={}, txId={}, gross={}, net={}, expectedDate={}",
                saved.getId(), paymentTransactionId,
                calculation.getGrossAmount(), calculation.getNetAmount(),
                calculation.getExpectedSettlementDate());
        return saved;
    }

    /**
     * PENDING 정산 건 일괄 처리 (SettlementScheduler에서 호출)
     * 각 건은 독립적으로 처리 → 한 건 실패해도 나머지 건 계속 진행
     */
    public void processAllPendingSettlements() {
        // expectedSettlementDate <= 오늘인 건만 처리 (D+1/D+2 정산 주기 준수)
        List<SettlementRecord> pendingList = settlementRepository
                .findByStatusAndExpectedSettlementDateLessThanEqual(SettlementStatus.PENDING, LocalDate.now());
        log.info("[정산 시작] 오늘 정산 대상 PENDING 건수: {}", pendingList.size());

        int successCount = 0;
        int failCount = 0;

        for (SettlementRecord record : pendingList) {
            try {
                settleOne(record.getId());
                successCount++;
            } catch (Exception e) {
                failCount++;
                // 실패 사유 기록 후 PENDING 유지 → 다음 정산 주기 재시도
                settlementRepository.findById(record.getId())
                        .ifPresent(r -> r.recordFailure(e.getMessage()));
                log.error("[정산 실패] id={}, reason={}", record.getId(), e.getMessage());
            }
        }

        log.info("[정산 완료] 성공={}, 실패={}", successCount, failCount);
    }

    /** 단건 정산: 외부 은행 승인 → 가상계좌(C) → 가맹점(B) 이체 → SETTLED */
    private void settleOne(Long recordId) {
        SettlementRecord recordInfo = settlementRepository.findById(recordId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));

        Long virtualAccountId = recordInfo.getVirtualAccountId();
        Long merchantAccountId = recordInfo.getMerchantAccountId();
        Long netAmount = recordInfo.getNetAmount();

        // 가맹점 은행 입금 외부 승인 (DB 커넥션 미점유 상태에서 호출)
        externalBankService.approve(merchantAccountId, netAmount);

        // 가상계좌 → 가맹점 이체 (분산 락으로 두 계좌 동시 보호)
        List<String> lockKeys = List.of(
                "lock:account:" + Math.min(virtualAccountId, merchantAccountId),
                "lock:account:" + Math.max(virtualAccountId, merchantAccountId)
        );

        redissonLockService.executeWithMultiLock(lockKeys, lockWaitSeconds, lockLeaseSeconds, () -> {
            transactionTemplate.execute(status -> {
                SettlementRecord record = settlementRepository.findById(recordId)
                        .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
                Account virtualAccount = accountRepository.findById(record.getVirtualAccountId())
                        .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
                Account merchantAccount = accountRepository.findById(record.getMerchantAccountId())
                        .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

                virtualAccount.withdraw(record.getNetAmount());
                merchantAccount.deposit(record.getNetAmount());
                record.markSettled();

                PaymentTransaction tx = transactionRepository.findById(record.getPaymentTransactionId())
                        .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
                tx.markSuccess();

                log.info("[정산 완료] settlementId={}, txId={}, net={}",
                        recordId, record.getPaymentTransactionId(), record.getNetAmount());
                return null;
            });
            return null;
        });
    }
}
