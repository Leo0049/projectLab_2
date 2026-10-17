package com.example.demo.service.order;

import com.example.demo.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 結帳失敗的分類（純函式，不載入 Spring context）。 */
class CheckoutFailureTest {

    @Test
    @DisplayName("只有明確標示 503 的 CustomException 是系統忙碌")
    void explicit503IsBusy() {
        assertTrue(CheckoutFailure.isBusy(new CustomException(CheckoutFailure.BUSY_CODE, "系統繁忙中，請稍後再試")));
    }

    @Test
    @DisplayName("業務拒絕、預設 500 的業務例外與一般 RuntimeException 都不是系統忙碌，即使訊息相同")
    void businessRejectionsAreNotBusy() {
        assertFalse(CheckoutFailure.isBusy(new CustomException("400", "餘額不足")));
        assertFalse(CheckoutFailure.isBusy(new CustomException("無效的優惠券")));
        assertFalse(CheckoutFailure.isBusy(new RuntimeException("系統繁忙中，請稍後再試")));
        assertFalse(CheckoutFailure.isBusy(null));
    }
}
