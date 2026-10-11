package com.example.demo.service.order;

import com.example.demo.exception.CustomException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 純邏輯測試（不載入 Spring context）。
 *
 * <p>守住的是本輪 C-1：顧客端不得自行把訂單推到門市才該推的狀態。
 */
class OrderStatusGuardTest {

    @Test
    @DisplayName("現金單的付款通知（OPEN/SUBMITTED → PAID）可以通過")
    void allowsPaymentNotice() {
        assertEquals("PAID", OrderStatusGuard.requireCustomerTransition("OPEN", "PAID"));
        assertEquals("PAID", OrderStatusGuard.requireCustomerTransition("SUBMITTED", "paid"));
    }

    @Test
    @DisplayName("不得自行把訂單改成完成（否則可繞過門市流程直接評分）")
    void rejectsSelfCompletion() {
        assertThrows(CustomException.class,
                () -> OrderStatusGuard.requireCustomerTransition("SUBMITTED", "COMPLETED"));
        assertThrows(CustomException.class,
                () -> OrderStatusGuard.requireCustomerTransition("READY", "COMPLETED"));
        assertThrows(CustomException.class,
                () -> OrderStatusGuard.requireCustomerTransition("PREPARING", "READY"));
    }

    @Test
    @DisplayName("已結帳的揪團不得改回 OPEN（會被重複結帳）")
    void rejectsReopeningCheckedOutGroup() {
        assertThrows(CustomException.class,
                () -> OrderStatusGuard.requireCustomerTransition("SUBMITTED", "OPEN"));
        assertThrows(CustomException.class,
                () -> OrderStatusGuard.requireCustomerTransition("COMPLETED", "OPEN"));
    }

    @Test
    @DisplayName("取消要導向有退款邏輯的端點")
    void rejectsCancellationWithPointerToRefundPath() {
        CustomException error = assertThrows(CustomException.class,
                () -> OrderStatusGuard.requireCustomerTransition("SUBMITTED", "CANCELLED"));

        assertTrue(error.getMsg().contains("cancel/v2"), "錯誤訊息應指向會退款的端點");
    }

    @Test
    @DisplayName("空值與未知狀態一律拒絕")
    void rejectsBlankAndUnknown() {
        assertThrows(CustomException.class, () -> OrderStatusGuard.requireCustomerTransition("OPEN", null));
        assertThrows(CustomException.class, () -> OrderStatusGuard.requireCustomerTransition("OPEN", "  "));
        assertThrows(CustomException.class, () -> OrderStatusGuard.requireCustomerTransition("OPEN", "WHATEVER"));
        assertThrows(CustomException.class, () -> OrderStatusGuard.requireCustomerTransition(null, "PAID"));
    }
}
