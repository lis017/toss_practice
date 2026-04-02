package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.account.entity.Account;
import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.payment.repository.PaymentTransactionRepository;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import com.toss.cashback.infrastructure.redis.RedissonLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

// ======= [4번] 계좌 이체 서비스 =======
/**
 * 계좌 이체 처리. C안 기준으로 트랜잭션을 2개로 분리합니다.
 *
 * TX1(1단계 이체): 구매자(A) → 가상계좌(C) 자금 보관
 * TX4(상태 업데이트): 정산 완료 후 PaymentTransaction SUCCESS 처리
 *
 * 외부 은행 승인(STEP 1)이 완료된 후에만 TX1이 실행됩니다.
 * TX1은 실제 자금 이동(A→C)만 담당하고, 최종 수신처(B)는 SettlementRecord에 기록합니다.
 *
 * 다중 서버 안전성:
 * - executeTransfer: Redisson MultiLock으로 구매자 계좌 + 가상계좌 동시 보호
 * - 데드락 방지: 항상 낮은 락 키 순서로 획득 (lock:account:{낮은ID} → lock:account:{높은ID})
 * - 락 내에서 TransactionTemplate으로 커밋 보장 → 락 해제 전 DB 반영 완료
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final AccountRepository accountRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final RedissonLockService redissonLockService;   // 계좌 이체 분산 락
    private final TransactionTemplate transactionTemplate;   // 락 내에서 트랜잭션 커밋 보장

    // application.yml lock.account 에서 주입 (기본값: 운영 권장값)
    @Value("${lock.account.wait-seconds:5}")
    private long lockWaitSeconds;       // 락 획득 대기 시간

    @Value("${lock.account.lease-seconds:30}")
    private long lockLeaseSeconds;      // 락 자동 해제 시간 (GC full pause ~10s × 3배 여유 = 30s)

    /**
     * TX1: 구매자(A) → 가상계좌(C) 자금 이동 + 트랜잭션 레코드 생성
     *
     * 자금은 가상계좌(C)로 이동하고, PaymentTransaction에는 최종 수신처(가맹점 B)를 기록합니다.
     * 실제 가맹점 입금은 SettlementScheduler가 담당합니다.
     *
     * @param fromAccountId    구매자 계좌 ID (A - 실제 출금 대상)
     * @param merchantAccountId 가맹점 계좌 ID (B - PaymentTransaction 기록용, 정산 수신처)
     * @param virtualAccountId 가상계좌 ID (C - 실제 입금 대상, 정산 전 자금 보관)
     * @return 생성된 PaymentTransaction ID (정산 레코드 연결용)
     */
    public Long executeTransfer(Long fromAccountId, Long merchantAccountId, Long virtualAccountId, Long amount) {

        // 구매자와 가맹점이 동일하면 자기 자신에게 결제하는 비정상 요청
        if (fromAccountId.equals(merchantAccountId)) {
            throw new CustomException(ErrorCode.SAME_ACCOUNT_TRANSFER);
        }

        // 실제 자금 이동: 구매자(A) ↔ 가상계좌(C) 두 계좌를 동시에 락
        // 데드락 방지: 항상 낮은 ID 순서로 획득
        List<String> lockKeys = List.of(
                "lock:account:" + Math.min(fromAccountId, virtualAccountId),
                "lock:account:" + Math.max(fromAccountId, virtualAccountId)
        );

        return redissonLockService.executeWithMultiLock(lockKeys, lockWaitSeconds, lockLeaseSeconds, () ->
                transactionTemplate.execute(status -> {

                    Account buyer = accountRepository.findById(fromAccountId)
                            .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
                    Account virtualAccount = accountRepository.findById(virtualAccountId)
                            .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

                    buyer.withdraw(amount);             // 잔액 부족 시 예외 → 트랜잭션 롤백
                    virtualAccount.deposit(amount);     // 가상계좌에 보관 (Dirty Checking → 자동 UPDATE)

                    // PaymentTransaction: 구매자(A) → 가맹점(B) 결제 의도 기록 (자금은 C에 보관 중)
                    PaymentTransaction transaction = PaymentTransaction.builder()
                            .fromAccountId(fromAccountId)
                            .toAccountId(merchantAccountId)
                            .virtualAccountId(virtualAccountId)
                            .amount(amount)
                            .build();

                    PaymentTransaction saved = transactionRepository.save(transaction);
                    log.info("[TX1] 구매자→가상계좌 이체 완료 - txId={}, from={}, virtualId={}, amount={}",
                            saved.getId(), fromAccountId, virtualAccountId, amount);

                    return saved.getId();
                })
        );
    }

    /** TX-정산완료: 가맹점 정산 완료 후 SUCCESS 상태로 변경 (cashbackAmount는 1단계에서 이미 기록됨) */
    @Transactional
    public void markTransactionSuccess(Long transactionId) {
        PaymentTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
        transaction.markSuccess();
        log.info("[TX-정산완료] 결제 최종 성공 - txId={}", transactionId);
    }

    /** TX-1단계완료: 구매자 출금 + 가상계좌 보관 완료. 정산 대기 상태로 변경 */
    @Transactional
    public void markTransactionPendingSettlement(Long transactionId) {
        PaymentTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
        transaction.markPendingSettlement();
        log.info("[TX-1단계완료] 정산 대기 상태 변경 - txId={}", transactionId);
    }

    /**
     * 외부 승인 완료 후 내부 후처리 실패 시 상태 기록.
     * POST_PROCESS_FAILED = 외부 은행은 출금됐지만 내부 DB 처리 실패 → 불일치 상태.
     * 이 메서드 자체가 실패하면 txId와 에러를 로그로 남겨 수동 복구 기반을 확보합니다.
     */
    @Transactional
    public void markTransactionPostProcessFailed(Long transactionId, String reason) {
        transactionRepository.findById(transactionId).ifPresent(tx -> tx.markPostProcessFailed(reason));
        log.error("[후처리실패] 트랜잭션 상태 기록 - txId={}", transactionId);
    }
}
