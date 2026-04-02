package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.account.entity.Account;
import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.payment.entity.MerchantSettlementPolicy;
import com.toss.cashback.domain.payment.entity.SettlementAmountResult;
import com.toss.cashback.domain.payment.entity.SettlementRecord;
import com.toss.cashback.domain.payment.entity.SettlementStatus;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.payment.repository.MerchantSettlementPolicyRepository;
import com.toss.cashback.domain.payment.repository.PaymentTransactionRepository;
import com.toss.cashback.domain.payment.repository.SettlementRepository;
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

import java.util.List;

// ======= [신규] 정산 서비스 (PG 가상계좌 → 가맹점 정산) =======
/**
 * =====================================================================
 * [설계 의도] PG 정산 처리 - 가상계좌 보관 자금을 가맹점에 입금
 * =====================================================================
 *
 * 두 가지 역할:
 * 1. createSettlementRecord() - 구매자 결제 완료 시 정산 레코드 생성 (PaymentFacade에서 호출)
 *    → MerchantSettlementPolicy를 조회해 수수료/VAT/netAmount를 계산 후 저장
 * 2. processAllPendingSettlements() - PENDING 건 일괄 정산 (SettlementScheduler에서 호출)
 *    → 가상계좌에서 netAmount만 차감해 가맹점에 입금 (feeAmount + vatAmount는 PG 수익)
 *
 * 정산 금액 흐름:
 *   가상계좌 보유 grossAmount → 가맹점 입금 netAmount + PG 수익 feeAmount+vatAmount
 *
 * 정산 실패 시 전략:
 * - 외부 API 실패(가맹점 은행 서버 다운 등) → SettlementRecord PENDING 유지 + 실패 사유 기록
 * - 다음 정산 주기(다음 날)에 자동 재시도
 * - 구매자 환불 없음 (자금은 가상계좌에 안전하게 보관 중)
 *
 * 실제 PG(토스페이먼츠)에서는 D+1, D+2 정산 주기로 운영됩니다.
 * =====================================================================
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

    // application.yml lock.account 에서 주입 (PaymentService와 동일한 계좌 락 설정 공유)
    @Value("${lock.account.wait-seconds:5}")
    private long lockWaitSeconds;

    @Value("${lock.account.lease-seconds:30}")
    private long lockLeaseSeconds;      // 정산은 배치 작업이나 동일한 DB 트랜잭션 특성 → 30s 동일 적용

    /**
     * 구매자 결제 완료 시 정산 레코드 생성 (PENDING 상태)
     *
     * 처리 흐름:
     * 1. 가맹점의 정산 정책 조회 (없으면 기본 정책 자동 적용: 3.5% / VAT포함 / D+1)
     * 2. SettlementCalculator로 수수료/부가세/실지급액 계산
     * 3. SettlementRecord에 계산 결과 포함하여 저장
     *
     * SettlementScheduler가 이 레코드를 기반으로 가맹점에 정산합니다.
     */
    @Transactional
    public SettlementRecord createSettlementRecord(Long paymentTransactionId, Long virtualAccountId,
                                                    Long merchantAccountId, Long grossAmount) {
        // 가맹점 정산 정책 조회 (없으면 기본 정책 자동 생성)
        MerchantSettlementPolicy policy = policyRepository
                .findByMerchantAccountId(merchantAccountId)
                .orElseGet(() -> {
                    log.info("[정산] 미등록 가맹점 → 기본 정책 적용 (3.5%/VAT/D+1) - merchantId={}", merchantAccountId);
                    return MerchantSettlementPolicy.createDefault(merchantAccountId);
                });

        // 수수료 / 부가세 / 실지급액 계산 (BigDecimal HALF_EVEN 반올림)
        SettlementAmountResult calculation = settlementCalculator.calculate(grossAmount, policy);

        SettlementRecord record = SettlementRecord.createPending(
                paymentTransactionId, virtualAccountId, merchantAccountId, calculation);
        SettlementRecord saved = settlementRepository.save(record);

        log.info("[정산 등록] id={}, txId={}, merchantId={}, gross={}, fee={}, vat={}, net={}, expectedDate={}",
                saved.getId(), paymentTransactionId, merchantAccountId,
                calculation.getGrossAmount(), calculation.getFeeAmount(),
                calculation.getVatAmount(), calculation.getNetAmount(),
                calculation.getExpectedSettlementDate());

        return saved;
    }

    /**
     * PENDING 상태 정산 건 일괄 처리 (SettlementScheduler에서 호출)
     * 각 건은 독립적으로 처리 → 한 건 실패해도 나머지 건 계속 진행
     */
    public void processAllPendingSettlements() {
        List<SettlementRecord> pendingList = settlementRepository.findByStatus(SettlementStatus.PENDING);
        log.info("[정산 시작] PENDING 건수: {}", pendingList.size());

        int successCount = 0;
        int failCount = 0;

        for (SettlementRecord record : pendingList) {
            try {
                settleOne(record.getId());
                successCount++;
            } catch (Exception e) {
                failCount++;
                // PENDING 유지 → 다음 정산 주기에 재시도
                updateFailureReason(record.getId(), e.getMessage());
                log.error("[정산 실패] id={}, merchantId={}, reason={}",
                        record.getId(), record.getMerchantAccountId(), e.getMessage());
            }
        }

        log.info("[정산 완료] 성공={}, 실패={} (실패 건은 다음 정산 주기 재시도)", successCount, failCount);
    }

    /**
     * 단건 정산 처리: 가상계좌(C) → 가맹점(B) 이체
     *
     * 이체 금액은 netAmount (수수료/VAT 차감 후 실지급액)
     * feeAmount + vatAmount는 가상계좌에 잔류 (PG 수익)
     *
     * 락 키 결정을 위해 레코드를 먼저 조회하고, 락 내에서 최신 상태로 재조회합니다.
     * (stale read 방지 - 두 번 조회 전략)
     */
    private void settleOne(Long recordId) {
        // 락 키 결정용 사전 조회 (락 외부 - 읽기 전용)
        SettlementRecord recordInfo = settlementRepository.findById(recordId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));

        Long virtualAccountId = recordInfo.getVirtualAccountId();
        Long merchantAccountId = recordInfo.getMerchantAccountId();
        // 가맹점 입금 승인 금액 = netAmount (수수료/VAT 차감 후 실지급액)
        Long netAmount = recordInfo.getNetAmount();

        // STEP A: 가맹점 입금 승인 (B 은행 외부 API) - 락 외부에서 호출 (DB 커넥션 미점유)
        externalBankService.approve(merchantAccountId, netAmount);
        log.info("[정산 STEP-A 완료] 가맹점 입금 승인 - settlementId={}, merchantId={}, netAmount={}",
                recordId, merchantAccountId, netAmount);

        // STEP B: 가상계좌(C) → 가맹점(B) 이체 (분산 락으로 두 계좌 동시 보호)
        List<String> lockKeys = List.of(
                "lock:account:" + Math.min(virtualAccountId, merchantAccountId),
                "lock:account:" + Math.max(virtualAccountId, merchantAccountId)
        );

        redissonLockService.executeWithMultiLock(lockKeys, lockWaitSeconds, lockLeaseSeconds, () -> {
            transactionTemplate.execute(status -> {

                // 락 내에서 최신 상태로 재조회 (stale read 방지)
                SettlementRecord record = settlementRepository.findById(recordId)
                        .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));

                Account virtualAccount = accountRepository.findById(record.getVirtualAccountId())
                        .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
                Account merchantAccount = accountRepository.findById(record.getMerchantAccountId())
                        .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

                // netAmount만 이체 (feeAmount + vatAmount는 가상계좌 잔류 = PG 수익)
                virtualAccount.withdraw(record.getNetAmount());
                merchantAccount.deposit(record.getNetAmount());

                record.markSettled();   // PENDING → SETTLED (Dirty Checking → 자동 UPDATE)

                // PaymentTransaction 최종 SUCCESS 처리
                PaymentTransaction tx = transactionRepository.findById(record.getPaymentTransactionId())
                        .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
                tx.markSuccess();   // PENDING_SETTLEMENT → SUCCESS

                log.info("[정산 STEP-B 완료] 가상계좌→가맹점 이체 완료 - settlementId={}, txId={}, net={}, fee={}, vat={}",
                        recordId, record.getPaymentTransactionId(),
                        record.getNetAmount(), record.getFeeAmount(), record.getVatAmount());
                return null;
            });
            return null;
        });
    }

    /** 정산 실패 사유 기록 (PENDING 상태 유지, 다음 정산 주기 재시도) */
    @Transactional
    private void updateFailureReason(Long recordId, String reason) {
        settlementRepository.findById(recordId).ifPresent(record -> record.recordFailure(reason));
    }
}
