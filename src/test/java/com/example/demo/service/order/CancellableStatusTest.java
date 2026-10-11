package com.example.demo.service.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellableStatusTest {

    @Test
    @DisplayName("只有 OPEN 與 SUBMITTED 可由發起人或團員取消品項／整單（大小寫與前後空白不敏感）")
    void openAndSubmittedAreCancellable() {
        assertTrue(OrderStatusGuard.isOpenOrSubmitted("OPEN"));
        assertTrue(OrderStatusGuard.isOpenOrSubmitted("submitted"));
        assertTrue(OrderStatusGuard.isOpenOrSubmitted(" Open "));
    }

    @Test
    @DisplayName("進入備餐之後，或被鎖定、已取消的訂單內容不可再被刪改")
    void laterStatesAreNotCancellable() {
        assertFalse(OrderStatusGuard.isOpenOrSubmitted("LOCKED"));
        assertFalse(OrderStatusGuard.isOpenOrSubmitted("PREPARING"));
        assertFalse(OrderStatusGuard.isOpenOrSubmitted("READY"));
        assertFalse(OrderStatusGuard.isOpenOrSubmitted("COMPLETED"));
        assertFalse(OrderStatusGuard.isOpenOrSubmitted("CANCELLED"));
        assertFalse(OrderStatusGuard.isOpenOrSubmitted(null));
        assertFalse(OrderStatusGuard.isOpenOrSubmitted(""));
    }
}
