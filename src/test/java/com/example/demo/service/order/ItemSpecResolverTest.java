package com.example.demo.service.order;

import com.example.demo.entity.BrandSpecSetting;
import com.example.demo.entity.ProductSpecRelation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定規格防竄改規則。
 *
 * 守住的是：商品只有唯一一種規格選項時（例如熱飲只賣熱的），
 * 用戶端送什麼都不算數。這條規則原本重複寫在 addItem 與 updateItem，
 * 兩邊都沒有測試——只要有一邊漏改，就會出現「冰的熱美式」這種做不出來的單。
 */
class ItemSpecResolverTest {

    private static ProductSpecRelation spec(String type, String name) {
        BrandSpecSetting setting = new BrandSpecSetting();
        setting.setSpecType(type);
        setting.setCustomName(name);
        ProductSpecRelation relation = new ProductSpecRelation();
        relation.setBrandSpec(setting);
        return relation;
    }

    @Test
    @DisplayName("只有唯一選項時，忽略用戶端送來的值")
    void fixedSpecOverridesClientValue() {
        ItemSpecResolver resolver = ItemSpecResolver.of(List.of(spec("ICE", "熱飲")));

        assertTrue(resolver.isFixed(ItemSpecResolver.ICE));
        assertEquals("熱飲", resolver.resolve(ItemSpecResolver.ICE, "全冰"));
        assertEquals("熱飲", resolver.resolve(ItemSpecResolver.ICE, null));
    }

    @Test
    @DisplayName("有多個選項時，採用用戶端送來的值")
    void multipleOptionsKeepClientValue() {
        ItemSpecResolver resolver = ItemSpecResolver.of(List.of(
                spec("ICE", "去冰"), spec("ICE", "少冰"), spec("ICE", "全冰")));

        assertFalse(resolver.isFixed(ItemSpecResolver.ICE));
        assertEquals("少冰", resolver.resolve(ItemSpecResolver.ICE, "少冰"));
    }

    @Test
    @DisplayName("各規格類型互不影響：冰量固定不會連帶影響甜度")
    void specTypesAreIndependent() {
        ItemSpecResolver resolver = ItemSpecResolver.of(List.of(
                spec("ICE", "熱飲"),
                spec("SWEETNESS", "無糖"), spec("SWEETNESS", "半糖")));

        assertEquals("熱飲", resolver.resolve(ItemSpecResolver.ICE, "全冰"));
        assertEquals("半糖", resolver.resolve(ItemSpecResolver.SWEETNESS, "半糖"));
    }

    @Test
    @DisplayName("spec_type 大小寫不一致仍要判斷得出來")
    void specTypeIsCaseInsensitive() {
        ItemSpecResolver resolver = ItemSpecResolver.of(List.of(spec("ice", "熱飲")));
        assertEquals("熱飲", resolver.resolve("ICE", "全冰"));
        assertEquals("熱飲", resolver.resolve("ice", "全冰"));
    }

    @Test
    @DisplayName("商品沒有設定規格時，原樣沿用用戶端的值")
    void noRelationsKeepsClientValue() {
        assertEquals("半糖", ItemSpecResolver.of(List.of()).resolve(ItemSpecResolver.SWEETNESS, "半糖"));
        assertEquals("半糖", ItemSpecResolver.of(null).resolve(ItemSpecResolver.SWEETNESS, "半糖"));
    }

    @Test
    @DisplayName("brandSpec 或 specType 為 null 的髒資料不可讓整批解析爆掉")
    void ignoresIncompleteRelations() {
        ProductSpecRelation noSetting = new ProductSpecRelation();
        ProductSpecRelation noType = new ProductSpecRelation();
        noType.setBrandSpec(new BrandSpecSetting());

        ItemSpecResolver resolver = ItemSpecResolver.of(List.of(noSetting, noType, spec("SIZE", "大杯")));

        assertEquals("大杯", resolver.resolve(ItemSpecResolver.SIZE, "中杯"));
    }

    @Test
    @DisplayName("resolveOrEmpty：新增品項時 null 要落成空字串，欄位不可為 null")
    void resolveOrEmptyNeverReturnsNull() {
        ItemSpecResolver resolver = ItemSpecResolver.of(List.of());
        assertNull(resolver.resolve(ItemSpecResolver.SIZE, null));
        assertEquals("", resolver.resolveOrEmpty(ItemSpecResolver.SIZE, null));
    }
}
