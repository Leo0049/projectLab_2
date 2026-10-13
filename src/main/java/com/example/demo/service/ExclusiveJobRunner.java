package com.example.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 讓排程任務在多實例部署時「同一時間只會在一個實例執行」。
 *
 * <p>做法：沿用 {@link RedisLockService} 的 token 鎖（key 為 {@code lock:job:<name>}）。
 * 取得鎖的實例執行任務，其餘實例記 log 後略過。任務結束（成功或失敗）都會釋放鎖。
 *
 * <p>⚠️ 這是「互斥」，不是「每天只執行一次」：若任務很快結束，另一個實例的觸發時間稍晚，
 * 仍可能再執行一次。因此被包住的任務必須是冪等的（例如「把已過期的券標記為 expired」重跑沒有副作用）。
 *
 * <p>Redis 不可用時，{@code RedisLockService} 退回本地鎖，多實例下無法互斥（見 ADR-0006）。
 */
@Slf4j
@Component
public class ExclusiveJobRunner {

    static final String LOCK_PREFIX = "lock:job:";

    private final RedisLockService redisLockService;

    public ExclusiveJobRunner(RedisLockService redisLockService) {
        this.redisLockService = redisLockService;
    }

    /**
     * @param jobName      任務名稱，組成鎖的 key；同一任務在所有實例必須使用同一個名稱
     * @param leaseSeconds 鎖的存活秒數（實例當機時的自動解鎖上限），需大於任務的最長執行時間
     * @param job          要執行的任務
     * @return true 表示本實例取得鎖並執行了任務；false 表示其他實例持有鎖而略過
     */
    public boolean runExclusively(String jobName, long leaseSeconds, Runnable job) {
        String lockKey = LOCK_PREFIX + jobName;
        String token = redisLockService.acquireLock(lockKey, leaseSeconds);
        if (token == null) {
            log.info("排程 {} 已由其他實例執行，本實例略過", jobName);
            return false;
        }
        try {
            return true;
        } finally {
            redisLockService.releaseLock(lockKey, token);
        }
    }
}
