package com.toss.cashback.infrastructure.redis;

import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 분산 락 공통 서비스. 락 로직(tryLock/unlock)을 비즈니스 코드와 분리합니다.
 *
 * leaseTime은 서버 다운 시 락이 영구히 잠기는 걸 방지하기 위한 자동 해제 시간입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonLockService {

    private final RedissonClient redissonClient;

    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Callable<T> task) {
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean isAcquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);

            if (!isAcquired) {
                log.warn("[RedissonLock] 락 획득 실패 - key={}", lockKey);
                throw new CustomException(ErrorCode.LOCK_ACQUISITION_FAILED);
            }

            log.debug("[RedissonLock] 락 획득 - key={}", lockKey);
            return task.call();

        } catch (CustomException e) {
            throw e;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[RedissonLock] 인터럽트 발생 - key={}", lockKey);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);

        } catch (Exception e) {
            log.error("[RedissonLock] 예외 발생 - key={}, error={}", lockKey, e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);

        } finally {
            // isHeldByCurrentThread(): 현재 스레드가 보유한 락만 해제 (다른 스레드 락을 실수로 풀지 않기 위해)
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("[RedissonLock] 락 해제 - key={}", lockKey);
            }
        }
    }
}
