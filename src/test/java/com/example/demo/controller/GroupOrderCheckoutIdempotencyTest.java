package com.example.demo.controller;

import com.example.demo.exception.CustomException;
import com.example.demo.service.GroupOrderService;
import com.example.demo.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 團長結帳的冪等鍵（stage 9，2026-10-22）。不載入 Spring context：控制器與冪等服務以真實物件接線，
 * 送單服務與推播以 mock 代替。守住：同鍵重送回放第一次的成功結果；4xx 釋放佔位、可以重試；
 * 提交之後的讀取或推播失敗不改變成功回應；同鍵搭配不同內容回 422；同鍵用在另一團不互相影響；
 * 未帶鍵維持舊行為；格式不合規的鍵在送單前就拒絕。
 */
@ExtendWith(MockitoExtension.class)
class GroupOrderCheckoutIdempotencyTest {

    private static final String TOKEN = "TOKEN123";
    private static final String OTHER_TOKEN = "OTHER456";
    private static final String KEY = "checkout-key-001";
    private static final long HOST_ID = 5L;
    private static final String ADDRESS = "臺北市中正區";

    @Mock private GroupOrderService groupOrderService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    /** 沒有 Redis：冪等紀錄走 JVM 本地路徑（與 IdempotencyServiceTest 相同做法）。 */
    @Spy private IdempotencyService idempotencyService = new IdempotencyService(noRedis(), new ObjectMapper());

    @InjectMocks private GroupOrderController controller;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> noRedis() {
        // 未設定 getIfAvailable() 時回 null，冪等服務即走本地路徑
        return mock(ObjectProvider.class);
    }

    private static GroupOrderController.CheckoutRequest request(Long couponId) {
        GroupOrderController.CheckoutRequest request = new GroupOrderController.CheckoutRequest();
        request.setPaymentMethod("WALLET");
        request.setAddress(ADDRESS);
        request.setCouponId(couponId);
        return request;
    }

