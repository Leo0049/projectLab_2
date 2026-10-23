package com.example.demo.common;

import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 只用於 Redis 鍵的序列化器：寫入時在鍵前面加上 {@code <命名空間>:}，讀回時去掉（ADR-0013）。
 *
 * <p>只包鍵，不包 hash 欄位、zset 成員與 value。購物車的品項 id 與 token 是資料，不是鍵，
 * 加上前綴只會改壞資料。命名空間為空時，行為與 {@link StringRedisSerializer} 完全相同。
 *
 * <p>Lua 腳本的 KEYS 也經由模板的鍵序列化器，因此 {@code RedisLockService} 的解鎖腳本
 * 與加鎖使用同一個前綴（見 {@code RedisKeyNamespaceIntegrationTest}）。
 */
public final class NamespacedRedisKeySerializer implements RedisSerializer<String> {

    private final StringRedisSerializer delegate = new StringRedisSerializer();
    private final String prefix;

    /**
     * @throws IllegalStateException 命名空間格式不合規
     */
    public NamespacedRedisKeySerializer(String namespace) {
        this.prefix = RedisKeyNamespace.prefixFor(RedisKeyNamespace.validate(namespace));
    }

    /** 實際使用的前綴；未啟用命名空間時為空字串。 */
    public String prefix() {
        return prefix;
    }

    @Override
    public byte[] serialize(String key) {
        if (key == null) {
            return null;
        }
        return delegate.serialize(prefix + key);
    }

    @Override
    public String deserialize(byte[] bytes) {
        String raw = delegate.deserialize(bytes);
        if (raw == null || !raw.startsWith(prefix)) {
            return raw;
        }
        return raw.substring(prefix.length());
    }
}
