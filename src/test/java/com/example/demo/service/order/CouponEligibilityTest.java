package com.example.demo.service.order;

import com.example.demo.entity.Brand;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.UserCoupon;
import com.example.demo.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 優惠券適用範圍。這是金額規則：判斷錯了就是用 A 品牌的券折 B 品牌的飲料，
 * 帳會直接對不起來。
 */
class CouponEligibilityTest {

    private static UserCoupon coupon(Long brandId, Long productId) {
        UserCoupon c = new UserCoupon();
        if (brandId != null) {
            Brand b = new Brand();
            b.setId(brandId);
            c.setBrand(b);
        }
        if (productId != null) {
            ProductTemplate p = new ProductTemplate();
            p.setId(productId);
            c.setProduct(p);
        }
        return c;
    }

    @Test
    @DisplayName("品牌與商品都相符：可以套用")
    void matchingCouponPasses() {
        assertDoesNotThrow(() -> CouponEligibility.check(coupon(1L, 10L), 1L, 10L));
    }

    @Test
    @DisplayName("跨品牌使用要被擋下")
    void wrongBrandRejected() {
        CustomException e = assertThrows(CustomException.class,
                () -> CouponEligibility.check(coupon(1L, null), 2L, 10L));
        assertEquals("該優惠券不適用於此品牌", e.getMsg());
    }

    @Test
    @DisplayName("指定商品券用在別的商品要被擋下")
    void wrongProductRejected() {
        CustomException e = assertThrows(CustomException.class,
                () -> CouponEligibility.check(coupon(1L, 10L), 1L, 11L));
        assertEquals("該優惠券不適用於此商品", e.getMsg());
    }

    @Test
    @DisplayName("沒綁品牌／商品的通用券，任何品項都能用")
    void unrestrictedCouponPasses() {
        assertDoesNotThrow(() -> CouponEligibility.check(coupon(null, null), 7L, 70L));
    }

    @Test
    @DisplayName("找不到券時要有明確錯誤，不可是 NullPointerException")
    void nullCouponRejected() {
        CustomException e = assertThrows(CustomException.class,
                () -> CouponEligibility.check(null, 1L, 10L));
        assertEquals("找不到優惠券", e.getMsg());
    }

    @Test
    @DisplayName("已付款的品項不可再套券")
    void paidItemCannotApplyCoupon() {
        assertThrows(CustomException.class, () -> CouponEligibility.requireUnpaid("PAID"));
        assertThrows(CustomException.class, () -> CouponEligibility.requireUnpaid("paid"));
        assertDoesNotThrow(() -> CouponEligibility.requireUnpaid("UNPAID"));
        assertDoesNotThrow(() -> CouponEligibility.requireUnpaid(null));
    }
}
