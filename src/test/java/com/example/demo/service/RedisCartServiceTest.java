package com.example.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RedisCartService 必須只序列化「純量快照」，不得碰 lazy proxy：
 * 曾因直接序列化 OrderItem entity 觸發 LazyInitializationException，
 * 導致 Redis 快照靜默失敗（log 只有 ERROR，功能看似正常）。
 */
@ExtendWith(MockitoExtension.class)
class RedisCartServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private ZSetOperations<String, String> zSetOps;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RedisCartService service;

    @BeforeEach
    void setUp() {
        // 手動建構：ObjectMapper 用真實例項，序列化行為才真實
        service = new RedisCartService(redisTemplate, objectMapper);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
    }

    private com.example.demo.entity.OrderItem newItem() {
        com.example.demo.entity.OrderItem item = new com.example.demo.entity.OrderItem();
        item.setId(7L);
        item.setProductNameSnapshot("珍珠奶茶");
        item.setQty(2);
        item.setUnitPriceSnapshot(new BigDecimal("35.00"));
        item.setFinalPrice(new BigDecimal("70.00"));
        item.setPaymentStatus("UNPAID");
        return item;
    }

    @Test
    void saveItemStoresFlatSnapshotWithoutLazyFields() {
        com.example.demo.entity.OrderItem item = newItem();

        service.saveItem("T1", item);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(hashOps).put(eq("cart:items:T1"), eq("7"), value.capture());
        String json = value.getValue();
        assertTrue(json.contains("珍珠奶茶"), "快照應含品名");
        assertTrue(json.contains("\"qty\":2"), "快照應含數量");
        assertFalse(json.contains("\"toppings\""), "不得序列化關聯集合");
        assertFalse(json.contains("\"product\""), "不得序列化 product 關聯");
        assertFalse(json.contains("\"user\""), "不得序列化 user 關聯");
    }

    @Test
    void saveItemSurvivesNullProduct() {
        // 舊資料可能 product_id 為 null；快照路徑不得拋 NPE
        com.example.demo.entity.OrderItem item = newItem();
        service.saveItem("T2", item);
        verify(hashOps).put(eq("cart:items:T2"), eq("7"), anyString());
    }
}
