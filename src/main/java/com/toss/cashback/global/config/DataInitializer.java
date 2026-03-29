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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final AccountRepository accountRepository;
    private final CashbackBudgetRepository cashbackBudgetRepository;

    @Value("${cashback.budget.total:100000000}")        // application.yml에서 조정 가능
    private Long totalBudget;

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
            log.info("[init] cashback budget created - totalBudget={} KRW", totalBudget);
        } else {
            log.info("[초기화] 캐시백 예산 이미 존재 - 스킵");
            log.info("[init] cashback budget already exists - skip");
        }
    }

    private void initTestAccounts() {
        if (accountRepository.count() == 0) {
            // [변경] 아래 계좌 정보(번호, 이름, 잔액)를 원하는 값으로 수정하세요
            accountRepository.save(Account.builder()
                    .accountNumber("110-1234-5678")
                    .ownerName("김철수")
                    .balance(10_000_000L)
                    .build());

            accountRepository.save(Account.builder()
                    .accountNumber("220-9876-5432")
                    .ownerName("토스상점")
                    .balance(0L)
                    .build());

            log.info("[초기화] 테스트 계좌 생성 완료");
            log.info("[init] test accounts created");
            log.info("[테스트] POST http://localhost:8080/api/v1/payments");
            log.info("[hint] POST http://localhost:8080/api/v1/payments");
            log.info("[테스트] Body: {{ \"idempotencyKey\": \"...\", \"fromAccountId\": 1, \"toAccountId\": 2, \"amount\": 10000 }}");
            log.info("[hint] Body: idempotencyKey, fromAccountId, toAccountId, amount (JSON)");
        } else {
            log.info("[초기화] 테스트 계좌 이미 존재 - 스킵");
            log.info("[init] test accounts already exist - skip");
        }
    }
}
