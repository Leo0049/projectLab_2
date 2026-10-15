package com.example.demo.common;

import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;

/**
 * 帳號目前是否仍可使用：顧客未刪除，品牌與門市帳號仍存在（停用機制見 N-brand-store disable）。
 *
 * <p>HTTP 的 {@link JwtAuthenticationFilter} 與 WebSocket 的 {@code StompAuthInterceptor}（CONNECT 與每次 SUBSCRIBE）
 * 共用這一條規則。兩邊各寫一份，就會出現「HTTP 已經擋掉的帳號，推播還能照收」的漏洞（N7）。
 */
public final class AccountStatus {

    private AccountStatus() {
    }

    public static boolean isActive(Long userId, String role, UserRepository users,
                                   BrandRepository brands, StoreRepository stores) {
        if (userId == null || role == null) {
            return false;
        }
        if ("CUSTOMER".equalsIgnoreCase(role)) {
            return users.findById(userId)
                    .map(user -> !Boolean.TRUE.equals(user.getIsDeleted()))
                    .orElse(false);
        }
        if ("BRAND".equalsIgnoreCase(role)) {
            return brands.existsById(userId);
        }
        if ("STORE".equalsIgnoreCase(role)) {
            return stores.existsById(userId);
        }
        return false;
    }
}
