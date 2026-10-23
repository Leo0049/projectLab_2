package com.example.demo.config;

import com.example.demo.service.FirebaseTokenReplayGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.mock.env.MockEnvironment;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Redis 模板的接線（ADR-0013，不連線）：鍵加前綴、hash 欄位與 value 不加；prod 未設定即拒絕啟動；
 * fbtoken 守衛使用不加前綴的模板。
 */
class RedisConfigWiringTest {

    private final RedisConnectionFactory factory = mock(RedisConnectionFactory.class);

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 模板的序列化器 getter 回傳 {@code RedisSerializer<?>}，這裡統一以 Object 序列化。 */
    @SuppressWarnings("unchecked")
    private static byte[] serializeWith(RedisSerializer<?> serializer, Object value) {
        return ((RedisSerializer<Object>) serializer).serialize(value);
    }

    @Test
    void stringTemplateNamespacesKeysAndNothingElse() {
        StringRedisTemplate t = new RedisConfig().stringRedisTemplate(factory, env(), "prod");
        assertArrayEquals(bytes("prod:lock:checkout:42"), serializeWith(t.getKeySerializer(), "lock:checkout:42"));
        assertArrayEquals(bytes("cart-item-1"), serializeWith(t.getHashKeySerializer(), "cart-item-1"));
        assertArrayEquals(bytes("v"), serializeWith(t.getValueSerializer(), "v"));
    }

    @Test
    void genericTemplateNamespacesKeysAndKeepsHashFieldsPlain() {
        RedisTemplate<String, Object> g = new RedisConfig().redisTemplate(factory, env(), "prod");
        assertArrayEquals(bytes("prod:spin:done:1:2026-10-22"),
                serializeWith(g.getKeySerializer(), "spin:done:1:2026-10-22"));
        assertArrayEquals(bytes("field"), serializeWith(g.getHashKeySerializer(), "field"));
    }

    @Test
    void globalTemplateDoesNotPrefixKeys() {
        StringRedisTemplate g = new RedisConfig().globalStringRedisTemplate(factory);
        assertArrayEquals(bytes("fbtoken:abc"), serializeWith(g.getKeySerializer(), "fbtoken:abc"));
    }

    @Test
    void noNamespaceOutsideProductionMeansNoPrefix() {
        StringRedisTemplate t = new RedisConfig().stringRedisTemplate(factory, env(), "");
        assertArrayEquals(bytes("lock:checkout:42"), serializeWith(t.getKeySerializer(), "lock:checkout:42"));
    }

    @Test
    void productionWithNamespaceUsesIt() {
        StringRedisTemplate t = new RedisConfig().stringRedisTemplate(factory, env("prod"), "prod");
        assertArrayEquals(bytes("prod:x"), serializeWith(t.getKeySerializer(), "x"));
    }

    @Test
    void productionWithoutNamespaceRefusesToStart() {
        RedisConfig config = new RedisConfig();
        assertThrows(IllegalStateException.class, () -> config.stringRedisTemplate(factory, env("prod"), ""));
        assertThrows(IllegalStateException.class, () -> config.redisTemplate(factory, env("prod"), "  "));
    }

    @Test
    void malformedNamespaceRefusesToStartOutsideProduction() {
        assertThrows(IllegalStateException.class,
                () -> new RedisConfig().stringRedisTemplate(factory, env(), "Prod"));
    }

    @Test
    void fbtokenGuardReadsItsTemplateFromTheGlobalBean() throws NoSuchMethodException {
        Constructor<FirebaseTokenReplayGuard> ctor = FirebaseTokenReplayGuard.class.getConstructor(ObjectProvider.class);
        Qualifier qualifier = null;
        for (Annotation annotation : ctor.getParameterAnnotations()[0]) {
            if (annotation instanceof Qualifier q) {
                qualifier = q;
            }
        }
        assertNotNull(qualifier, "fbtoken 守衛的模板參數必須標 @Qualifier，否則會落到加前綴的主要模板");
        assertEquals(RedisConfig.GLOBAL_STRING_TEMPLATE, qualifier.value());
    }
}
