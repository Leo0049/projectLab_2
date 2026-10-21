package com.example.demo.common;

import com.example.demo.entity.Brand;
import com.example.demo.entity.Store;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分店可見與可連到的規則（stage 6，純函式）。 */
class StoreVisibilityTest {

    @Test
    @DisplayName("分店與品牌都未停用才可連到；任一停用即不可連到")
    void reachableOnlyWhenNeitherIsSuspended() {
        assertTrue(StoreVisibility.isReachable(false, false));
        assertFalse(StoreVisibility.isReachable(true, false));
        assertFalse(StoreVisibility.isReachable(false, true));
        assertFalse(StoreVisibility.isReachable(true, true));
    }

    @Test
    @DisplayName("實體版本讀取分店與品牌兩個旗標；沒有品牌時只看分店本身")
    void entityOverloadReadsBothFlags() {
        Brand brand = new Brand();
        Store store = new Store();
        store.setBrand(brand);
        assertTrue(StoreVisibility.isReachable(store));

        brand.setSuspended(true);
        assertFalse(StoreVisibility.isReachable(store));

        brand.setSuspended(false);
        store.setSuspended(true);
        assertFalse(StoreVisibility.isReachable(store));

        store.setSuspended(false);
        store.setBrand(null);
        assertTrue(StoreVisibility.isReachable(store));
    }

    @Test
    @DisplayName("列表條件要求營業中，且分店與品牌都未停用")
    void listingFilterRequiresActiveAndBothFlagsOff() {
        assertTrue(StoreVisibility.FILTER.contains("s.status = 'active'"));
        assertTrue(StoreVisibility.FILTER.contains("s.suspended = FALSE"));
        assertTrue(StoreVisibility.FILTER.contains("b.suspended = FALSE"));
    }
}
