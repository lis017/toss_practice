package com.toss.cashback.global.config;

import com.toss.cashback.domain.account.entity.Account;
import com.toss.cashback.domain.account.repository.AccountRepository;
import com.toss.cashback.domain.cashback.entity.CashbackBudget;
import com.toss.cashback.domain.cashback.repository.CashbackBudgetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// ======= [11번] 초기 데이터 세팅 (테스트 계좌 + 캐시백 예산) =======
/**
 * 서버 시작 시 초기 데이터 세팅. 운영 환경이면 Flyway로 대체해야 합니다.
 *
 * 생성되는 계좌:
 * 1. 김철수 (구매자): 잔액 1,000만 원 → 결제 요청 시 fromAccountId로 사용
 * 2. 토스상점 (가맹점): 잔액 0원 → 정산 완료 시 입금 대상 (toAccountId)
 * 3. 토스 가상계좌 (PG 중간 보관): 잔액 0원 → 구매자 출금 직후 자금 보관, 정산 시 가맹점으로 이동
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final AccountRepository accountRepository;
    private final CashbackBudgetRepository cashbackBudgetRepository;

    @Value("${cashback.budget.total:100000000}")        // application.yml에서 조정 가능
    private Long totalBudget;

    @Value("${cashback.virtual-account.account-number:TOSS-VIRTUAL-001}")
    private String virtualAccountNumber;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        initCashbackBudget();
        initTestAccounts();
    }

    private void initCashbackBudget() {
        if (cashbackBudgetRepository.count() == 0) {
            cashbackBudgetRepository.save(new CashbackBudget(totalBudget));
            log.info("[초기화] 캐시백 예산 생성 - 총 예산: {}원", totalBudget);
        } else {
            log.info("[초기화] 캐시백 예산 이미 존재 - 스킵");
        }
    }

    private void initTestAccounts() {
        if (accountRepository.count() == 0) {
            // [변경] 아래 계좌 정보(번호, 이름, 잔액)를 원하는 값으로 수정하세요
            Account buyer = accountRepository.save(Account.builder()
                    .accountNumber("110-1234-5678")
                    .ownerName("김철수")
                    .balance(10_000_000L)
                    .build());

            Account merchant = accountRepository.save(Account.builder()
                    .accountNumber("220-9876-5432")
                    .ownerName("토스상점")
                    .balance(0L)
                    .build());

            // 가상계좌: PG 정산 전 자금 보관용 (구매자 → 가상계좌 → 가맹점 정산 흐름)
            Account virtualAccount = accountRepository.save(Account.builder()
                    .accountNumber(virtualAccountNumber)
                    .ownerName("토스 가상계좌")
                    .balance(0L)
                    .build());

            log.info("[초기화] 테스트 계좌 생성 완료");
            log.info("[계좌] 구매자 ID={} (잔액 1,000만 원)", buyer.getId());
            log.info("[계좌] 가맹점 ID={} (정산 수신)", merchant.getId());
            log.info("[계좌] 가상계좌 ID={} (PG 자금 보관)", virtualAccount.getId());
            log.info("[테스트] POST http://localhost:8080/api/v1/payments");
            log.info("[테스트] Body: {{ \"idempotencyKey\": \"...\", \"fromAccountId\": {}, \"toAccountId\": {}, \"amount\": 10000 }}",
                    buyer.getId(), merchant.getId());
        } else {
            log.info("[초기화] 테스트 계좌 이미 존재 - 스킵");
        }
    }
}
