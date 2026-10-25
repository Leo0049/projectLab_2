package com.example.demo.controller;

import com.example.demo.exception.CustomException;
import com.example.demo.service.GroupOrderService;
import com.example.demo.service.IdempotencyService;
import com.example.demo.service.RedisCartService;
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
 * 團員付款的冪等鍵（stage 15，2026-10-25）。不載入 Spring context，接線方式與 GroupOrderCheckoutIdempotencyTest 相同。
 * 守住：同鍵重送回放第一次的成功結果；4xx 釋放佔位、可以重試；付款提交之後的購物車快照清除或推播失敗，
 * 不改變成功回應；同鍵搭配不同付款方式回 422；同鍵用在另一團互不影響；未帶鍵維持舊行為；
 * 格式不合規的鍵與非白名單的付款方式在付款前就拒絕。
 */
@ExtendWith(MockitoExtension.class)
class GroupMemberCheckoutIdempotencyTest {

    private static final String TOKEN = "TOKEN123";
    private static final String OTHER_TOKEN = "OTHER456";
    private static final String KEY = "member-key-0001";
    private static final long MEMBER_ID = 9L;
    private static final BigDecimal AMOUNT = new BigDecimal("35.00");

    @Mock private GroupOrderService groupOrderService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private RedisCartService redisCartService;

    /** 沒有 Redis：冪等紀錄走 JVM 本地路徑（與 GroupOrderCheckoutIdempotencyTest 相同做法）。 */
    @Spy private IdempotencyService idempotencyService = new IdempotencyService(noRedis(), new ObjectMapper());

    @InjectMocks private GroupOrderController controller;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> noRedis() {
        // 未設定 getIfAvailable() 時回 null，冪等服務即走本地路徑
        return mock(ObjectProvider.class);
    }

    private static GroupOrderController.MemberCheckoutRequest request(String paymentMethod, Long couponId) {
        GroupOrderController.MemberCheckoutRequest request = new GroupOrderController.MemberCheckoutRequest();
        request.setPaymentMethod(paymentMethod);
        request.setCouponId(couponId);
        return request;
    }

