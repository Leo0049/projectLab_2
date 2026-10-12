package com.example.demo.service;

import com.example.demo.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 速率限制（不載入 Spring context）。
 *
 * <p>守住的是本輪 C-2：登入端點原本可以無限次嘗試。
 */
class LoginAttemptLimiterTest {

    @Test
    @DisplayName("超過門檻後回 429，門檻內不受影響")
    void blocksOnlyAfterTheLimitIsExceeded() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(new CountingCounter());

        for (int i = 1; i <= 3; i++) {
            assertDoesNotThrow(() -> limiter.check("login", "1.2.3.4|0912000000", 3, 300));
        }

        CustomException error = assertThrows(CustomException.class,
                () -> limiter.check("login", "1.2.3.4|0912000000", 3, 300));
        assertEquals("429", error.getCode());
    }

    @Test
    @DisplayName("不同身分（帳號）互不影響")
    void countsEachIdentitySeparately() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(new CountingCounter());

        // 把 A 的額度用完（前 3 次放行、第 4 次擋下）
        for (int i = 1; i <= 3; i++) {
            limiter.check("login", "1.2.3.4|a@x.com", 3, 300);
        }
        assertThrows(CustomException.class, () -> limiter.check("login", "1.2.3.4|a@x.com", 3, 300));

        // 別的身分沿用同一組門檻，不該被別人的失敗次數牽連
        assertDoesNotThrow(() -> limiter.check("login", "1.2.3.4|b@x.com", 3, 300));
        assertDoesNotThrow(() -> limiter.check("login", "5.6.7.8|a@x.com", 3, 300));
    }

    @Test
    @DisplayName("不同範圍（scope）互不影響")
    void countsEachScopeSeparately() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(new CountingCounter());

        for (int i = 1; i <= 3; i++) {
            limiter.check("login", "same", 3, 300);
        }
        assertThrows(CustomException.class, () -> limiter.check("login", "same", 3, 300));

        // 同一個身分在不同端點（scope）有各自的計數
        assertDoesNotThrow(() -> limiter.check("reset-password", "same", 3, 300));
    }

    @Test
    @DisplayName("Redis 可用時讀 Redis 的計數")
    void usesRedisWhenAvailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L, 99L);
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);

        LoginAttemptLimiter limiter = new LoginAttemptLimiter(providerOf(redisTemplate));

        assertDoesNotThrow(() -> limiter.check("login", "ip|acct", 3, 300));
        assertThrows(CustomException.class, () -> limiter.check("login", "ip|acct", 3, 300));
    }

    @Test
    @DisplayName("Redis 不可用時退回本地計數，仍能擋下連續嘗試")
    void fallsBackToLocalCounterWhenRedisIsUnavailable() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(providerOf(null));

        assertDoesNotThrow(() -> limiter.check("login", "ip|acct", 2, 300));
        assertDoesNotThrow(() -> limiter.check("login", "ip|acct", 2, 300));
        assertThrows(CustomException.class, () -> limiter.check("login", "ip|acct", 2, 300));
    }

    @Test
    @DisplayName("純函式：門檻判斷")
    void pureDecision() {
        assertFalse(LoginAttemptLimiter.shouldBlock(10, 10));
        assertTrue(LoginAttemptLimiter.shouldBlock(11, 10));
        assertFalse(LoginAttemptLimiter.shouldBlock(9999, 0));   // 0 = 停用
    }

    @Test
    @DisplayName("身分鍵只看 remoteAddr：偽造的 X-Forwarded-For 不會換掉限流 key")
    void forgedForwardedForDoesNotChangeTheIdentity() {
        // 舊版會取 XFF 第一段（攻擊者可任意填），每次換值就等於換一組計數器。
        // 信任可信代理的工作交給容器（server.forward-headers-strategy=native），這裡不再解析。
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        assertEquals("198.51.100.20", LoginAttemptLimiter.clientIp(request));
        assertEquals("198.51.100.20|0912000000",
                LoginAttemptLimiter.identity(LoginAttemptLimiter.clientIp(request), "0912000000"));
        assertEquals("198.51.100.20", LoginAttemptLimiter.identity("198.51.100.20", "  "));
    }

    @Test
    @DisplayName("Redis 計數器的逾時使用呼叫端的視窗，不是寫死的 300 秒")
    void redisExpiryUsesTheRequestedWindow() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);

        LoginAttemptLimiter limiter = new LoginAttemptLimiter(providerOf(redisTemplate));
        assertDoesNotThrow(() -> limiter.check("check-phone", "ip", 60, 60));

        verify(redisTemplate).expire(anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("沒有標頭時用 remoteAddr；null request 不得爆掉")
    void clientIpFallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");

        assertEquals("10.0.0.9", LoginAttemptLimiter.clientIp(request));
        assertEquals("unknown", LoginAttemptLimiter.clientIp(null));
    }

    /**
     * 假計數器：每個 key 各自遞增（不依賴 Redis，也不依賴時間）。
     *
     * <p>⚠️ 一開始寫成「全體共用一個 AtomicLong」，於是「不同身分互不影響」兩個測試永遠
     * 在第二個 key 就撞到門檻而失敗——那其實是假計數器自己把身分混在一起，不是實作有問題。
     * 要驗「key 有沒有分開算」，假物件就必須照 key 分開算。
     */
    private static final class CountingCounter implements LoginAttemptLimiter.Counter {
        private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();

        @Override
        public long increment(String key, long windowSeconds) {
            return values.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        }
    }

    private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate template) {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject() {
                return template;
            }

            // ObjectProvider.getObject(Object...) 是介面上的抽象方法（Spring 6.2），
            // 少了它匿名類別編不過（實測：is not abstract and does not override abstract method）。
            // LoginAttemptLimiter 只用到 getIfAvailable()，這裡回同一個 template 即可。
            @Override
            public StringRedisTemplate getObject(Object... args) {
                return template;
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return template;
            }

            @Override
            public StringRedisTemplate getIfUnique() {
                return template;
            }
        };
    }
}