    private static MockHttpServletRequest asHost() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setAttribute("currentUserId", HOST_ID);
        return httpRequest;
    }

    private static Map<String, Object> notifyInfo() {
        return Map.of("storeId", 7L, "totalAmount", new BigDecimal("35.00"));
    }

    private static long orderIdOf(ResponseEntity<?> response) {
        return ((Number) ((Map<?, ?>) response.getBody()).get("orderId")).longValue();
    }

    @Test
    @DisplayName("同一把鍵重送：回放第一次的成功結果，不會再結帳一次")
    void sameKeyReplaysTheFirstSuccessWithoutCheckingOutAgain() {
        when(groupOrderService.checkout(TOKEN, HOST_ID, null, "WALLET", ADDRESS, null)).thenReturn(42L);
        when(groupOrderService.getCheckoutNotifyInfo(TOKEN)).thenReturn(notifyInfo());

        ResponseEntity<?> first = controller.checkout(TOKEN, asHost(), request(null), KEY);
        ResponseEntity<?> second = controller.checkout(TOKEN, asHost(), request(null), KEY);

        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, second.getStatusCode().value());
        assertEquals("true", second.getHeaders().getFirst("Idempotent-Replayed"));
        assertEquals(42L, orderIdOf(second));
        verify(groupOrderService, times(1)).checkout(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("送單被拒（4xx）時釋放冪等鍵，同一把鍵可以重試")
    void rejectedAttemptReleasesTheKeySoTheSameKeyCanRetry() {
        when(groupOrderService.checkout(TOKEN, HOST_ID, null, "WALLET", ADDRESS, null))
                .thenThrow(new CustomException("400", "尚有未付款的品項"))
                .thenReturn(42L);
        when(groupOrderService.getCheckoutNotifyInfo(TOKEN)).thenReturn(notifyInfo());

        ResponseEntity<?> rejected = controller.checkout(TOKEN, asHost(), request(null), KEY);
        ResponseEntity<?> retried = controller.checkout(TOKEN, asHost(), request(null), KEY);

        assertEquals(400, rejected.getStatusCode().value());
        assertEquals(200, retried.getStatusCode().value());
        assertNull(retried.getHeaders().getFirst("Idempotent-Replayed"));   // 第二次是真的執行，不是回放
        assertEquals(42L, orderIdOf(retried));
        verify(groupOrderService, times(2)).checkout(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("提交之後讀取通知資訊失敗：回應仍為成功，重送回放成功（不會因已結帳而回 400）")
    void notifyInfoFailureAfterCommitStillReportsSuccess() {
        when(groupOrderService.checkout(TOKEN, HOST_ID, null, "WALLET", ADDRESS, null)).thenReturn(42L);
        when(groupOrderService.getCheckoutNotifyInfo(TOKEN)).thenThrow(new RuntimeException("讀取失敗"));

        ResponseEntity<?> first = controller.checkout(TOKEN, asHost(), request(null), KEY);
        ResponseEntity<?> retried = controller.checkout(TOKEN, asHost(), request(null), KEY);

        assertEquals(200, first.getStatusCode().value());
        assertEquals(42L, orderIdOf(first));
        assertEquals(200, retried.getStatusCode().value());
        assertEquals("true", retried.getHeaders().getFirst("Idempotent-Replayed"));
        verify(groupOrderService, times(1)).checkout(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("提交之後推播失敗：門市與揪團兩則推播都會嘗試，回應仍為成功，重送回放成功")
    void messagingFailureAfterCommitStillReportsSuccess() {
        when(groupOrderService.checkout(TOKEN, HOST_ID, null, "WALLET", ADDRESS, null)).thenReturn(42L);
        when(groupOrderService.getCheckoutNotifyInfo(TOKEN)).thenReturn(notifyInfo());
        doThrow(new RuntimeException("broker 不可用"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        ResponseEntity<?> first = controller.checkout(TOKEN, asHost(), request(null), KEY);
        ResponseEntity<?> retried = controller.checkout(TOKEN, asHost(), request(null), KEY);

        assertEquals(200, first.getStatusCode().value());
        assertEquals(42L, orderIdOf(first));
        assertEquals("true", retried.getHeaders().getFirst("Idempotent-Replayed"));
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(Object.class));
        verify(groupOrderService, times(1)).checkout(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("同一把鍵搭配不同內容：回 422，不執行，也不改動第一次的結果")
    void sameKeyWithAnotherBodyIsRejectedWithoutExecuting() {
        when(groupOrderService.checkout(TOKEN, HOST_ID, null, "WALLET", ADDRESS, null)).thenReturn(42L);
        when(groupOrderService.getCheckoutNotifyInfo(TOKEN)).thenReturn(notifyInfo());

        controller.checkout(TOKEN, asHost(), request(null), KEY);
        ResponseEntity<?> mismatched = controller.checkout(TOKEN, asHost(), request(99L), KEY);

        assertEquals(422, mismatched.getStatusCode().value());
        verify(groupOrderService, times(1)).checkout(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("同一把鍵用在另一團，不會被當成重送")
    void sameKeyOnAnotherGroupIsIndependent() {
        when(groupOrderService.checkout(TOKEN, HOST_ID, null, "WALLET", ADDRESS, null)).thenReturn(42L);
        when(groupOrderService.checkout(OTHER_TOKEN, HOST_ID, null, "WALLET", ADDRESS, null)).thenReturn(43L);
        when(groupOrderService.getCheckoutNotifyInfo(anyString())).thenReturn(notifyInfo());

        ResponseEntity<?> first = controller.checkout(TOKEN, asHost(), request(null), KEY);
        ResponseEntity<?> other = controller.checkout(OTHER_TOKEN, asHost(), request(null), KEY);

        assertEquals(42L, orderIdOf(first));
        assertEquals(43L, orderIdOf(other));
        assertNull(other.getHeaders().getFirst("Idempotent-Replayed"));
        verify(groupOrderService).checkout(OTHER_TOKEN, HOST_ID, null, "WALLET", ADDRESS, null);
    }

    @Test
    @DisplayName("未帶 Idempotency-Key：維持舊行為，每次都會執行")
    void withoutAKeyEveryAttemptRunsTheCheckout() {
        when(groupOrderService.checkout(TOKEN, HOST_ID, null, "WALLET", ADDRESS, null)).thenReturn(42L, 43L);
        when(groupOrderService.getCheckoutNotifyInfo(TOKEN)).thenReturn(notifyInfo());

        ResponseEntity<?> first = controller.checkout(TOKEN, asHost(), request(null), null);
        ResponseEntity<?> second = controller.checkout(TOKEN, asHost(), request(null), null);

        assertEquals(42L, orderIdOf(first));
        assertEquals(43L, orderIdOf(second));
        assertNull(second.getHeaders().getFirst("Idempotent-Replayed"));
    }

    @Test
    @DisplayName("格式不合規的鍵在送單前就拒絕，不會結帳")
    void malformedKeyIsRejectedBeforeAnyCheckout() {
        CustomException ex = assertThrows(CustomException.class,
                () -> controller.checkout(TOKEN, asHost(), request(null), "short"));

        assertEquals("400", ex.getCode());
        verify(groupOrderService, never()).checkout(any(), any(), any(), any(), any(), any());
    }
}
