package com.example.demo.service.order;

import com.example.demo.entity.UserCoupon;
import com.example.demo.exception.CustomException;

/**
 * 優惠券能不能套用到這筆品項上。
 *
 * <p>把規則集中在這裡的理由：這是**金額**規則。券的適用範圍判斷錯了，
 * 等於讓使用者用 A 品牌的券折 B 品牌的飲料，帳直接對不起來。
 * 原本這段夾在 {@code applyCouponToItem} 那 112 行中間，
 * 與「拆單」「還原舊券」「重算 hash」混在一起，沒有辦法單獨驗證。
 */
public final class CouponEligibility {

    private CouponEligibility() {
    }

    /**
     * @param coupon         要套用的券
     * @param orderBrandId   這張訂單所屬門市的品牌
     * @param itemProductId  這筆品項的商品
     * @throws CustomException 不適用時，訊息即為要顯示給使用者的原因
     */
    public static void check(UserCoupon coupon, Long orderBrandId, Long itemProductId) {
        if (coupon == null) {
            throw new CustomException("404", "找不到優惠券");
        }
        // 品牌券：綁定品牌時，只能用在該品牌的門市
        if (coupon.getBrand() != null
                && !java.util.Objects.equals(coupon.getBrand().getId(), orderBrandId)) {
            throw new CustomException("400", "該優惠券不適用於此品牌");
        }
        // 指定商品券：綁定商品時，只能用在該商品
        if (coupon.getProduct() != null
                && !java.util.Objects.equals(coupon.getProduct().getId(), itemProductId)) {
            throw new CustomException("400", "該優惠券不適用於此商品");
        }
    }

    /** 已付款的品項不可再套券——折扣必須發生在扣款之前，否則要走退款流程 */
    public static void requireUnpaid(String paymentStatus) {
        if ("PAID".equalsIgnoreCase(paymentStatus)) {
            throw new CustomException("409", "此品項已付款，優惠券必須在付款前套用");
        }
    }
}
