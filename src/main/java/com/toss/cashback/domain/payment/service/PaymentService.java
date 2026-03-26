package com.toss.cashback.domain.payment.service;

import com.toss.cashback.domain.account.entity.Account;
import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.payment.entity.PaymentTransaction;
import com.toss.cashback.domain.payment.repository.PaymentTransactionRepository;
import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계좌 이체 처리. 트랜잭션을 3개로 분리한 게 핵심입니다.
 *
 * 이체(TX1), 보상(TX2), 상태 업데이트(TX4)를 각각 독립 트랜잭션으로 분리해
 * PaymentFacade가 외부 API 결과에 따라 각 트랜잭션을 선택적으로 실행할 수 있습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final AccountRepository accountRepository;
    private final PaymentTransactionRepository transactionRepository;

    /** @return 생성된 PaymentTransaction ID (보상 트랜잭션 참조용) */
    @Transactional
    public Long executeTransfer(Long fromAccountId, Long toAccountId, Long amount) {

        if (fromAccountId.equals(toAccountId)) {
            throw new CustomException(ErrorCode.SAME_ACCOUNT_TRANSFER);
        }

        Account sender = accountRepository.findById(fromAccountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        Account receiver = accountRepository.findById(toAccountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        sender.withdraw(amount);    // 잔액 부족 시 예외 발생 → 트랜잭션 자동 롤백
        receiver.deposit(amount);   // Dirty Checking → save() 없이 커밋 시 자동 UPDATE

        PaymentTransaction transaction = PaymentTransaction.builder()
                .fromAccountId(fromAccountId)
                .toAccountId(toAccountId)
                .amount(amount)
                .build();

        PaymentTransaction saved = transactionRepository.save(transaction);
        log.info("[TX1] 이체 완료 - txId={}, from={}, to={}, amount={}", saved.getId(), fromAccountId, toAccountId, amount);

        return saved.getId();
    }

    /** TX1은 이미 커밋됐으므로 역방향 이체로 계좌를 원복합니다 */
    @Transactional
    public void compensate(Long transactionId) {
        log.warn("[TX2] 보상 트랜잭션 시작 - txId={}", transactionId);

        PaymentTransaction originalTx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));

        Long fromId = originalTx.getFromAccountId();
        Long toId = originalTx.getToAccountId();
        Long amount = originalTx.getAmount();

        Account originalSender = accountRepository.findById(fromId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));
        Account originalReceiver = accountRepository.findById(toId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        // 역방향 이체: 수신자 → 송금자로 금액 반환
        originalReceiver.withdraw(amount);  // 상점에서 차감
        originalSender.deposit(amount);     // 사용자에게 복구

        originalTx.markCompensated("외부 은행 API 실패로 인한 보상 트랜잭션 처리 완료");
        log.warn("[TX2] 보상 트랜잭션 완료 - txId={}, amount={}원 원복", transactionId, amount);
    }

    /** 결제 최종 SUCCESS 상태 업데이트 */
    @Transactional
    public void markTransactionSuccess(Long transactionId, Long cashbackAmount) {
        PaymentTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
        transaction.markSuccess(cashbackAmount);
        log.info("[TX3] 트랜잭션 성공 처리 - txId={}, cashback={}", transactionId, cashbackAmount);
    }
}
