package com.example.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分散式鎖（token 版）。
 *
 * <p>⚠️ M-3 修復：舊版有兩個問題——
 * <ol>
 *   <li><b>誤刪他人的鎖</b>：值固定寫 "locked"，releaseLock 直接 DEL。
 *       當 A 的鎖逾時自動過期、B 取得同一把鎖後，A 的 finally 才執行 DEL，
 *       會把 B 手上的鎖刪掉，第三個人又能進來——鎖形同虛設。
 *       改為每次 acquire 產生隨機 token，release 用 Lua 比對「值 == 我的 token」
 *       才刪除（比對＋刪除在 Redis 端原子完成）。</li>
 *   <li><b>API 語意</b>：acquireLock 回傳 {@code String} token（null = 取得失敗），
 *       releaseLock 必須帶回同一個 token。回傳 null 的判斷式取代原本的 {@code !boolean}。</li>
 * </ol>
 *
 * <p>Redis 不可用時退回 JVM 本地 Map（僅單實例部署下正確；多實例請確保 Redis 可用）。
 */
@Slf4j
@Service
public class RedisLockService {

    /** 比對 token 才刪除，避免逾時後誤刪他人的鎖（GET+DEL 不能分兩步） */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    /** 本地退避鎖：key -> token（僅單實例正確） */
    private final Map<String, LocalLock> localLocks = new ConcurrentHashMap<>();

    private record LocalLock(String token, long expiresAtMillis) {
    }

    public RedisLockService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    /**
     * 嘗試取得鎖。
     *
     * @return 成功時回傳本次持有的 token（之後必須原封不動傳給 releaseLock）；失敗回傳 null
     */
    public String acquireLock(String lockKey, long timeoutSeconds) {
        if (redisTemplate == null) {
            log.warn("Redis unavailable (not configured), falling back to local lock");
            return useLocalLock(lockKey, timeoutSeconds);
        }
        try {
            String token = UUID.randomUUID().toString();
            Boolean ok = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token, Duration.ofSeconds(timeoutSeconds));
            return Boolean.TRUE.equals(ok) ? token : null;
        } catch (Exception e) {
            log.warn("Redis unavailable (connection failed), falling back to local lock: {}", e.getMessage());
            return useLocalLock(lockKey, timeoutSeconds);
        }
    }

    private String useLocalLock(String lockKey, long timeoutSeconds) {
        long now = System.currentTimeMillis();
        // Clean up expired local locks
        localLocks.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
        String token = UUID.randomUUID().toString();
        LocalLock existing = localLocks.putIfAbsent(lockKey, new LocalLock(token, now + timeoutSeconds * 1000));
        return existing == null ? token : null;
    }

    /**
     * 釋放鎖。只有 token 與持有者一致才會真的刪除；
     * 鎖已逾時被他人接手時，此呼叫靜默無效（不影響新持有者）。
     */
    public void releaseLock(String lockKey, String token) {
        if (token == null) {
            return;
        }
        if (redisTemplate != null) {
            try {
                redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
                return;
            } catch (Exception e) {
                log.warn("Redis unavailable while releasing lock: {}", e.getMessage());
            }
        }
        // 本地退避：比對 token 才移除
        localLocks.computeIfPresent(lockKey, (k, v) -> token.equals(v.token()) ? null : v);
    }
}
