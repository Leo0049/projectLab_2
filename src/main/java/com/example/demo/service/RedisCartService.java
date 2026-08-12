package com.example.demo.service;

import com.example.demo.entity.OrderItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RedisCartService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CART_HASH_PREFIX = "cart:items:";
    private static final String ACTIVE_CARTS_KEY = "carts:active";

    @Autowired
    public RedisCartService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 儲存單一品項至 Redis Hash
     */
    public void saveItem(String token, OrderItem item) {
        try {
            String key = CART_HASH_PREFIX + token;
            String field = String.valueOf(item.getId());
            String value = objectMapper.writeValueAsString(item);
            
            redisTemplate.opsForHash().put(key, field, value);
            updateActiveCartTimestamp(token);
            
            log.debug("Saved item {} to Redis cart: {}", item.getId(), token);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize OrderItem for Redis", e);
        }
    }

    /**
     * 從 Redis Hash 移除品項
     */
    public void removeItem(String token, Long itemId) {
        String key = CART_HASH_PREFIX + token;
        redisTemplate.opsForHash().delete(key, String.valueOf(itemId));
        updateActiveCartTimestamp(token);
        
        log.debug("Removed item {} from Redis cart: {}", itemId, token);
    }

    /**
     * 取得該揪團的所有品項
     */
    public List<OrderItem> getCartItems(String token) {
        String key = CART_HASH_PREFIX + token;
        Map<Object, Object> itemsMap = redisTemplate.opsForHash().entries(key);
        
        if (itemsMap.isEmpty()) {
            return Collections.emptyList();
        }

        return itemsMap.values().stream()
                .map(obj -> {
                    try {
                        return objectMapper.readValue((String) obj, OrderItem.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize OrderItem from Redis", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 清空 Redis 中的購物車資料
     */
    public void clearCart(String token) {
        String key = CART_HASH_PREFIX + token;
        redisTemplate.delete(key);
        redisTemplate.opsForZSet().remove(ACTIVE_CARTS_KEY, token);
        log.debug("Cleared Redis cart: {}", token);
    }

    /**
     * 更新活躍揪團的時間戳 (Sorted Set)
     */
    private void updateActiveCartTimestamp(String token) {
        double score = (double) Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().add(ACTIVE_CARTS_KEY, token, score);
    }

    /**
     * 獲取最近活躍的揪團 Token 列表
     */
    public List<String> getRecentActiveCarts(int count) {
        return redisTemplate.opsForZSet().reverseRange(ACTIVE_CARTS_KEY, 0, count - 1)
                .stream().map(String::valueOf).collect(Collectors.toList());
    }
}
