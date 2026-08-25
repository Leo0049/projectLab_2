package com.example.demo.service;

import com.example.demo.entity.OrderItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 揪團購物車的 Redis 快照。
 *
 * <p>⚠️ 修復：舊版 {@code saveItem} 直接用 Jackson 序列化 OrderItem entity，
 * 離開交易後 lazy proxy（product→category→brand）拋 LazyInitializationException，
 * 例外被吞掉，Redis 快照從未真正寫入。改為只存 {@link CartItemSnapshot} 純量欄位——
 * 其中 productId 取自 proxy 的 getId()（Hibernate 保證不觸發初始化）。
 *
 * <p>⚠️ 同步移除死碼 getCartItems／getRecentActiveCarts（全專案零呼叫端，
 * 已於 2026-08 安全審查時 grep 證實）。日後要讀回購物車請反序列化成
 * CartItemSnapshot，不要再綁回 entity。
 */
@Slf4j
@Service
public class RedisCartService {

    /** 存進 Redis 的最小快照；只允許純量欄位，禁止加入任何關聯型別 */
    public record CartItemSnapshot(
            Long id,
            Long productId,
            String productNameSnapshot,
            Integer qty,
            BigDecimal unitPriceSnapshot,
            BigDecimal finalPrice,
            String paymentStatus) {
    }

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CART_HASH_PREFIX = "cart:items:";
    private static final String ACTIVE_CARTS_KEY = "carts:active";

    @Autowired
    public RedisCartService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 儲存單一品項至 Redis Hash（純量快照，永不觸發 lazy loading） */
    public void saveItem(String token, OrderItem item) {
        try {
            CartItemSnapshot snapshot = new CartItemSnapshot(
                    item.getId(),
                    item.getProduct() != null ? item.getProduct().getId() : null,
                    item.getProductNameSnapshot(),
                    item.getQty(),
                    item.getUnitPriceSnapshot(),
                    item.getFinalPrice(),
                    item.getPaymentStatus());

            String key = CART_HASH_PREFIX + token;
            String field = String.valueOf(item.getId());
            String value = objectMapper.writeValueAsString(snapshot);

            redisTemplate.opsForHash().put(key, field, value);
            updateActiveCartTimestamp(token);

            log.debug("Saved item {} to Redis cart: {}", item.getId(), token);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize cart snapshot for Redis", e);
        }
    }

    /** 從 Redis Hash 移除品項 */
    public void removeItem(String token, Long itemId) {
        String key = CART_HASH_PREFIX + token;
        redisTemplate.opsForHash().delete(key, String.valueOf(itemId));
        updateActiveCartTimestamp(token);

        log.debug("Removed item {} from Redis cart: {}", itemId, token);
    }

    /** 清空 Redis 中的購物車資料 */
    public void clearCart(String token) {
        String key = CART_HASH_PREFIX + token;
        redisTemplate.delete(key);
        redisTemplate.opsForZSet().remove(ACTIVE_CARTS_KEY, token);
        log.debug("Cleared Redis cart: {}", token);
    }

    /** 更新活躍揪團的時間戳 (Sorted Set)，供未來的清理排程使用 */
    private void updateActiveCartTimestamp(String token) {
        double score = (double) Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().add(ACTIVE_CARTS_KEY, token, score);
    }
}
