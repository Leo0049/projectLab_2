package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class UserCouponService {

    @Autowired
    private UserCouponRepository userCouponRepository;

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

        // 原子消耗（N10）：條件式更新 WHERE status = 'unused'，受影響列數為 0 代表另一個請求已先用掉。
        // 上面的 status 檢查只是讀取時的快照，兩個請求同時讀到 unused 時，只能靠這一行決定誰成功。
        // 不可改回「setStatus 再 save」：那會把讀取時的舊實例寫回去，兩次都會成功。
        if (userCouponRepository.markUsedIfUnused(userCouponId, userId, LocalDateTime.now()) == 0) {
            throw new CustomException("400", "此優惠券已使用或已過期");
        }
    }
}
