package com.example.demo.service;

import com.example.demo.common.BrandApproval;
import com.example.demo.entity.Brand;
import com.example.demo.entity.MenuCategory;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.UserCoupon;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.MenuCategoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.UserCouponRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轉盤的今日標記與優惠券寫入是同一個工作單位（stage 24，見 IMPROVEMENT_ASSESSMENT「仍未做」第 4 項）：
 * 寫入失敗、提交失敗，或取得標記之後才發現已抽過，都必須回收本次取得的標記；提交成功則保留。
 * 交易由真實的 {@link TransactionTemplate} 開啟，只把 {@link PlatformTransactionManager} 換成 mock，
 * 因此「提交失敗」可以在不連資料庫的情況下重現。不載入 Spring context。
 */
class DailySpinMarkerRollbackTest {

    private static final long USER = 7L;
    private static final long BRAND = 5L;
    private static final String LOCK_KEY = "lock:spin:7";

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final UserCouponRepository userCouponRepository = mock(UserCouponRepository.class);
    private final MenuCategoryRepository menuCategoryRepository = mock(MenuCategoryRepository.class);
    private final RedisLockService redisLockService = mock(RedisLockService.class);
    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final BrandRepository brandRepository = mock(BrandRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final String dailyKey = "spin:done:" + USER + ":" + LocalDate.now();

    private DailySpinService service() {
        return new DailySpinService(productRepository, userCouponRepository, menuCategoryRepository,
                redisLockService, stringRedisTemplate, brandRepository, new TransactionTemplate(transactionManager));
    }

    @Test
    @DisplayName("寫入並提交成功：今日標記保留，使用者鎖最後才釋放")
    void keepsTodaysMarkerWhenTheSpinCommits() {
        arrangeSpinnableBrand();
        when(redisLockService.acquireLock(LOCK_KEY, 10)).thenReturn("user-token");
        when(redisLockService.acquireLock(eq(dailyKey), anyLong())).thenReturn("daily-token");
        saveAssignsId(99L);

        DailySpinService.SpinResult result = service().spin(USER, BRAND);

        assertEquals("SPIN-99", result.couponCode());
        verify(redisLockService, never()).releaseLock(eq(dailyKey), any());
        verify(redisLockService).releaseLock(LOCK_KEY, "user-token");
    }

    @Test
    @DisplayName("寫入優惠券失敗：回收今日標記，且先於使用者鎖釋放")
    void releasesTodaysMarkerWhenTheCouponWriteFails() {
        arrangeSpinnableBrand();
        when(redisLockService.acquireLock(LOCK_KEY, 10)).thenReturn("user-token");
        when(redisLockService.acquireLock(eq(dailyKey), anyLong())).thenReturn("daily-token");
        when(userCouponRepository.save(any(UserCoupon.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> service().spin(USER, BRAND));

        InOrder order = inOrder(redisLockService);
        order.verify(redisLockService).releaseLock(dailyKey, "daily-token");
        order.verify(redisLockService).releaseLock(LOCK_KEY, "user-token");
    }

    @Test
    @DisplayName("寫入已完成但提交失敗：同樣回收今日標記（標註在方法上的交易會漏掉這個情況）")
    void releasesTodaysMarkerWhenTheCommitFails() {
        arrangeSpinnableBrand();
        when(redisLockService.acquireLock(LOCK_KEY, 10)).thenReturn("user-token");
        when(redisLockService.acquireLock(eq(dailyKey), anyLong())).thenReturn("daily-token");
        saveAssignsId(99L);
        doThrow(new TransactionSystemException("commit failed")).when(transactionManager).commit(any());

        assertThrows(TransactionSystemException.class, () -> service().spin(USER, BRAND));

        verify(redisLockService).releaseLock(dailyKey, "daily-token");
        verify(redisLockService).releaseLock(LOCK_KEY, "user-token");
    }

    @Test
    @DisplayName("今日標記已被佔用：回 409，不寫入優惠券，也不回收別人的標記")
    void alreadySpunTodayRejectsWithoutWritingOrReleasing() {
        arrangeSpinnableBrand();
        when(redisLockService.acquireLock(LOCK_KEY, 10)).thenReturn("user-token");
        when(redisLockService.acquireLock(eq(dailyKey), anyLong())).thenReturn(null);

        CustomException e = assertThrows(CustomException.class, () -> service().spin(USER, BRAND));

        assertEquals("409", e.getCode());
        verify(userCouponRepository, never()).save(any());
        verify(redisLockService, never()).releaseLock(eq(dailyKey), isNotNull());
        verify(redisLockService).releaseLock(LOCK_KEY, "user-token");
    }

    @Test
    @DisplayName("品牌沒有可抽獎的飲品：回 422，且在取得今日標記之前就拒絕（不消耗當日次數）")
    void emptyBrandNeverClaimsTodaysMarker() {
        arrangeApprovedBrand();
        when(redisLockService.acquireLock(LOCK_KEY, 10)).thenReturn("user-token");
        when(productRepository.findByBrandId(BRAND)).thenReturn(List.of());

        CustomException e = assertThrows(CustomException.class, () -> service().spin(USER, BRAND));

        assertEquals("422", e.getCode());
        verify(redisLockService, never()).acquireLock(eq(dailyKey), anyLong());
        verify(redisLockService).releaseLock(LOCK_KEY, "user-token");
    }

    private void saveAssignsId(long id) {
        when(userCouponRepository.save(any(UserCoupon.class))).thenAnswer(inv -> {
            UserCoupon saved = inv.getArgument(0);
            saved.setId(id);
            return saved;
        });
    }

    private void arrangeApprovedBrand() {
        Brand brand = new Brand();
        brand.setId(BRAND);
        brand.setName("茶");
        brand.setApprovalStatus(BrandApproval.APPROVED);
        brand.setSuspended(false);
        when(brandRepository.findById(BRAND)).thenReturn(Optional.of(brand));
    }

    private void arrangeSpinnableBrand() {
        arrangeApprovedBrand();
        MenuCategory category = new MenuCategory();
        category.setId(3L);
        category.setName("奶茶");
        ProductTemplate product = new ProductTemplate();
        product.setId(9L);
        product.setName("珍珠奶茶");
        product.setCategory(category);
        when(productRepository.findByBrandId(BRAND)).thenReturn(List.of(product));
        when(menuCategoryRepository.findByBrandIdOrderBySortOrderAsc(BRAND)).thenReturn(List.of(category));
    }
}
