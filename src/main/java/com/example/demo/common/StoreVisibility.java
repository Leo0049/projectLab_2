package com.example.demo.common;

import com.example.demo.entity.Store;

/**
 * 分店對顧客是否可見、可否連到的規則（停用機制，stage 6）。
 *
 * <p>停用只看兩個旗標：分店本身的 {@code suspended}，以及所屬品牌的 {@code suspended}。
 * 任一為真，分店就不出現在公開列表、詳情與菜單中，也不能下單。
 *
 * <p>列表查詢必須把 {@link #FILTER} 放進 WHERE。JPQL 與原生 SQL 共用同一個字串，
 * 而且計數查詢（分頁的 total）與列表查詢必須使用完全相同的條件，否則 hasNext 會與實際列表對不上。
 * 單筆讀取則用 {@link #isReachable(boolean, boolean)}。
 */
public final class StoreVisibility {

    /**
     * 列表查詢的條件：營業狀態為 active，且分店與品牌都未停用。
     * 別名必須是 {@code s}（stores）與 {@code b}（brands）；使用者的 FROM 子句要 JOIN 品牌。
     */
    public static final String FILTER = "s.status = 'active' AND s.suspended = FALSE AND b.suspended = FALSE";

    private StoreVisibility() {
    }

    /**
     * 單筆讀取（店家詳情、菜單、下單）是否可連到：分店與品牌都未停用即可。
     * 不看營業狀態，關店中的分店仍可檢視（與列表不同）；下單另外檢查營業狀態。
     */
    public static boolean isReachable(boolean storeSuspended, boolean brandSuspended) {
        return !storeSuspended && !brandSuspended;
    }

    /**
     * 實體版本。品牌是 LAZY 關聯，呼叫端必須在交易內，或查詢已用 EntityGraph 載入品牌。
     * 沒有交易時請改用 {@link #isReachable(boolean, boolean)} 搭配 StoreRepository#isBrandSuspended。
     */
    public static boolean isReachable(Store store) {
        return isReachable(store.isSuspended(), store.getBrand() != null && store.getBrand().isSuspended());
    }
}
