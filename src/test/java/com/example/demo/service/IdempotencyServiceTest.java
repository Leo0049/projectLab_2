package com.example.demo.service;

import com.example.demo.exception.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 冪等鍵的狀態機（不載入 Spring context）。
 *
 * <p>守住的是「同一個結帳意圖只執行一次」：重送重播、失敗可重試、佔位中拒絕、鍵以使用者為範圍、
 * 格式錯誤拒絕、同一把鍵配不同內容拒絕（422），以及 Redis 路徑的同一組性質。
 */
class IdempotencyServiceTest {

    private static final String KEY = "order-key-0001";

    /** 同一份購物車的兩次送出，內容相同。 */
    private static final Map<String, Object> BODY_A = Map.of("finalPrice", 35);
    /** 同一把鍵卻改了內容：必須拒絕，不可回放 BODY_A 的結果。 */
    private static final Map<String, Object> BODY_B = Map.of("finalPrice", 1);

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate template) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }

    private static IdempotencyService localService() {
        return new IdempotencyService(providerOf(null), new ObjectMapper());
    }

    private static ResponseEntity<?> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }

    private static int orderIdOf(ResponseEntity<?> response) {
        return ((Number) ((Map<?, ?>) response.getBody()).get("orderId")).intValue();
    }

    @Test
    @DisplayName("同一把鍵第二次不再執行，直接重播第一次的結果")
    void secondCallReplaysTheFirstResult() {
        IdempotencyService service = localService();
        AtomicInteger runs = new AtomicInteger();

        ResponseEntity<?> first = service.execute("checkout", 1L, KEY, BODY_A, () -> {
            runs.incrementAndGet();
            return ok(Map.of("orderId", 7));
        });
        ResponseEntity<?> second = service.execute("checkout", 1L, KEY, BODY_A, () -> {
            runs.incrementAndGet();
            return ok(Map.of("orderId", 99));
        });

        assertEquals(1, runs.get(), "第二次不應再執行操作");
        assertEquals(7, orderIdOf(first));
        assertEquals(7, orderIdOf(second), "重播必須回第一次的訂單");
        assertEquals(200, second.getStatusCode().value());
        assertEquals("true", second.getHeaders().getFirst(IdempotencyService.REPLAYED_HEADER));
    }

    @Test
    @DisplayName("失敗（4xx）會釋放佔位，同一把鍵可以重試")
    void failedAttemptReleasesTheKey() {
        IdempotencyService service = localService();
        AtomicInteger runs = new AtomicInteger();

        ResponseEntity<?> failed = service.execute("checkout", 1L, KEY, BODY_A, () -> {
            runs.incrementAndGet();
            return ResponseEntity.badRequest().body(Map.of("error", "餘額不足"));
        });
        assertEquals(400, failed.getStatusCode().value());

        ResponseEntity<?> retry = service.execute("checkout", 1L, KEY, BODY_A, () -> {
            runs.incrementAndGet();
            return ok(Map.of("orderId", 8));
        });
        assertEquals(2, runs.get());
        assertEquals(8, orderIdOf(retry));
    }

    @Test
    @DisplayName("例外會往外拋，並釋放佔位")
    void exceptionPropagatesAndReleasesTheKey() {
        IdempotencyService service = localService();

        assertThrows(IllegalStateException.class,
                () -> service.execute("checkout", 1L, KEY, BODY_A, () -> {
                    throw new IllegalStateException("boom");
                }));

        ResponseEntity<?> retry = service.execute("checkout", 1L, KEY, BODY_A, () -> ok(Map.of("orderId", 9)));
        assertEquals(9, orderIdOf(retry));
    }

    @Test
    @DisplayName("佔位中的重送（內容相同）回 409，且不會執行操作")
    void duplicateWhileInProgressIsRejected() {
        IdempotencyService service = localService();
        AtomicInteger innerRuns = new AtomicInteger();

        ResponseEntity<?> outer = service.execute("checkout", 1L, KEY, BODY_A, () -> {
            ResponseEntity<?> inner = service.execute("checkout", 1L, KEY, BODY_A, () -> {
                innerRuns.incrementAndGet();
                return ok(Map.of("orderId", 1));
            });
            assertEquals(409, inner.getStatusCode().value());
            return ok(Map.of("orderId", 2));
        });

        assertEquals(0, innerRuns.get());
        assertEquals(2, orderIdOf(outer));
    }

    @Test
    @DisplayName("佔位中又來一個內容不同的請求 → 422（不是 409），且不會執行操作")
    void anotherBodyWhileInProgressIsRejectedAs422() {
        IdempotencyService service = localService();
        AtomicInteger innerRuns = new AtomicInteger();

        ResponseEntity<?> outer = service.execute("checkout", 1L, KEY, BODY_A, () -> {
            ResponseEntity<?> inner = service.execute("checkout", 1L, KEY, BODY_B, () -> {
                innerRuns.incrementAndGet();
                return ok(Map.of("orderId", 1));
            });
            assertEquals(422, inner.getStatusCode().value());
            return ok(Map.of("orderId", 2));
        });

        assertEquals(0, innerRuns.get());
        assertEquals(2, orderIdOf(outer));
    }

    @Test
    @DisplayName("同一把鍵配不同內容 → 422，不執行；第一次的結果不被覆寫")
    void sameKeyWithAnotherBodyIsRejectedAndTheFirstResultSurvives() {
        IdempotencyService service = localService();
        AtomicInteger runs = new AtomicInteger();

        service.execute("checkout", 1L, KEY, BODY_A, () -> {
            runs.incrementAndGet();
            return ok(Map.of("orderId", 7));
        });
        ResponseEntity<?> other = service.execute("checkout", 1L, KEY, BODY_B, () -> {
            runs.incrementAndGet();
            return ok(Map.of("orderId", 99));
        });
        ResponseEntity<?> again = service.execute("checkout", 1L, KEY, BODY_A, () -> {
            runs.incrementAndGet();
            return ok(Map.of("orderId", 0));
        });

        assertEquals(422, other.getStatusCode().value());
        assertEquals(1, runs.get(), "內容不同的請求不應執行");
        assertEquals(7, orderIdOf(again), "第一次的結果不被覆寫");
    }

    @Test
    @DisplayName("格式不合規的鍵直接拒絕（8–64 個英數字、底線、連字號）")
    void rejectsMalformedKeys() {
        IdempotencyService service = localService();

        for (String bad : new String[] {"short", "has space 123", "bad/chars!!", "x".repeat(65)}) {
            CustomException error = assertThrows(CustomException.class,
                    () -> service.execute("checkout", 1L, bad, BODY_A, () -> ok(Map.of("orderId", 0))));
            assertEquals("400", error.getCode(), bad);
        }
        assertDoesNotThrow(() -> IdempotencyService.validate("a1_b2-c3d4"));
    }

    @Test
    @DisplayName("鍵以使用者為範圍：A 用過的鍵不影響 B")
    void keysAreScopedPerUser() {
        IdempotencyService service = localService();
        AtomicInteger runs = new AtomicInteger();

        service.execute("checkout", 1L, KEY, BODY_A, () -> {
            runs.incrementAndGet();
            return ok(Map.of("orderId", 1));
        });
        service.execute("checkout", 2L, KEY, BODY_A, () -> {
            runs.incrementAndGet();
            return ok(Map.of("orderId", 2));
        });

        assertEquals(2, runs.get());
    }

    @Test
    @DisplayName("請求指紋：同一份內容永遠相同，內容不同則不同")
    void fingerprintFollowsTheBodyContent() {
        ObjectMapper mapper = new ObjectMapper();
        String first = IdempotencyService.fingerprint(mapper, BODY_A);

        assertEquals(first, IdempotencyService.fingerprint(mapper, Map.of("finalPrice", 35)));
        assertNotEquals(first, IdempotencyService.fingerprint(mapper, BODY_B));
        assertEquals(64, first.length(), "SHA-256 的十六進位長度");
    }

    @Test
    @DisplayName("Redis 路徑：佔位成功 → 執行 → 把結果寫回 Redis（DONE: 前綴）")
    void redisClaimThenStoresTheResult() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        IdempotencyService service = new IdempotencyService(providerOf(redis), new ObjectMapper());
        ResponseEntity<?> response = service.execute("checkout", 1L, KEY, BODY_A, () -> ok(Map.of("orderId", 5)));

        assertEquals(5, orderIdOf(response));
        verify(ops).set(anyString(), startsWith("DONE:"), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 路徑：已有完成紀錄（舊格式，無指紋）時直接重播，不執行操作（多實例共用）")
    void redisReplaysWhenTheKeyIsAlreadyDone() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(ops.get(anyString())).thenReturn("DONE:{\"status\":200,\"body\":{\"orderId\":42}}");

        IdempotencyService service = new IdempotencyService(providerOf(redis), new ObjectMapper());
        AtomicInteger runs = new AtomicInteger();
        ResponseEntity<?> response = service.execute("checkout", 1L, KEY, BODY_A, () -> {
            runs.incrementAndGet();
            return ok(Map.of("orderId", 0));
        });

        assertEquals(0, runs.get());
        assertEquals(42, orderIdOf(response));
    }

    @Test
    @DisplayName("Redis 路徑：佔位中（舊格式 IN_PROGRESS，無指紋）→ 409")
    void redisInProgressReturnsConflict() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(ops.get(anyString())).thenReturn("IN_PROGRESS");

        IdempotencyService service = new IdempotencyService(providerOf(redis), new ObjectMapper());
        AtomicInteger runs = new AtomicInteger();
        ResponseEntity<?> response = service.execute("checkout", 1L, KEY, BODY_A, () -> {
            runs.incrementAndGet();
            return ok(Map.of("orderId", 0));
        });

        assertEquals(409, response.getStatusCode().value());
        assertEquals(0, runs.get());
    }

    @Test
    @DisplayName("Redis 路徑：佔位中（帶指紋）遇到內容不同的請求 → 422")
    void redisInProgressWithAnotherBodyIsRejectedAs422() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        String placeholder = "IN_PROGRESS:" + IdempotencyService.fingerprint(new ObjectMapper(), BODY_A);
        when(ops.get(anyString())).thenReturn(placeholder);

        IdempotencyService service = new IdempotencyService(providerOf(redis), new ObjectMapper());
        AtomicInteger runs = new AtomicInteger();
        ResponseEntity<?> response = service.execute("checkout", 1L, KEY, BODY_B, () -> {
            runs.incrementAndGet();
            return ok(Map.of("orderId", 0));
        });

        assertEquals(422, response.getStatusCode().value());
        assertEquals(0, runs.get());
    }

    @Test
    @DisplayName("Redis 路徑：已完成的紀錄遇到內容不同的請求 → 422，不重播")
    void redisDoneWithAnotherBodyIsRejectedAs422() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        IdempotencyService service = new IdempotencyService(providerOf(redis), new ObjectMapper());
        service.execute("checkout", 1L, KEY, BODY_A, () -> ok(Map.of("orderId", 42)));

        // 第一次執行寫入的完成紀錄（含 BODY_A 的指紋）；下面模擬另一個實例讀到它
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(ops).set(anyString(), saved.capture(), any(Duration.class));
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(ops.get(anyString())).thenReturn(saved.getValue());

        AtomicInteger runs = new AtomicInteger();
        ResponseEntity<?> response = service.execute("checkout", 1L, KEY, BODY_B, () -> {
            runs.incrementAndGet();
            return ok(Map.of("orderId", 0));
        });

        assertEquals(422, response.getStatusCode().value());
        assertEquals(0, runs.get());
    }
}
