package com.toss.cashback.infrastructure.redis;

import com.toss.cashback.global.error.CustomException;
import com.toss.cashback.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

// ======= [6번] Redisson 분산 락 서비스 =======
/**
 * Redisson 분산 락 공통 서비스. 락 로직(tryLock/unlock)을 비즈니스 코드와 분리합니다.
 *
 * leaseTime은 서버 다운 시 락이 영구히 잠기는 걸 방지하기 위한 자동 해제 시간입니다.
 *
 * executeWithLock: 단일 키 락 (캐시백 예산)
 * executeWithMultiLock: 다중 키 락 (계좌 이체 - 데드락 방지를 위해 키 정렬 후 획득)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonLockService {

    private final RedissonClient redissonClient;

    /**
     * 단일 키 분산 락
     * 사용처: 캐시백 예산 (lock:cashback:budget)
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Callable<T> task) {
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean isAcquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);

            if (!isAcquired) {
                log.warn("[RedissonLock] 락 획득 실패 - key={}", lockKey);
                log.warn("[RedissonLock] lock acquire failed - key={}", lockKey);
                throw new CustomException(ErrorCode.LOCK_ACQUISITION_FAILED);
            }

            log.debug("[RedissonLock] 락 획득 - key={}", lockKey);
            log.debug("[RedissonLock] lock acquired - key={}", lockKey);
            return task.call();

        } catch (CustomException e) {
            throw e;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[RedissonLock] 인터럽트 발생 - key={}", lockKey);
            log.error("[RedissonLock] interrupted - key={}", lockKey);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);

        } catch (Exception e) {
            log.error("[RedissonLock] 예외 발생 - key={}, error={}", lockKey, e.getMessage());
            log.error("[RedissonLock] error - key={}, error={}", lockKey, e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);

        } finally {
            // isHeldByCurrentThread(): 현재 스레드가 보유한 락만 해제 (다른 스레드 락을 실수로 풀지 않기 위해)
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("[RedissonLock] 락 해제 - key={}", lockKey);
                log.debug("[RedissonLock] lock released - key={}", lockKey);
            }
        }
    }

    /**
     * 다중 키 분산 락 (MultiLock)
     * 사용처: 계좌 이체 (lock:account:{fromId} + lock:account:{toId})
     *
     * 데드락 방지: lockKeys를 사전순 정렬 후 획득 → 모든 서버 인스턴스가 동일한 순서로 락 획득
     * 예) A→B 이체와 B→A 이체가 동시에 들어와도 둘 다 낮은 ID 락부터 시도 → 교착 상태 방지
     */
    public <T> T executeWithMultiLock(List<String> lockKeys, long waitTime, long leaseTime, Callable<T> task) {
        // 데드락 방지: 항상 사전순(동일 순서)으로 락 획득
        List<RLock> locks = lockKeys.stream()
                .sorted()
                .map(redissonClient::getLock)
                .collect(Collectors.toList());

        RLock multiLock = redissonClient.getMultiLock(locks.toArray(new RLock[0]));

        try {
            boolean isAcquired = multiLock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);

            if (!isAcquired) {
                log.warn("[RedissonMultiLock] 락 획득 실패 - keys={}", lockKeys);
                log.warn("[RedissonMultiLock] lock acquire failed - keys={}", lockKeys);
                throw new CustomException(ErrorCode.LOCK_ACQUISITION_FAILED);
            }

            log.debug("[RedissonMultiLock] 락 획득 - keys={}", lockKeys);
            log.debug("[RedissonMultiLock] locks acquired - keys={}", lockKeys);
            return task.call();

        } catch (CustomException e) {
            throw e;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[RedissonMultiLock] 인터럽트 발생 - keys={}", lockKeys);
            log.error("[RedissonMultiLock] interrupted - keys={}", lockKeys);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);

        } catch (Exception e) {
            log.error("[RedissonMultiLock] 예외 발생 - keys={}, error={}", lockKeys, e.getMessage());
            log.error("[RedissonMultiLock] error - keys={}, error={}", lockKeys, e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);

        } finally {
            if (multiLock.isHeldByCurrentThread()) {
                multiLock.unlock();
                log.debug("[RedissonMultiLock] 락 해제 - keys={}", lockKeys);
                log.debug("[RedissonMultiLock] locks released - keys={}", lockKeys);
            }
        }
    }
}
