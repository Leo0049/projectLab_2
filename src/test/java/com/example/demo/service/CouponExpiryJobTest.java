package com.example.demo.service;

import com.example.demo.entity.UserCoupon;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserCouponRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 到期券排程（stage 25，不載入 Spring context）。
 *
 * <p>到期券只剩 {@link ScheduledTasks#expireCouponsDaily()} 一支排程：經 {@link ExclusiveJobRunner} 互斥，
 * 交給 {@link CouponService#expireOldCoupons()}（交易內）把到期的未使用券改為 expired。
 */
@ExtendWith(MockitoExtension.class)
class CouponExpiryJobTest {

    @Mock
    private ExclusiveJobRunner exclusiveJobRunner;
    @Mock
    private OrderAutoRejectJob orderAutoRejectJob;
    @Mock
    private CouponService couponService;
    @Mock
    private UserCouponRepository userCouponRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private StoreRepository storeRepository;
    @Mock
    private RedisLockService redisLockService;

    @Test
    @DisplayName("到期排程以 coupon-expire-old、600 秒租約交給 ExclusiveJobRunner；取得鎖之前不碰服務")
    void expiryJobRunsThroughTheSharedRunnerUnderItsOwnLockName() {
        ScheduledTasks scheduledTasks = new ScheduledTasks(couponService, exclusiveJobRunner, orderAutoRejectJob);

        scheduledTasks.expireCouponsDaily();

        ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        verify(exclusiveJobRunner).runExclusively(eq("coupon-expire-old"), eq(600L), job.capture());
        verifyNoInteractions(couponService);

        // 取得鎖之後真正執行的才是到期處理
        job.getValue().run();
        verify(couponService).expireOldCoupons();
    }

    @Test
    @DisplayName("到期處理只查未使用的券：查到的每一張都改為 expired 並逐張寫回")
    void expireOldCouponsMarksEveryDueUnusedCouponExpiredAndSavesIt() {
        CouponService service = new CouponService(productRepository, storeRepository, userCouponRepository, redisLockService);
        UserCoupon first = coupon(1L);
        UserCoupon second = coupon(2L);
        when(userCouponRepository.findByStatusAndExpiredAtBefore(eq("unused"), any(LocalDateTime.class)))
                .thenReturn(List.of(first, second));

        service.expireOldCoupons();

        assertEquals("expired", first.getStatus());
        assertEquals("expired", second.getStatus());
        verify(userCouponRepository).save(first);
        verify(userCouponRepository).save(second);
    }

    private static UserCoupon coupon(long id) {
        UserCoupon coupon = new UserCoupon();
        coupon.setId(id);
        coupon.setStatus("unused");
        return coupon;
    }
}
