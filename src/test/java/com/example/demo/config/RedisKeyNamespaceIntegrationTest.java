package com.example.demo.config;

import com.example.demo.service.RedisLockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真實 Redis 上的命名空間行為（ADR-0013）。CI 與 docker compose 都提供 Redis。
 *
 * <p>不載入 Spring context。每個測試使用獨立的隨機命名空間，結束時只刪除自己建立的鍵，不呼叫 FLUSH。
 * 斷言的是伺服器上實際的鍵名：原始檢查使用不加前綴的模板讀取。
 */
class RedisKeyNamespaceIntegrationTest {

    private LettuceConnectionFactory factory;
    /** 不加前綴，用來看伺服器上的真實鍵名。 */
    private StringRedisTemplate raw;
    private String ns;
    private final List<String> created = new ArrayList<>();

    @BeforeEach
    void connect() throws Exception {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("REDIS_HOST", "localhost"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        String password = System.getenv().getOrDefault("REDIS_PASSWORD", "");
        if (!password.isBlank()) {
            cfg.setPassword(RedisPassword.of(password));
        }
        factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();
        raw = new StringRedisTemplate(factory);
        raw.afterPropertiesSet();
        ns = "it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try {
            if (raw != null && !created.isEmpty()) {
                raw.delete(created);
            }
        } finally {
            if (factory != null) {
                factory.destroy();
            }
        }
    }

    /** 記下伺服器上的真實鍵名（帶前綴），測試結束時刪除。 */
    private void track(String namespace, String logicalKey) {
        created.add(namespace + ":" + logicalKey);
    }

    private StringRedisTemplate stringTemplate(String namespace) {
        return new RedisConfig().stringRedisTemplate(factory, new MockEnvironment(), namespace);
    }

    @Test
    void stringTemplateWritesTheNamespacedKeyOnTheServer() {
        StringRedisTemplate t = stringTemplate(ns);
        track(ns, "probe:string");
        t.opsForValue().set("probe:string", "v1");

        assertEquals("v1", raw.opsForValue().get(ns + ":probe:string"));
        assertFalse(raw.hasKey("probe:string"), "未加前綴的鍵不應存在");
    }

    @Test
    void hashFieldsAreDataAndStayUnprefixed() {
        StringRedisTemplate t = stringTemplate(ns);
        track(ns, "cart:items:tok");
        t.opsForHash().put("cart:items:tok", "item-7", "{}");

        assertTrue(raw.opsForHash().hasKey(ns + ":cart:items:tok", "item-7"),
                "hash 欄位不是鍵，必須維持原樣");
        assertFalse(raw.hasKey("cart:items:tok"));
    }

    @Test
    void lockIsAcquiredAndReleasedThroughTheNamespacedKey() {
        RedisLockService locks = new RedisLockService(providerOf(stringTemplate(ns)));
        track(ns, "lock:checkout:42");

        String token = locks.acquireLock("lock:checkout:42", 30);
        assertNotNull(token);
        assertTrue(raw.hasKey(ns + ":lock:checkout:42"), "加鎖必須寫入帶前綴的鍵");

        locks.releaseLock("lock:checkout:42", token);
        assertFalse(raw.hasKey(ns + ":lock:checkout:42"),
                "解鎖的 Lua 腳本必須刪除同一把帶前綴的鍵");
    }

    @Test
    void genericTemplateUsesTheSameNamespace() {
        RedisTemplate<String, Object> g = new RedisConfig().redisTemplate(factory, new MockEnvironment(), ns);
        track(ns, "probe:generic");
        g.opsForValue().set("probe:generic", "v2");

        assertTrue(raw.hasKey(ns + ":probe:generic"));
        assertFalse(raw.hasKey("probe:generic"));
    }

    @Test
    void fbtokenKeyIsNotPrefixed() {
        StringRedisTemplate g = new RedisConfig().globalStringRedisTemplate(factory);
        String key = "fbtoken:" + ns;   // 測試專用的鍵，不會與真實權杖指紋碰撞
        created.add(key);
        g.opsForValue().set(key, "1");

        assertTrue(raw.hasKey(key), "fbtoken: 必須是不加前綴的鍵");
        assertFalse(raw.hasKey(ns + ":" + key));
    }

    @Test
    void sameLogicalKeyInTwoNamespacesDoesNotCollide() {
        String a = ns + "-a";
        String b = ns + "-b";
        StringRedisTemplate envA = stringTemplate(a);
        StringRedisTemplate envB = stringTemplate(b);
        track(a, "spin:done:1:2026-10-22");
        track(b, "spin:done:1:2026-10-22");

        envA.opsForValue().set("spin:done:1:2026-10-22", "from-a");
        envB.opsForValue().set("spin:done:1:2026-10-22", "from-b");

        assertEquals("from-a", envA.opsForValue().get("spin:done:1:2026-10-22"));
        assertEquals("from-b", envB.opsForValue().get("spin:done:1:2026-10-22"));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate template) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }
}
