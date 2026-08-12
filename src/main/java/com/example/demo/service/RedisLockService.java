package com.example.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Service
@Slf4j
public class RedisLockService {

    private final StringRedisTemplate redisTemplate;
    private final Map<String, Long> localLocks = new ConcurrentHashMap<>();

    public RedisLockService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    public boolean acquireLock(String lockKey, long timeoutSeconds) {
        if (redisTemplate == null) {
            log.warn("Redis unavailable (not configured), falling back to local lock");
            return useLocalLock(lockKey, timeoutSeconds);
        }
        
        try {
            Boolean success = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", Duration.ofSeconds(timeoutSeconds));
            return success != null && success;
        } catch (Exception e) {
            log.warn("Redis unavailable (connection failed), falling back to local lock: {}", e.getMessage());
            return useLocalLock(lockKey, timeoutSeconds);
        }
    }

    private boolean useLocalLock(String lockKey, long timeoutSeconds) {
        long now = System.currentTimeMillis();
        long expiry = now + (timeoutSeconds * 1000);
        
        // Clean up expired local locks
        localLocks.entrySet().removeIf(entry -> entry.getValue() < now);
        
        if (localLocks.putIfAbsent(lockKey, expiry) == null) {
            return true;
        }
        return false;
    }

    public void releaseLock(String lockKey) {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(lockKey);
            } catch (Exception e) {
                log.warn("Redis unavailable, releasing local lock");
                localLocks.remove(lockKey);
            }
        } else {
            localLocks.remove(lockKey);
        }
    }
}
