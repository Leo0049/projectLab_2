package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.entity.UserCoupon;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.UserCouponRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 優惠券的使用（N10）：消耗必須是條件式的原子更新（WHERE status = 'unused'），不可先讀後寫。
 *
 * <p>以假的儲存庫模擬競爭：讀取時兩個請求都看到 unused，但原子更新只有第一次會成功。
 * 第二次必須失敗，而且不可以 save() 一個讀取時的舊實例把狀態寫回去。
 */
class UserCouponUseTest {

    private static final long OWNER = 1L;
    private static final long COUPON = 100L;

    @Test
    @DisplayName("同一張券第二次使用時回 400，即使讀取時仍看到 unused（N10）")
    void secondUseFailsEvenIfTheEarlierReadStillSaysUnused() {
        Fixture f = new Fixture(coupon(null), 1);

        assertDoesNotThrow(() -> f.service.useCoupon(COUPON, OWNER));
        CustomException second = assertThrows(CustomException.class, () -> f.service.useCoupon(COUPON, OWNER));

        assertEquals("此優惠券已使用或已過期", second.getMessage());
        assertEquals(2, f.updateCalls.get());
        assertEquals(0, f.saveCalls.get());
    }

    @Test
    @DisplayName("成功消耗時只做條件式更新，不以讀取時的實例 save() 寫回（N10）")
    void successfulUseDoesNotSaveAStaleEntity() {
        Fixture f = new Fixture(coupon(null), 1);

        f.service.useCoupon(COUPON, OWNER);

        assertEquals(1, f.updateCalls.get());
        assertEquals(0, f.saveCalls.get());
    }

    @Test
    @DisplayName("不是擁有者使用時回 403，且不嘗試消耗")
    void nonOwnerIsRefusedBeforeAnyUpdate() {
        Fixture f = new Fixture(coupon(null), 1);

        CustomException e = assertThrows(CustomException.class, () -> f.service.useCoupon(COUPON, 2L));

        assertEquals("無權限使用此優惠券", e.getMessage());
        assertEquals(0, f.updateCalls.get());
    }

    @Test
    @DisplayName("已過期的券回 400，且不嘗試消耗")
    void expiredCouponIsRefusedBeforeAnyUpdate() {
        Fixture f = new Fixture(coupon(LocalDateTime.now().minusDays(1)), 1);

        CustomException e = assertThrows(CustomException.class, () -> f.service.useCoupon(COUPON, OWNER));

        assertEquals("此優惠券已過期", e.getMessage());
        assertEquals(0, f.updateCalls.get());
    }

    private static UserCoupon coupon(LocalDateTime expiredAt) {
        User owner = new User();
        owner.setId(OWNER);
        UserCoupon coupon = new UserCoupon();
        coupon.setId(COUPON);
        coupon.setUser(owner);
        coupon.setStatus("unused");
        coupon.setExpiredAt(expiredAt);
        return coupon;
    }

    /**
     * 假儲存庫：findById 永遠回傳同一個實例（讀取當下仍為 unused，模擬競爭中的舊快照）；
     * markUsedIfUnused 只有前 {@code winners} 次回傳 1，之後回傳 0（列已不是 unused）。
     */
    private static final class Fixture {
        final UserCouponService service = new UserCouponService();
        final AtomicInteger updateCalls = new AtomicInteger();
        final AtomicInteger saveCalls = new AtomicInteger();

        Fixture(UserCoupon stored, int winners) {
            UserCouponRepository repo = (UserCouponRepository) Proxy.newProxyInstance(
                    UserCouponRepository.class.getClassLoader(),
                    new Class<?>[]{UserCouponRepository.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "findById":
                                return Optional.of(stored);
                            case "markUsedIfUnused":
                                return updateCalls.incrementAndGet() <= winners ? 1 : 0;
                            case "save":
                                saveCalls.incrementAndGet();
                                return stored;
                            default:
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
            ReflectionTestUtils.setField(service, "userCouponRepository", repo);
        }
    }
}
