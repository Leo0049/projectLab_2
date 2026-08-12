package com.example.demo.controller;

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

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<com.example.demo.dto.UserCouponDTO>> getUserCoupons(
            @PathVariable Long userId,
            @RequestParam(required = false) Long storeId) {
        return ResponseEntity.ok(couponService.getValidCouponsForUser(userId, storeId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getCouponById(@PathVariable Long id) {
        return couponService.getCouponDTOById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
