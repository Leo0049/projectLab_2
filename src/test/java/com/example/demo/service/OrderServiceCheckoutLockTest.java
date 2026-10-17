package com.example.demo.service;

import com.example.demo.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 結帳的使用者鎖忙碌時的回應（不載入 Spring context）。
 *
 * <p>鎖取不到時還沒有建單、也還沒有扣款，應回 503 讓前端稍後重試。若拋成一般的 RuntimeException，
 * 控制器會把它當成業務拒絕（400），前端就無從分辨（docs/IMPROVEMENT_ASSESSMENT.md 第 5 項）。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceCheckoutLockTest {

    @Mock private RedisLockService redisLockService;
    @InjectMocks private OrderService orderService;

    @Test
    void busyCheckoutLockFailsWith503InsteadOfAnOrdinaryRejection() {
        when(redisLockService.acquireLock(anyString(), anyLong())).thenReturn(null);

        CustomException ex = assertThrows(CustomException.class,
                () -> orderService.createOrder(1L, 11L, BigDecimal.ONE, null, List.of(), "WALLET", null, null));

        assertEquals("503", ex.getCode());
    }
}
