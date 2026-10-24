package com.example.demo.common;

/**
 * 品牌參照的判斷（N-33，2026-10-24）：門市只能切換本品牌的飲品與配料。
 *
 * 純函式，不碰資料庫。任一方缺少品牌 id 一律視為不符（fail-closed），
 * 因此分店沒有品牌、或被參照的品項沒有品牌時，都拒絕切換。
 */
public final class BrandReference {

    private BrandReference() {
    }

    /**
     * @param referencedBrandId 被參照的飲品或配料所屬的品牌 id
     * @param brandId           發出請求的分店所屬的品牌 id
     * @return 兩者都有值且相等時為 true
     */
    public static boolean belongsToBrand(Long referencedBrandId, Long brandId) {
        return referencedBrandId != null && brandId != null && referencedBrandId.equals(brandId);
    }
}
