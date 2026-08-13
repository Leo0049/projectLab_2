package com.example.demo.controller;

import com.example.demo.exception.CustomException;
import com.example.demo.service.CouponService;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    /**
     * 身分一律取自 JwtAuthenticationFilter 寫入的 currentUserId。
     *
     * ⚠️ 這兩支端點原本都沒有擁有權檢查：`/user/{userId}` 可以讀任何人的券，
     * `/{id}` 可以讀任何一張券——而 `user_coupons.id` 是連續整數，
     * 等於直接把「有哪些券可以偷」列出來給攻擊者看（見 S-9）。
     */
    private void requireSelf(Long targetUserId, Long currentUserId) {
        if (currentUserId == null || !currentUserId.equals(targetUserId))
            throw new CustomException("403", "無權存取其他使用者的優惠券");
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<com.example.demo.dto.UserCouponDTO>> getUserCoupons(
            @PathVariable Long userId,
            @RequestParam(required = false) Long storeId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        return ResponseEntity.ok(couponService.getValidCouponsForUser(userId, storeId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getCouponById(@PathVariable Long id,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        // 擁有權檢查與轉 DTO 都留在 Service 的交易內，見該方法的說明
        return couponService.getCouponDTOByIdForUser(id, currentUserId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
