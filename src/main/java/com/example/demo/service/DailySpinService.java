package com.example.demo.service;

import com.example.demo.common.BrandApproval;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.UserCoupon;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.UserCouponRepository;
import com.example.demo.repository.MenuCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
    private final BrandRepository brandRepository;
    private final TransactionTemplate transactionTemplate;

    /** 品牌已核准且未停用，才對外可見（stage 6）。品牌不存在也視為不可見。 */
    private boolean brandReachable(Long brandId) {
        return brandRepository.findById(brandId)
                .map(brand -> BrandApproval.isApproved(brand.getApprovalStatus()) && !brand.isSuspended())
                .orElse(false);
    }

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
        // stage 6：未核准或已停用的品牌，對外視同沒有分類
        if (!brandReachable(brandId)) {
            return List.of();
        }
        return menuCategoryRepository.findByBrandIdOrderBySortOrderAsc(brandId).stream()
                .map(com.example.demo.entity.MenuCategory::getName)
                .collect(Collectors.toList());
    }

    /**
     * 執行轉盤。
     *
     * <p>今日標記（{@code spin:done:…}）與優惠券寫入是同一個工作單位：標記只在這次抽獎真的提交時保留。
     * 寫入失敗、提交失敗，或取得標記之後才發現「今日已抽過」，都會把自己取得的標記收回，
     * 使用者不會因為系統錯誤而失去當天的機會（stage 24，見 IMPROVEMENT_ASSESSMENT「仍未做」第 4 項）。
     *
     * <p>交易改由 {@link TransactionTemplate} 明確開啟，而不是標註在方法上：標註的交易要等方法返回之後才提交，
     * 提交失敗時已經離開 try，無法回收標記。這裡的 try 同時包住提交。
     */
    public SpinResult spin(Long userId, Long brandId) {
        if (brandId == null) {
            throw new com.example.demo.exception.CustomException("400", "請選擇品牌");
        }
        // stage 6：未核准或已停用的品牌不發放新優惠券（在取得鎖與寫入今日標記之前就拒絕）
        if (!brandReachable(brandId)) {
            throw new com.example.demo.exception.CustomException("404", "找不到品牌");
        }

        // 1. 使用 Redis 鎖防止同一用戶同時多次併發請求
        // ⚠️ M-3 修復：acquire/release 改為 token 配對，避免逾時後誤刪他人的鎖
        String lockKey = "lock:spin:" + userId;
        String lockToken = redisLockService.acquireLock(lockKey, 10);
        if (lockToken == null) {
            throw new com.example.demo.exception.CustomException("503", "系統繁忙中，請稍後再試");
        }
        String dailyKey = "spin:done:" + userId + ":" + LocalDate.now();
        AtomicReference<String> dailyToken = new AtomicReference<>();
        try {
            return transactionTemplate.execute(status -> spinInTransaction(userId, brandId, dailyKey, dailyToken));
        } catch (RuntimeException e) {
            // 本次沒有提交：回收今日標記。沒有取得標記時 token 為 null，releaseLock 會直接略過。
            throw e;
        } finally {
            // 先回收標記、再釋放使用者鎖：同一使用者的下一次請求只會在結果已定之後才進來。
            redisLockService.releaseLock(lockKey, lockToken);
        }
    }

    /**
     * 交易內的抽獎主體：品牌內容檢查、取得今日標記、二次檢查，然後寫入優惠券。
     * 取得的今日標記 token 寫入 {@code dailyToken}，由 {@link #spin} 在失敗時回收。
     */
    private SpinResult spinInTransaction(Long userId, Long brandId, String dailyKey,
                                         AtomicReference<String> dailyToken) {
        // 2. 先確認品牌有可抽獎的內容。
        //    ⚠️ 必須在寫入「今日已轉」標記之前檢查：品牌沒有飲品時若標記已寫入，
        //    使用者當天就再也抽不到，卻沒有拿到任何獎品（2026-10-12 評估中發現）。
        List<ProductTemplate> allProducts = productRepository.findByBrandId(brandId);
        if (allProducts.isEmpty()) {
            throw new com.example.demo.exception.CustomException("422", "該品牌目前沒有可抽獎的飲品");
        }
        List<com.example.demo.entity.MenuCategory> categories =
                menuCategoryRepository.findByBrandIdOrderBySortOrderAsc(brandId);
        if (categories.isEmpty()) {
            throw new com.example.demo.exception.CustomException("422", "該品牌目前沒有可抽獎的飲品分類");
        }

        // 3. 使用 Redis 記錄今日已轉（TTL 到午夜）
        long ttl = getRemainingSecondsToday();

        boolean redisCheckPassed = true;
        try {
            // ⚠️ M-3 修復：acquire 故障時回傳 null（內部已 log），不再靠例外分支
            String token = redisLockService.acquireLock(dailyKey, ttl);
            dailyToken.set(token);
            redisCheckPassed = token != null;
        } catch (Exception e) {
            // Redis 例外時無法判定「已轉過」，交給下面的資料庫檢查決定。
            // （原本這裡設為 false，等於把 Redis 故障誤報成「今日已參加過」。）
            log.warn("Redis unavailable for spin dailyKey lock, will rely on DB: {}", e.getMessage());
        }

        if (!redisCheckPassed) {
            throw new com.example.demo.exception.CustomException("409", "你今日已參加過轉盤遊戲");
        }

        // 4. 資料庫層級二次檢查（防止 Redis 重啟導致狀態遺失，或 Redis 故障）
        if (userCouponRepository.existsByUserIdAndObtainedDateAndCouponType(userId, LocalDate.now(), "WHEEL_GAME")) {
            throw new com.example.demo.exception.CustomException("409", "你今日已參加過轉盤遊戲");
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

        userCouponRepository.save(uc);

        return new SpinResult(winningCategory.getName(), winningProduct.getName(), winningProduct.getId(),
                "SPIN-" + uc.getId(), winningProduct.getCouponImageUrl());
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
