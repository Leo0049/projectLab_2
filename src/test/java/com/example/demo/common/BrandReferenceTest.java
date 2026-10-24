package com.example.demo.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 品牌參照（N-33，2026-10-24）：門市只能切換本品牌的飲品與配料，缺值一律不符。
 */
class BrandReferenceTest {

    @Test
    @DisplayName("同一品牌的 id 相符")
    void sameBrandMatches() {
        assertTrue(BrandReference.belongsToBrand(7L, 7L));
    }

    @Test
    @DisplayName("不同品牌的 id 不符")
    void otherBrandDoesNotMatch() {
        assertFalse(BrandReference.belongsToBrand(7L, 8L));
    }

    @Test
    @DisplayName("被參照的品項沒有品牌時不符")
    void missingReferencedBrandDoesNotMatch() {
        assertFalse(BrandReference.belongsToBrand(null, 7L));
    }

    @Test
    @DisplayName("分店沒有品牌時不符")
    void missingStoreBrandDoesNotMatch() {
        assertFalse(BrandReference.belongsToBrand(7L, null));
    }

    @Test
    @DisplayName("兩邊都沒有品牌時不符（fail-closed）")
    void bothMissingDoesNotMatch() {
        assertFalse(BrandReference.belongsToBrand(null, null));
    }

    @Test
    @DisplayName("比較的是值而不是物件參照：超過 Long 快取範圍的 id 也相符")
    void comparesValueNotReference() {
        assertTrue(BrandReference.belongsToBrand(1000L, Long.valueOf(1000L)));
    }
}
