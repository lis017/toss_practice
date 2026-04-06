package com.toss.cashback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// ======= [2번] 애플리케이션 진입점 =======
/**
 * [주의] @EnableScheduling: SettlementScheduler, IdempotencyCleanupScheduler의 @Scheduled 활성화
 * 이 어노테이션 없으면 스케줄러가 실행되지 않습니다
 */
@SpringBootApplication
@EnableScheduling
public class CashbackSystemApplication {
    public static void main(String[] args) {
        SpringApplication.run(CashbackSystemApplication.class, args);
    }
}
