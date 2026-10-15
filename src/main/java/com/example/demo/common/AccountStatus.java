package com.example.demo.common;

import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;

/**
 * 權杖所代表的帳號目前是否仍可使用。下列條件都必須成立：
 *
 * <ol>
 *   <li>帳號列存在；顧客另外不得已刪除（N7）。</li>
 *   <li>權杖的角色與帳號列的角色相符。角色變更後，舊權杖不再代表同一種身分（N13）。</li>
 *   <li>權杖的版本等於帳號目前的 tokenVersion。改密碼與刪除帳號時會遞增，舊權杖因此失效（N13）。</li>
 * </ol>
 *
 * <p>HTTP 的 {@link JwtAuthenticationFilter} 與 WebSocket 的 {@code StompAuthInterceptor}（CONNECT 與每次 SUBSCRIBE）
 * 共用這一條規則。兩邊各寫一份，就會出現「HTTP 已經擋掉的帳號，推播還能照收」的漏洞（N7）。
 *
 * <p>品牌與門市的停用機制見 N-brand-store disable；停用旗標日後應加在這裡，而不是另寫一處。
 */
public final class AccountStatus {

    private AccountStatus() {
    }

    public static boolean isActive(Long userId, String role, int tokenVersion,
                                   UserRepository users, BrandRepository brands, StoreRepository stores) {
        if (userId == null || role == null) {
            return false;
        }
        if ("CUSTOMER".equalsIgnoreCase(role)) {
            return users.findById(userId)
                    .map(user -> !Boolean.TRUE.equals(user.getIsDeleted())
                            && isCurrent(user.getRole(), user.getTokenVersion(), role, tokenVersion))
                    .orElse(false);
        }
        if ("BRAND".equalsIgnoreCase(role)) {
            return brands.findById(userId)
                    .map(brand -> isCurrent(brand.getRole(), brand.getTokenVersion(), role, tokenVersion))
                    .orElse(false);
        }
        if ("STORE".equalsIgnoreCase(role)) {
            return stores.findById(userId)
                    .map(store -> isCurrent(store.getRole(), store.getTokenVersion(), role, tokenVersion))
                    .orElse(false);
        }
        return false;
    }

    /** 帳號列的角色必須與權杖相符（大小寫不計），且權杖版本必須等於帳號目前版本。角色為 null 一律不相符。 */
    private static boolean isCurrent(String accountRole, int accountVersion, String tokenRole, int tokenVersion) {
        return accountRole != null
                && accountRole.equalsIgnoreCase(tokenRole)
                && accountVersion == tokenVersion;
    }
}
