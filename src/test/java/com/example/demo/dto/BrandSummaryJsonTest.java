package com.example.demo.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 品牌清單的 JSON 契約（不載入 Spring context）。
 *
 * <p>守住的是今天踩到的那一類錯誤：後端與前端對欄位名稱的認知不同。
 * 前端 {@code index.html} 的轉盤讀 {@code brandId}／{@code brandName}，這裡鎖死它們。
 */
class BrandSummaryJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("JSON 欄位名稱是前端契約：brandId、brandName、logoUrl，且沒有多餘欄位")
    void serializesTheFieldNamesTheFrontendReads() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(new BrandSummary(1L, "50嵐", "/logo.png")));

        assertEquals(Set.of("brandId", "brandName", "logoUrl"), fieldNames(json));
        assertTrue(json.get("brandId").isNumber(), "brandId 必須是數字");
        assertEquals("50嵐", json.get("brandName").asText());
        assertEquals("/logo.png", json.get("logoUrl").asText());
    }

    @Test
    @DisplayName("沒有 logo 時 logoUrl 為 null（前端以預設圖處理）")
    void allowsAMissingLogo() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(new BrandSummary(2L, "CoCo", null)));
        assertTrue(json.get("logoUrl").isNull());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
