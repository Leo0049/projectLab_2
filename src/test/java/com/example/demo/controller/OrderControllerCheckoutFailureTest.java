package com.example.demo.controller;

import com.example.demo.exception.CustomException;
import com.example.demo.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 結帳失敗在控制器的 HTTP 結果（不載入 Spring context）。
 *
 * <p>系統忙碌（結帳鎖取不到）必須原樣拋出，交給 GlobalExceptionHandler 回 503；
 * 業務拒絕則仍回 400 的回應主體。若控制器把忙碌也吞成 400，前端就無從分辨要不要稍後重試
 * （docs/IMPROVEMENT_ASSESSMENT.md 第 5 項）。
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerCheckoutFailureTest {

    @Mock private OrderService orderService;
    @InjectMocks private OrderController controller;

    private static OrderController.CheckoutRequest request() {
        OrderController.CheckoutRequest request = new OrderController.CheckoutRequest();
        request.setStoreId(11L);
        request.setTotalAmount(BigDecimal.ONE);
        request.setPaymentMethod("WALLET");
        request.setItems(List.of());
        return request;
    }

    @Test
    void busyCheckoutIsRethrownSoItSurfacesAs503() {
        when(orderService.createOrder(anyLong(), any(), any(), any(), anyList(), any(), any(), any()))
                .thenThrow(new CustomException("503", "系統繁忙中，請稍後再試"));

        CustomException ex = assertThrows(CustomException.class,
                () -> controller.checkout(request(), 1L, null));
        assertEquals("503", ex.getCode());
    }

    @Test
    void businessRejectionStaysA400Response() {
        when(orderService.createOrder(anyLong(), any(), any(), any(), anyList(), any(), any(), any()))
                .thenThrow(new CustomException("400", "餘額不足"));

        ResponseEntity<?> response = controller.checkout(request(), 1L, null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("餘額不足", ((Map<?, ?>) response.getBody()).get("error"));
    }
}
