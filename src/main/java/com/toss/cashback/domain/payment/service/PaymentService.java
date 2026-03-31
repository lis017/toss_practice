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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

// ======= [4번] 계좌 이체 서비스 =======
/**
 * 계좌 이체 처리. 트랜잭션을 3개로 분리한 게 핵심입니다.
 *
 * 이체(TX1), 보상(TX2), 상태 업데이트(TX4)를 각각 독립 트랜잭션으로 분리해
 * PaymentFacade가 외부 API 결과에 따라 각 트랜잭션을 선택적으로 실행할 수 있습니다.
 *
 * 다중 서버 안전성:
 * - executeTransfer/compensate: Redisson MultiLock으로 두 계좌를 동시에 보호
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

    /**
     * TX1: 계좌 이체 + 트랜잭션 레코드 생성
     * 두 계좌에 MultiLock을 걸고 트랜잭션을 커밋한 뒤 락 해제
     * @return 생성된 PaymentTransaction ID (보상 트랜잭션 참조용)
     */
    public Long executeTransfer(Long fromAccountId, Long toAccountId, Long amount) {

        if (fromAccountId.equals(toAccountId)) {
            throw new CustomException(ErrorCode.SAME_ACCOUNT_TRANSFER);
        }

        // 데드락 방지: 두 계좌에 동시에 락 - 항상 사전순(낮은 ID)으로 획득
        List<String> lockKeys = List.of(
                "lock:account:" + fromAccountId,
                "lock:account:" + toAccountId
        );

        return redissonLockService.executeWithMultiLock(lockKeys, 5, 10, () ->
                transactionTemplate.execute(status -> {

                    Account sender = accountRepository.findById(fromAccountId)
                            .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
                    Account receiver = accountRepository.findById(toAccountId)
                            .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

                    sender.withdraw(amount);    // 잔액 부족 시 예외 → 트랜잭션 롤백
                    receiver.deposit(amount);   // Dirty Checking → 커밋 시 자동 UPDATE

                    PaymentTransaction transaction = PaymentTransaction.builder()
                            .fromAccountId(fromAccountId)
                            .toAccountId(toAccountId)
                            .amount(amount)
                            .build();

                    PaymentTransaction saved = transactionRepository.save(transaction);
                    log.info("[TX1] 이체 완료 - txId={}, from={}, to={}, amount={}",
                            saved.getId(), fromAccountId, toAccountId, amount);

                    return saved.getId();
                })
        );
    }

    /**
     * TX2: 보상 트랜잭션 - TX1을 역방향으로 원복
     * 락 키 결정을 위해 트랜잭션 정보를 먼저 조회한 뒤, 락 내에서 최신 상태로 재조회 후 처리
     */
    public void compensate(Long transactionId) {
        log.warn("[TX2] 보상 트랜잭션 시작 - txId={}", transactionId);

        // 락 키 결정을 위한 사전 조회 (락 외부 - 읽기 전용)
        PaymentTransaction txInfo = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));

        Long fromId = txInfo.getFromAccountId();
        Long toId = txInfo.getToAccountId();
        Long amount = txInfo.getAmount();

        List<String> lockKeys = List.of(
                "lock:account:" + fromId,
                "lock:account:" + toId
        );

        redissonLockService.executeWithMultiLock(lockKeys, 5, 10, () -> {
            transactionTemplate.execute(status -> {

                Account sender = accountRepository.findById(fromId)
                        .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
                Account receiver = accountRepository.findById(toId)
                        .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

                // 역방향 이체: 수신자 → 송금자로 금액 반환
                receiver.withdraw(amount);  // 상점에서 차감
                sender.deposit(amount);     // 사용자에게 복구

                // 락 내에서 최신 TX 재조회 후 상태 업데이트 (stale read 방지)
                PaymentTransaction tx = transactionRepository.findById(transactionId)
                        .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
                tx.markCompensated("외부 은행 API 실패로 인한 보상 트랜잭션 처리 완료");

                log.warn("[TX2] 보상 트랜잭션 완료 - txId={}, amount={}원 원복", transactionId, amount);
                return null;
            });
            return null;
        });
    }

    /** TX4: 결제 최종 SUCCESS 상태 업데이트 (계좌 수정 없음 → 단순 @Transactional) */
    @Transactional
    public void markTransactionSuccess(Long transactionId, Long cashbackAmount) {
        PaymentTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
        transaction.markSuccess(cashbackAmount);
        log.info("[TX3] 트랜잭션 성공 처리 - txId={}, cashback={}", transactionId, cashbackAmount);
    }

    /**
     * 보상 트랜잭션 자체가 실패했을 때 상태 기록
     * 계좌 불일치 상태이므로 수동 개입이 필요함을 나타내는 상태로 변경
     */
    @Transactional
    public void markTransactionCompensationFailed(Long transactionId, String reason) {
        transactionRepository.findById(transactionId).ifPresent(tx ->
                tx.markCompensationFailed("보상 트랜잭션 실패 - " + reason));
        log.error("[TX2] 보상 트랜잭션 실패 상태 기록 - txId={}", transactionId);
    }
}
