package com.example.demo.service.order;

import com.example.demo.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 訂單編號（純函式，不載入 Spring context）。見 {@link OrderService#generateOrderNo()} 的註解。
 */
class OrderNoFormatTest {

    /** ORD + MMdd + 10 個大寫十六進位字元 */
    private static final Pattern FORMAT = Pattern.compile("ORD\\d{4}[0-9A-F]{10}");

    @Test
    @DisplayName("訂單編號為 ORD + MMdd + 10 位大寫十六進位（隨機碼只剩 4 位時，同一天 300 張單撞號機率約 50%）")
    void orderNumberHasTenHexRandomCharacters() {
        for (int i = 0; i < 50; i++) {
            String orderNo = OrderService.generateOrderNo();
            assertTrue(FORMAT.matcher(orderNo).matches(), () -> "訂單編號格式不符：" + orderNo);
        }
    }

    @Test
    @DisplayName("同一天 300 張單的訂單編號互不重複")
    void noCollisionsAcrossADaysWorthOfOrders() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            String orderNo = OrderService.generateOrderNo();
            assertTrue(seen.add(orderNo), () -> "訂單編號重複：" + orderNo);
        }
    }
}
