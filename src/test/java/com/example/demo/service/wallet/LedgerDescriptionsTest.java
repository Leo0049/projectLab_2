package com.example.demo.service.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 個人訂單扣款說明的文字規則（純函式與純文字檢查，不載入 Spring context）。
 *
 * <p>第二支掃描 {@code OrderService.java}：揪團的團員字樣不得出現在個人訂單的程式碼內，
 * 且兩條個人扣款路徑都必須經過 {@link LedgerDescriptions}。測試工作目錄必須是專案根目錄（Maven 預設即是）。
 */
class LedgerDescriptionsTest {

    private static final Path ORDER_SERVICE = Path.of("src/main/java/com/example/demo/service/OrderService.java");
    private static final String GROUP_WORDING = "已扣除團員已付部分";
    private static final String HELPER_CALL = "LedgerDescriptions.personalOrderPayment(";

    @Test
    @DisplayName("個人訂單扣款說明只提到訂單本身")
    void personalOrderPaymentNamesTheOrderOnly() {
        assertEquals("個人訂單 #12 結帳扣款", LedgerDescriptions.personalOrderPayment(12L));
    }

    @Test
    @DisplayName("OrderService 不含揪團的團員字樣，且兩處個人訂單扣款都經過 LedgerDescriptions（文字掃描）")
    void orderServiceUsesThePersonalWordingEverywhere() throws IOException {
        String source = Files.readString(ORDER_SERVICE, StandardCharsets.UTF_8);

        assertFalse(source.contains(GROUP_WORDING), "OrderService 不應出現揪團的說法：" + GROUP_WORDING);
        // 建單的 escrow 扣款與逐品項扣款，兩處都必須經過 helper；少於兩處代表掃描或程式碼有問題
        int calls = countOccurrences(source, HELPER_CALL);
        assertTrue(calls >= 2, "個人訂單扣款應經 LedgerDescriptions，實際只找到 " + calls + " 處");
    }

    private static int countOccurrences(String haystack, String needle) {
        return (haystack.length() - haystack.replace(needle, "").length()) / needle.length();
    }
}
