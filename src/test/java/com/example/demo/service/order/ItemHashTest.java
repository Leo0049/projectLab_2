package com.example.demo.service.order;

import com.example.demo.entity.OrderItemTopping;
import com.example.demo.entity.OrderItemToppingId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 品項識別碼。前端靠它把相同的品項合併成「x2」，
 * 所以「什麼算相同」必須precise：規格或配料只要差一項就得是不同的 hash，
 * 而配料的**加入順序**不能影響結果。
 */
class ItemHashTest {

    private static OrderItemTopping topping(String name) {
        OrderItemToppingId id = new OrderItemToppingId();
        id.setToppingNameSnapshot(name);
        OrderItemTopping t = new OrderItemTopping();
        t.setId(id);
        return t;
    }

    @Test
    @DisplayName("完全相同的品項算出相同的 hash")
    void identicalItemsShareHash() {
        assertEquals(
                ItemHash.of(1L, "半糖", "少冰", "大杯", "珍珠", null),
                ItemHash.of(1L, "半糖", "少冰", "大杯", "珍珠", null));
    }

    @Test
    @DisplayName("配料順序不同不影響結果——否則同樣兩杯不會被合併")
    void toppingOrderDoesNotMatter() {
        String a = ItemHash.toppingsKey(List.of(topping("珍珠"), topping("椰果")));
        String b = ItemHash.toppingsKey(List.of(topping("椰果"), topping("珍珠")));

        assertEquals(a, b);
        assertEquals(ItemHash.of(1L, "半糖", "少冰", "大杯", a, null),
                     ItemHash.of(1L, "半糖", "少冰", "大杯", b, null));
    }

    @Test
    @DisplayName("任一規格不同就要是不同的 hash")
    void differentSpecsProduceDifferentHash() {
        String base = ItemHash.of(1L, "半糖", "少冰", "大杯", "", null);

        assertNotEquals(base, ItemHash.of(2L, "半糖", "少冰", "大杯", "", null));
        assertNotEquals(base, ItemHash.of(1L, "無糖", "少冰", "大杯", "", null));
        assertNotEquals(base, ItemHash.of(1L, "半糖", "去冰", "大杯", "", null));
        assertNotEquals(base, ItemHash.of(1L, "半糖", "少冰", "中杯", "", null));
        assertNotEquals(base, ItemHash.of(1L, "半糖", "少冰", "大杯", "珍珠", null));
    }

    @Test
    @DisplayName("套了優惠券的那一杯必須與沒套的分開顯示")
    void couponSplitsTheHash() {
        assertNotEquals(
                ItemHash.of(1L, "半糖", "少冰", "大杯", "", null),
                ItemHash.of(1L, "半糖", "少冰", "大杯", "", 99L));
    }

    @Test
    @DisplayName("沒有配料時回傳空字串，不可為 null")
    void emptyToppingsKey() {
        assertEquals("", ItemHash.toppingsKey(null));
        assertEquals("", ItemHash.toppingsKey(List.of()));
    }

    @Test
    @DisplayName("配料名稱含中文時仍穩定（UTF-8 編碼一致）")
    void stableForNonAsciiNames() {
        String once = ItemHash.of(1L, "微糖", "微冰", "大杯", "黑糖粉圓", null);
        String twice = ItemHash.of(1L, "微糖", "微冰", "大杯", "黑糖粉圓", null);
        assertEquals(once, twice);
        assertEquals(32, once.length(), "MD5 十六進位應為 32 字元");
    }
}
