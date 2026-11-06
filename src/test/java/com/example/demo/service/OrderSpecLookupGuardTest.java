package com.example.demo.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建單的固定規格查詢守衛（文字掃描，不載入 Spring context）。
 *
 * <p>建單的固定規格判斷若逐品項呼叫 {@code findByIdProductId}，一張 N 項的訂單就會查 N 次（寫入路徑的 N+1）。
 * 這裡要求 {@code OrderService} 不再逐品項查詢，且 createOrder 與 placeOrder 都經由同一個批次 helper。
 * 測試工作目錄必須是專案根目錄（Maven 預設即是）。
 */
class OrderSpecLookupGuardTest {

    private static final Path ORDER_SERVICE = Path.of("src/main/java/com/example/demo/service/OrderService.java");
    private static final String PER_ITEM_LOOKUP = "findByIdProductId(";
    private static final String BATCH_HELPER = "loadSpecsByProduct(";

    @Test
    @DisplayName("OrderService 不得逐品項呼叫 findByIdProductId（文字掃描）")
    void orderServiceDoesNotLookUpSpecsPerItem() throws IOException {
        String source = Files.readString(ORDER_SERVICE, StandardCharsets.UTF_8);

        assertFalse(source.contains(PER_ITEM_LOOKUP),
                "建單的固定規格應改用批次 helper（" + BATCH_HELPER + "），不可逐品項呼叫 " + PER_ITEM_LOOKUP);
    }

    @Test
    @DisplayName("建單的兩條路徑都經由批次 helper：一次宣告加兩次呼叫（文字掃描）")
    void bothOrderPathsUseTheBatchHelper() throws IOException {
        String source = Files.readString(ORDER_SERVICE, StandardCharsets.UTF_8);

        // 宣告 1 次 + createOrder 1 次 + placeOrder 1 次；少於 3 代表有一條路徑沒有走批次
        int occurrences = countOccurrences(source, BATCH_HELPER);
        assertTrue(occurrences >= 3, "應有 1 次宣告與 2 次呼叫，實際找到 " + occurrences + " 處");
    }

    private static int countOccurrences(String haystack, String needle) {
        return (haystack.length() - haystack.replace(needle, "").length()) / needle.length();
    }
}
