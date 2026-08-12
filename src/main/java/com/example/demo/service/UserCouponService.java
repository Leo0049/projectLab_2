package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class UserCouponService {

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private MenuCategoryRepository menuCategoryRepository;

    @Autowired
    private ProductTemplateRepository productTemplateRepository;

    private static final int MAX_SPIN_PER_DAY = 1;

    @Transactional
    public Map<String, Object> spin(Long userId, Long brandId) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        long todayCount = userCouponRepository.countByUserIdAndObtainedAtBetween(
                userId, startOfDay, endOfDay);

        if (todayCount >= MAX_SPIN_PER_DAY) {
            throw new CustomException("429", "今日轉盤次數已用完（每天最多 " + MAX_SPIN_PER_DAY + " 次）");
        }

        // 根據品牌篩選飲品，若未指定則全平台隨機
        List<ProductTemplate> products;
        if (brandId != null) {
            products = productTemplateRepository.findByBrandId(brandId).stream()
                    .filter(p -> Boolean.TRUE.equals(p.getIsEnabled()))
                    .toList();
        } else {
            products = productTemplateRepository.findAll().stream()
                    .filter(p -> Boolean.TRUE.equals(p.getIsEnabled()) && p.getBrand() != null)
                    .toList();
        }

        if (products.isEmpty()) {
            throw new CustomException("404", "目前該品牌沒有可用的優惠券內容，請稍後再試");
        }

        ProductTemplate selected = products.get(new Random().nextInt(products.size()));
        Brand brand = selected.getBrand();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("404", "找不到用戶"));

        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setUser(user);
        userCoupon.setBrand(brand);
        userCoupon.setProduct(selected);
        userCoupon.setCouponType("WHEEL_GAME");
        userCoupon.setStatus("unused");
        userCoupon.setObtainedAt(LocalDateTime.now());
        userCouponRepository.save(userCoupon);

        Map<String, Object> result = new HashMap<>();
        result.put("userCouponId", userCoupon.getId());
        result.put("brandName", brand.getName());
        result.put("productName", selected.getName());
        result.put("category", selected.getCategory() != null ? selected.getCategory().getName() : "指定飲品");
        result.put("couponImageUrl", selected.getCouponImageUrl());
        result.put("discountAmount", userCoupon.getDiscountAmount());
        result.put("expiryDate", userCoupon.getExpiredAt());
        result.put("remainSpins", MAX_SPIN_PER_DAY - todayCount - 1);
        return result;
    }

    @Transactional
    public List<Map<String, Object>> getMyCoupons(Long userId) {
        List<UserCoupon> userCoupons = userCouponRepository.findByUserId(userId);
        List<Map<String, Object>> resultList = new ArrayList<>();

        for (UserCoupon uc : userCoupons) {
            Map<String, Object> item = new HashMap<>();
            item.put("userCouponId", uc.getId());
            item.put("couponId", uc.getId()); // For compatibility with older frontend code if any
            item.put("brandId", uc.getBrand().getId());
            item.put("brandName", uc.getBrand().getName());
            item.put("productId", uc.getProduct() != null ? uc.getProduct().getId() : null);
            item.put("productName", uc.getProduct() != null ? uc.getProduct().getName() : "");
            item.put("categoryId", (uc.getProduct() != null && uc.getProduct().getCategory() != null) 
                    ? uc.getProduct().getCategory().getId() : null);
            item.put("couponImageUrl", uc.getProduct() != null ? uc.getProduct().getCouponImageUrl() : null);
            item.put("discountAmount", uc.getDiscountAmount());
            item.put("status", uc.getStatus());
            item.put("obtainedAt", uc.getObtainedAt());
            item.put("expiredAt", uc.getExpiredAt());
            item.put("expiryDate", uc.getExpiredAt()); // For compatibility
            resultList.add(item);
        }

        return resultList;
    }

    @Transactional
    public void useCoupon(Long userCouponId, Long userId) {
        UserCoupon userCoupon = userCouponRepository.findById(userCouponId)
                .orElseThrow(() -> new CustomException("404", "找不到此優惠券"));

        if (!userCoupon.getUser().getId().equals(userId)) {
            throw new CustomException("403", "無權限使用此優惠券");
        }

        if (!"unused".equals(userCoupon.getStatus())) {
            throw new CustomException("400", "此優惠券已使用或已過期");
        }

        if (userCoupon.getExpiredAt() != null && userCoupon.getExpiredAt().isBefore(LocalDateTime.now())) {
            throw new CustomException("400", "此優惠券已過期");
        }

        userCoupon.setStatus("used");
        userCoupon.setUsedAt(LocalDateTime.now());
        userCouponRepository.save(userCoupon);
    }

    public Map<String, Object> getSpinStatus(Long userId) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        long todayCount = userCouponRepository.countByUserIdAndObtainedAtBetween(
                userId, startOfDay, endOfDay);
        return Map.of(
                "todayCount", todayCount,
                "remainSpins", Math.max(0, MAX_SPIN_PER_DAY - todayCount),
                "maxSpins", MAX_SPIN_PER_DAY
        );
    }

    public List<Map<String, Object>> getGameWheelBrands() {
        List<ProductTemplate> products = productTemplateRepository.findAll();
        Set<Long> brandIds = new HashSet<>();
        for (ProductTemplate p : products) {
            if (p.getBrand() != null) brandIds.add(p.getBrand().getId());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Long bid : brandIds) {
            brandRepository.findById(bid).ifPresent(b -> {
                Map<String, Object> m = new HashMap<>();
                m.put("brandId", b.getId());
                m.put("brandName", b.getName());
                m.put("logoUrl", b.getLogoUrl());
                result.add(m);
            });
        }
        return result;
    }

    public Map<String, Object> getGameWheelMenu(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "找不到品牌"));
        List<MenuCategory> categories = menuCategoryRepository.findByBrandId(brandId);
        List<ProductTemplate> allProducts = productTemplateRepository.findByBrandId(brandId);

        List<Map<String, Object>> wheelOptions = new ArrayList<>();
        for (MenuCategory cat : categories) {
            long count = allProducts.stream()
                    .filter(p -> p.getCategory() != null && p.getCategory().getId().equals(cat.getId()))
                    .count();
            if (count == 0) continue;
            Map<String, Object> opt = new HashMap<>();
            opt.put("categoryId", cat.getId());
            opt.put("categoryName", cat.getName());
            opt.put("productCount", count);
            wheelOptions.add(opt);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("brandId", brandId);
        result.put("brandName", brand.getName());
        result.put("wheelOptions", wheelOptions);
        return result;
    }
}
