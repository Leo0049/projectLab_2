package com.example.demo.service;

import com.example.demo.exception.CustomException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登入／重設密碼的嘗試次數限制（滑動視窗計數）。
 *
 * <p>⚠️ 為什麼需要：本專案原本<b>沒有任何速率限制</b>。密碼規則只有 8～16 字元，
 * {@code /api/auth/login}、{@code /api/brand-auth/login}、{@code /api/stores/auth/login}
 * 都是公開且可無限次呼叫，線上爆破／撞庫沒有成本；{@code /api/auth/check-phone}
 * 又直接回答「這個號碼註冊了沒」，可直接做帳號列舉。
 *
 * <p>計數以「範圍（scope）＋ 身分」為鍵：登入用 {@code IP + 帳號}，避免同一 NAT
 * 後面的正常使用者被別人的失敗次數牽連；重設密碼這類沒有帳號可比對的端點只用 IP。
 *
 * <p>Redis 可用時用 {@code INCR + EXPIRE}（多實例共享）；Redis 不可用時退回 JVM 本地
 * 計數（僅單實例正確），與 {@link RedisLockService} 的退避策略一致——
 * 本機開發沒有 Redis 也不會整支壞掉。
 */
@Slf4j
@Service
public class LoginAttemptLimiter {

    /** 預設：同一 IP + 帳號，5 分鐘內最多 10 次 */
    public static final int DEFAULT_LIMIT = 10;
    public static final long DEFAULT_WINDOW_SECONDS = 300;

    /** 每個計數器的實際增量來源（抽出來讓測試可以注入假計數器） */
    @FunctionalInterface
    interface Counter {
        long increment(String key, long windowSeconds);
    }

    private final Counter counter;
    private final Map<String, LocalCounter> localCounters = new ConcurrentHashMap<>();

    private record LocalCounter(long count, long expiresAtMillis) {
    }

    public LoginAttemptLimiter(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        this.counter = redisTemplate == null
                ? this::incrementLocally
                : (key, windowSeconds) -> incrementInRedis(redisTemplate, key);
    }

    /** 測試用：直接注入計數器 */
    LoginAttemptLimiter(Counter counter) {
        this.counter = counter;
    }

    /** 以預設門檻檢查；超過即擲 429（呼叫端不必自行處理回傳值） */
    public void check(String scope, String identity) {
        check(scope, identity, DEFAULT_LIMIT, DEFAULT_WINDOW_SECONDS);
    }

    public void check(String scope, String identity, int limit, long windowSeconds) {
        String key = "ratelimit:" + scope + ":" + (identity == null || identity.isBlank() ? "unknown" : identity);
        long attempts = counter.increment(key, windowSeconds);
        if (shouldBlock(attempts, limit)) {
            throw new CustomException("429", "嘗試次數過多，請稍後再試（每 " + windowSeconds + " 秒最多 " + limit + " 次）");
        }
    }

    /**
     * 純函式：第 {@code attempts} 次（含本次）是否該擋。
     * limit <= 0 代表停用限制。
     */
    static boolean shouldBlock(long attempts, int limit) {
        return limit > 0 && attempts > limit;
    }

    /**
     * 取用戶端 IP。
     *
     * <p>反向代理後 {@code getRemoteAddr()} 會是代理的位址，因此優先讀
     * {@code X-Forwarded-For} 的第一段；本機直連時兩者相同。
     * 注意：這個標頭可由用戶端偽造，所以只用於「區分來源」而非作為授權依據。
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    /** 登入類端點的身分鍵：IP ＋ 帳號（帳號 null 時只留 IP） */
    public static String identity(String ip, String account) {
        return account == null || account.isBlank() ? ip : ip + "|" + account.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private long incrementInRedis(StringRedisTemplate redisTemplate, String key) {
        try {
            Long value = redisTemplate.opsForValue().increment(key);
            long count = value == null ? 1L : value;
            if (count == 1L) {
                // 第一次才設逾時，讓計數器隨視窗自然過期
                redisTemplate.expire(key, Duration.ofSeconds(DEFAULT_WINDOW_SECONDS));
            }
            return count;
        } catch (Exception e) {
            log.warn("Redis 不可用，速率限制改用本地計數：{}", e.getMessage());
            return incrementLocally(key, DEFAULT_WINDOW_SECONDS);
        }
    }

    private long incrementLocally(String key, long windowSeconds) {
        long now = System.currentTimeMillis();
        localCounters.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
        LocalCounter updated = localCounters.compute(key, (k, existing) -> {
            if (existing == null || existing.expiresAtMillis() < now) {
                return new LocalCounter(1L, now + windowSeconds * 1000);
            }
            return new LocalCounter(existing.count() + 1, existing.expiresAtMillis());
        });
        return updated.count();
    }
}
