package com.toss.cashback.infrastructure.api;

import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// ======= [8번] 외부 은행 API Mock + 서킷 브레이커 =======
/**
 * 외부 은행 API Mock 구현체.
 *
 * 서킷 브레이커를 붙인 이유: 외부 서버 다운 시 매 요청마다 타임아웃 대기가 반복되면
 * 스레드가 고갈됩니다. 실패율 60% 초과 시 서킷을 열어서 이후 요청은 즉시 차단합니다.
 *
 * application.yml에서 simulate-delay: true / simulate-error: true로 장애 상황을 재현할 수 있습니다.
 */
@Slf4j
@Service
public class MockExternalBankService implements ExternalBankService {

    @Value("${cashback.external-bank.simulate-delay:false}")
    private boolean simulateDelay;

    @Value("${cashback.external-bank.simulate-error:false}")
    private boolean simulateError;

    @Value("${cashback.external-bank.timeout-seconds:3}")
    private int timeoutSeconds;

    private static final int SIMULATED_DELAY_SECONDS = 5; // 타임아웃(3초)보다 커야 TimeoutException 발생

    // fallbackMethod: 서킷 Open 상태 또는 예외 누적 시 호출. 파라미터 + Throwable 순서 맞춰야 함
    @Override
    @CircuitBreaker(name = "externalBankService", fallbackMethod = "approveFallback")
    public void approve(Long accountId, Long amount) {
        log.info("[외부 은행 API] 승인 요청 시작 - accountId={}, amount={}", accountId, amount);

        // 별도 스레드에서 실행해야 get()으로 타임아웃을 걸 수 있습니다
        CompletableFuture<Void> apiFuture = CompletableFuture.runAsync(() -> {
            try {
                if (simulateDelay) {
                    log.warn("[외부 은행 API] 지연 시뮬레이션 - {}초 대기", SIMULATED_DELAY_SECONDS);
                    Thread.sleep(SIMULATED_DELAY_SECONDS * 1000L);
                }
                if (simulateError) {
                    throw new RuntimeException("외부 은행 서버 내부 오류 (시뮬레이션)");
                }
                Thread.sleep(100); // 실제 HTTP 통신 시간 모의 (실제 구현 시 WebClient로 교체)
                log.info("[외부 은행 API] 승인 완료 - accountId={}", accountId);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("외부 은행 API 호출 중단됨", e);
            }
        });

        try {
            apiFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("[외부 은행 API] 정상 응답 완료 - accountId={}", accountId);

        } catch (TimeoutException e) {
            apiFuture.cancel(true);                             // 진행 중인 작업 강제 취소
            log.error("[외부 은행 API] 타임아웃 - {}초 초과, accountId={}", timeoutSeconds, accountId);
            throw new CustomException(ErrorCode.EXTERNAL_BANK_TIMEOUT); // → 서킷 실패 카운트 증가

        } catch (ExecutionException e) {
            apiFuture.cancel(true);
            log.error("[외부 은행 API] 오류 - accountId={}, error={}", accountId, e.getCause().getMessage());
            throw new CustomException(ErrorCode.EXTERNAL_BANK_ERROR);   // → 서킷 실패 카운트 증가

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[외부 은행 API] 인터럽트 - accountId={}", accountId);
            throw new CustomException(ErrorCode.EXTERNAL_BANK_ERROR);
        }
    }

    private void approveFallback(Long accountId, Long amount, Throwable throwable) {
        if (throwable instanceof CallNotPermittedException) {
            log.warn("[서킷 브레이커] OPEN 상태 - 즉시 차단, accountId={}", accountId);
            throw new CustomException(ErrorCode.CIRCUIT_BREAKER_OPEN);
        }
        // 서킷 오픈 외 예외: 원본 예외 그대로 전파 (Facade에서 적절히 처리)
        log.error("[서킷 브레이커] Fallback - accountId={}, cause={}", accountId, throwable.getMessage());
        if (throwable instanceof CustomException) {
            throw (CustomException) throwable;
        }
        throw new CustomException(ErrorCode.EXTERNAL_BANK_ERROR);
    }
}
