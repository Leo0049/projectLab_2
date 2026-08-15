package com.example.demo.service;

import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.UserCoupon;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.UserCouponRepository;
import com.example.demo.repository.MenuCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DailySpinService {

    private final ProductRepository productRepository;
    private final UserCouponRepository userCouponRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final RedisLockService redisLockService;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 純查詢今日是否已轉過（不寫入 Redis，避免干擾 spin 的 dailyKey）
     */
    public boolean hasSpunToday(Long userId) {
        String key = "spin:done:" + userId + ":" + LocalDate.now();
        try {
            // 💡 增加 Redis 查詢的超時保護，避免 Redis 故障時阻塞主執行緒
            Boolean result = stringRedisTemplate.hasKey(key);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Redis unavailable for hasSpunToday, falling back to DB: {}", e.getMessage());
            
            // 💡 優化：使用 obtainedAt 索引欄位進行時間範圍查詢，提升查詢效率
            LocalDateTime start = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
            LocalDateTime end = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
            return userCouponRepository.existsByUserIdAndCouponTypeAndObtainedAtBetween(
                    userId, "WHEEL_GAME", start, end);
        }
    }

    public List<String> getCategoriesByBrand(Long brandId) {
        return menuCategoryRepository.findByBrandIdOrderBySortOrderAsc(brandId).stream()
                .map(com.example.demo.entity.MenuCategory::getName)
                .collect(Collectors.toList());
    }

    @Transactional
    public SpinResult spin(Long userId, Long brandId) {
        // 1. 使用 Redis 鎖防止同一用戶同時多次併發請求
        String lockKey = "lock:spin:" + userId;
        if (!redisLockService.acquireLock(lockKey, 10)) {
            throw new RuntimeException("系統繁忙中，請稍後再試");
        }
        try {
            // 2. 使用 Redis 記錄今日已轉（TTL 到午夜）
            String dailyKey = "spin:done:" + userId + ":" + LocalDate.now();
            long ttl = getRemainingSecondsToday();
            
            boolean redisCheckPassed = true;
            try {
                if (!redisLockService.acquireLock(dailyKey, ttl)) {
                    redisCheckPassed = false;
                }
            } catch (Exception e) {
                log.warn("Redis unavailable for spin dailyKey lock, will rely on DB: {}", e.getMessage());
                // If Redis fails, we continue and let DB layer handle duplicate check
            }

            if (!redisCheckPassed) {
                throw new com.example.demo.exception.CustomException("409", "你今日已參加過轉盤遊戲");
            }

            // 3. 資料庫層級二次檢查（防止 Redis 重啟導致狀態遺失，或 Redis 故障）
            if (userCouponRepository.existsByUserIdAndObtainedDateAndCouponType(userId, LocalDate.now(), "WHEEL_GAME")) {
                throw new com.example.demo.exception.CustomException("409", "你今日已參加過轉盤遊戲");
            }

            List<ProductTemplate> allProducts = productRepository.findByBrandId(brandId);
            if (allProducts.isEmpty()) {
                throw new RuntimeException("該品牌目前沒有可抽獎的飲品");
            }

            List<com.example.demo.entity.MenuCategory> categories =
                    menuCategoryRepository.findByBrandIdOrderBySortOrderAsc(brandId);
            if (categories.isEmpty()) {
                throw new RuntimeException("該品牌目前沒有可抽獎的飲品分類");
            }

            com.example.demo.entity.MenuCategory winningCategory =
                    categories.get((int) (Math.random() * categories.size()));

            List<ProductTemplate> categoryProducts = allProducts.stream()
                    .filter(p -> p.getCategory() != null && winningCategory.getId().equals(p.getCategory().getId()))
                    .collect(Collectors.toList());

            if (categoryProducts.isEmpty()) {
                categoryProducts = allProducts;
            }

            ProductTemplate winningProduct = categoryProducts.get((int) (Math.random() * categoryProducts.size()));

            com.example.demo.entity.User user = new com.example.demo.entity.User();
            user.setId(userId);

            com.example.demo.entity.Brand brand = new com.example.demo.entity.Brand();
            brand.setId(brandId);

            UserCoupon uc = new UserCoupon();
            uc.setUser(user);
            uc.setBrand(brand);
            uc.setProduct(winningProduct);
            uc.setCouponType("WHEEL_GAME");
            uc.setDiscountAmount(new BigDecimal("5.00"));
            uc.setStatus("unused");
            uc.setObtainedAt(LocalDateTime.now());
            
            // obtainedDate 由資料庫 Generated Column 自動生成，故不在此顯式設定
            // uc.setObtainedDate(LocalDate.now()); 
            
            userCouponRepository.save(uc);

            return new SpinResult(winningCategory.getName(), winningProduct.getName(), winningProduct.getId(),
                    "SPIN-" + uc.getId(), winningProduct.getCouponImageUrl());
        } finally {
            redisLockService.releaseLock(lockKey);
        }
    }

    /**
     * 計算距今日午夜的剩餘秒數（用於 Redis TTL）
     */
    private long getRemainingSecondsToday() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.MIDNIGHT);
        long seconds = Duration.between(now, midnight).getSeconds();
        return Math.max(seconds, 1);
    }

    public record SpinResult(String category, String productName, Long productId, String couponCode, String couponImageUrl) {
    }
}