    private static MockHttpServletRequest asMember() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setAttribute("currentUserId", MEMBER_ID);
        return httpRequest;
    }

    @Test
    @DisplayName("同一把鍵重送：回放第一次的成功結果，不會再付款一次")
    void sameKeyReplaysTheFirstSuccessWithoutPayingAgain() {
        when(groupOrderService.getMemberUnpaidTotalAndMarkPaid(TOKEN, MEMBER_ID, "WALLET", null)).thenReturn(AMOUNT);

        ResponseEntity<?> first = controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), KEY);
        ResponseEntity<?> second = controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), KEY);

        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, second.getStatusCode().value());
        assertEquals("true", second.getHeaders().getFirst("Idempotent-Replayed"));
        verify(groupOrderService, times(1)).getMemberUnpaidTotalAndMarkPaid(any(), any(), any(), any());
    }

    @Test
    @DisplayName("付款被拒（4xx）時釋放冪等鍵，同一把鍵可以重試")
    void rejectedPaymentReleasesTheKeySoTheSameKeyCanRetry() {
        when(groupOrderService.getMemberUnpaidTotalAndMarkPaid(TOKEN, MEMBER_ID, "WALLET", null))
                .thenThrow(new CustomException("400", "儲值金餘額不足"))
                .thenReturn(AMOUNT);

        ResponseEntity<?> rejected = controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), KEY);
        ResponseEntity<?> retried = controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), KEY);

        assertEquals(400, rejected.getStatusCode().value());
        assertEquals(200, retried.getStatusCode().value());
        assertNull(retried.getHeaders().getFirst("Idempotent-Replayed"));   // 第二次是真的執行，不是回放
        verify(groupOrderService, times(2)).getMemberUnpaidTotalAndMarkPaid(any(), any(), any(), any());
    }

    @Test
    @DisplayName("付款已提交後清除購物車快照失敗：回應仍為成功，重送回放成功（不會因已付款而回 400）")
    void cartSnapshotFailureAfterPaymentStillReportsSuccess() {
        when(groupOrderService.getMemberUnpaidTotalAndMarkPaid(TOKEN, MEMBER_ID, "WALLET", null)).thenReturn(AMOUNT);
        doThrow(new RuntimeException("redis 不可用")).when(redisCartService).clearCart(TOKEN);

        ResponseEntity<?> first = controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), KEY);
        ResponseEntity<?> retried = controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), KEY);

        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, retried.getStatusCode().value());
        assertEquals("true", retried.getHeaders().getFirst("Idempotent-Replayed"));
        verify(groupOrderService, times(1)).getMemberUnpaidTotalAndMarkPaid(any(), any(), any(), any());
    }

    @Test
    @DisplayName("付款已提交後推播失敗：回應仍為成功，重送回放成功")
    void notificationFailureAfterPaymentStillReportsSuccess() {
        when(groupOrderService.getMemberUnpaidTotalAndMarkPaid(TOKEN, MEMBER_ID, "WALLET", null)).thenReturn(AMOUNT);
        doThrow(new RuntimeException("broker 不可用"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        ResponseEntity<?> first = controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), KEY);
        ResponseEntity<?> retried = controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), KEY);

        assertEquals(200, first.getStatusCode().value());
        assertEquals("true", retried.getHeaders().getFirst("Idempotent-Replayed"));
        verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(Object.class));
        verify(groupOrderService, times(1)).getMemberUnpaidTotalAndMarkPaid(any(), any(), any(), any());
    }

    @Test
    @DisplayName("同一把鍵搭配不同付款方式：回 422，不執行，也不改動第一次的結果")
    void sameKeyWithAnotherPaymentMethodIsRejectedWithoutPayingAgain() {
        when(groupOrderService.getMemberUnpaidTotalAndMarkPaid(TOKEN, MEMBER_ID, "WALLET", null)).thenReturn(AMOUNT);

        controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), KEY);
        ResponseEntity<?> mismatched = controller.memberCheckout(TOKEN, request("CASH", null), asMember(), KEY);

        assertEquals(422, mismatched.getStatusCode().value());
        verify(groupOrderService, times(1)).getMemberUnpaidTotalAndMarkPaid(any(), any(), any(), any());
    }

    @Test
    @DisplayName("同一把鍵用在另一團，不會被當成重送")
    void sameKeyOnAnotherGroupIsIndependent() {
        when(groupOrderService.getMemberUnpaidTotalAndMarkPaid(TOKEN, MEMBER_ID, "WALLET", null)).thenReturn(AMOUNT);
        when(groupOrderService.getMemberUnpaidTotalAndMarkPaid(OTHER_TOKEN, MEMBER_ID, "WALLET", null))
                .thenReturn(new BigDecimal("50.00"));

        ResponseEntity<?> first = controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), KEY);
        ResponseEntity<?> other = controller.memberCheckout(OTHER_TOKEN, request("WALLET", null), asMember(), KEY);

        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, other.getStatusCode().value());
        assertNull(other.getHeaders().getFirst("Idempotent-Replayed"));
        verify(groupOrderService).getMemberUnpaidTotalAndMarkPaid(OTHER_TOKEN, MEMBER_ID, "WALLET", null);
    }

    @Test
    @DisplayName("未帶 Idempotency-Key：維持舊行為，每次都會付款")
    void withoutAKeyEveryAttemptRunsThePayment() {
        when(groupOrderService.getMemberUnpaidTotalAndMarkPaid(TOKEN, MEMBER_ID, "WALLET", null)).thenReturn(AMOUNT);

        ResponseEntity<?> first = controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), null);
        ResponseEntity<?> second = controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), null);

        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, second.getStatusCode().value());
        assertNull(second.getHeaders().getFirst("Idempotent-Replayed"));
        verify(groupOrderService, times(2)).getMemberUnpaidTotalAndMarkPaid(any(), any(), any(), any());
    }

    @Test
    @DisplayName("格式不合規的鍵在付款前就拒絕，不會扣款")
    void malformedKeyIsRejectedBeforeAnyPayment() {
        CustomException ex = assertThrows(CustomException.class,
                () -> controller.memberCheckout(TOKEN, request("WALLET", null), asMember(), "short"));

        assertEquals("400", ex.getCode());
        verify(groupOrderService, never()).getMemberUnpaidTotalAndMarkPaid(any(), any(), any(), any());
    }

    @Test
    @DisplayName("非白名單的付款方式在付款前就拒絕，不會呼叫付款服務")
    void disallowedPaymentMethodIsRejectedBeforeAnyPayment() {
        ResponseEntity<?> response = controller.memberCheckout(TOKEN, request("BITCOIN", null), asMember(), KEY);

        assertEquals(400, response.getStatusCode().value());
        verify(groupOrderService, never()).getMemberUnpaidTotalAndMarkPaid(any(), any(), any(), any());
    }
}
