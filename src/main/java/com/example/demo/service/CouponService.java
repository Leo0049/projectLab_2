package com.example.demo.service;

import com.example.demo.entity.UserCoupon;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.User;
import com.example.demo.entity.Brand;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final ProductRepository productRepository;
    private final StoreRepository storeRepository;
    private final com.example.demo.repository.UserCouponRepository userCouponRepository;
    private final RedisLockService redisLockService;

    public List<com.example.demo.dto.UserCouponDTO> getValidCouponsForUser(Long userId, Long storeId) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);
        
        return userCouponRepository.findByUserIdAndStatus(userId, "unused").stream()
                .filter(uc -> uc.getExpiredAt() == null || uc.getExpiredAt().isAfter(now))
                .map(this::convertToDTO)
                .toList();
    }

    /**
     * 單張優惠券（含擁有權檢查）。
     *
     * <p>⚠️ 檢查一定要留在交易內。原本是 Controller 先自己 findById 取擁有者、
     * 再呼叫這支轉 DTO，兩次查詢各自開關交易，convertToDTO 讀 product 時
     * session 早就關了——open-in-view=false 之下固定 500（這顆雷的第十次）。
     *
     * @return 找不到時為 empty；存在但不屬於 currentUserId 時擲 403
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.Optional<com.example.demo.dto.UserCouponDTO> getCouponDTOByIdForUser(Long couponId,
            Long currentUserId) {
        return userCouponRepository.findById(couponId)
                .map(uc -> {
                    Long owner = uc.getUser() != null ? uc.getUser().getId() : null;
                    if (currentUserId == null || !currentUserId.equals(owner))
                        throw new com.example.demo.exception.CustomException("403", "無權存取其他使用者的優惠券");
                    return convertToDTO(uc);
                });
    }

    private com.example.demo.dto.UserCouponDTO convertToDTO(UserCoupon uc) {
        com.example.demo.dto.UserCouponDTO dto = new com.example.demo.dto.UserCouponDTO();
        dto.setCouponId(uc.getId());
        dto.setUserCouponId(uc.getId());
        dto.setDiscountAmount(uc.getDiscountAmount());
        dto.setObtainedAt(uc.getObtainedAt());
        dto.setExpiryDate(uc.getExpiredAt());
        
        if (uc.getProduct() != null) {
            dto.setProductId(uc.getProduct().getId());
            dto.setProductName(uc.getProduct().getName());
            if (uc.getProduct().getCategory() != null) {
                dto.setCategoryId(uc.getProduct().getCategory().getId());
                dto.setCategory(uc.getProduct().getCategory().getName());
            }
            dto.setFinalImageUrl(uc.getProduct().getCouponImageUrl() != null ? uc.getProduct().getCouponImageUrl() : uc.getProduct().getLogoUrl());
        }
        if (uc.getBrand() != null) {
            dto.setBrandId(uc.getBrand().getId());
            dto.setBrandName(uc.getBrand().getName());
        }
        return dto;
    }

    public boolean isValidForStore(Long userCouponId, Long storeId, List<Long> productIds) {
        if (userCouponId == null)
            return true;
            
        return userCouponRepository.findById(userCouponId)
                .map(c -> {
                    if (!"unused".equals(c.getStatus()) || (c.getExpiredAt() != null && c.getExpiredAt().isBefore(LocalDateTime.now()))) {
                        return false;
                    }

                    // 1. Brand Check: The store MUST belong to the same brand as the coupon
                    if (c.getBrand() != null) {
                        Long orderStoreBrandId = storeRepository.findById(storeId)
                                .map(s -> s.getBrand() != null ? s.getBrand().getId() : null).orElse(null);
                        if (!c.getBrand().getId().equals(orderStoreBrandId)) {
                            return false;
                        }
                    }

                    // 2. Category Check: At least one product in the order MUST belong to the coupon's category
                    if (c.getProduct() != null && c.getProduct().getCategory() != null) {
                        if (productIds == null || productIds.isEmpty()) {
                            return false;
                        }
                        boolean hasMatchingCategory = productRepository.findAllById(productIds).stream()
                                .anyMatch(p -> p.getCategory() != null && c.getProduct().getCategory().getId().equals(p.getCategory().getId()));
                        if (!hasMatchingCategory) {
                            return false;
                        }
                    }

                    // 3. Product Check: If coupon is restricted to a product, the order MUST contain that product
                    if (c.getProduct() != null) {
                        if (productIds == null || productIds.isEmpty() || !productIds.contains(c.getProduct().getId())) {
                            return false;
                        }
                    }

                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public Long playRoulette(Long userId, Long storeId) {
        // 1. Redis 鎖防止同時併發
        String lockKey = "lock:spin:" + userId;
        if (!redisLockService.acquireLock(lockKey, 10)) {
            throw new RuntimeException("系統繁忙中，請稍後再試");
        }
        try {
            // 2. 每日限制檢查 (Redis)
            String dailyKey = "spin:done:" + userId + ":" + LocalDate.now();
            long ttl = 86400; // 為簡單起見使用 24 小時，或計算到午夜
            if (!redisLockService.acquireLock(dailyKey, ttl)) {
                throw new RuntimeException("你今日已參加過轉盤遊戲");
            }

            // 3. 每日限制檢查 (DB)
            if (userCouponRepository.existsByUserIdAndObtainedDateAndCouponType(userId, LocalDate.now(), "WHEEL_GAME")) {
                throw new RuntimeException("資料庫顯示你今日已參加過轉盤遊戲");
            }

            List<ProductTemplate> products = productRepository.findByStoreId(storeId);
            if (products.isEmpty()) {
                throw new RuntimeException("該店目前沒有可抽獎的飲品");
            }

            ProductTemplate product = products.get((int) (Math.random() * products.size()));
            BigDecimal discount = new BigDecimal("5.00");

            User user = new User();
            user.setId(userId);

            UserCoupon uc = new UserCoupon();
            uc.setUser(user);
            uc.setBrand(product.getBrand());
            uc.setProduct(product);
            uc.setCouponType("WHEEL_GAME");
            uc.setDiscountAmount(discount);
            uc.setStatus("unused");
            uc.setObtainedAt(LocalDateTime.now());
            uc.setObtainedDate(LocalDate.now());
            uc.setExpiredAt(LocalDate.now().atTime(23, 59, 59));
            
            uc = userCouponRepository.save(uc);
            return uc.getId();
        } finally {
            redisLockService.releaseLock(lockKey);
        }
    }

    @Transactional
    public void expireOldCoupons() {
        List<UserCoupon> activeCoupons = userCouponRepository.findByStatusAndExpiredAtBefore("unused", LocalDateTime.now());
        for (UserCoupon c : activeCoupons) {
            c.setStatus("expired");
            userCouponRepository.save(c);
        }
    }
}
