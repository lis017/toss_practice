package com.toss.cashback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// ======= [10번] 애플리케이션 진입점 (@EnableScheduling 추가) =======
/**
 * [주의] @EnableScheduling: IdempotencyCleanupScheduler의 @Scheduled 활성화
 * 이 어노테이션 없으면 스케줄러가 실행되지 않습니다
 */
@SpringBootApplication
@EnableScheduling
public class CashbackSystemApplication {
    public static void main(String[] args) {
        SpringApplication.run(CashbackSystemApplication.class, args); // Spring Boot 애플리케이션 시작
    }
}
