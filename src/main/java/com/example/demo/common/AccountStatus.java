package com.example.demo.common;

import com.example.demo.repository.AdminAccountRepository;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;

/**
 * 權杖所代表的帳號目前是否仍可使用。下列條件都必須成立：
 *
 * <ol>
 *   <li>帳號列存在；顧客另外不得已刪除（N7）；品牌必須已核准（N-28）。</li>
 *   <li>權杖的角色與帳號列的角色相符。角色變更後，舊權杖不再代表同一種身分（N13）。</li>
 *   <li>權杖的版本等於帳號目前的 tokenVersion。改密碼與刪除帳號時會遞增，舊權杖因此失效（N13）。</li>
 * </ol>
 *
 * <p>HTTP 的 {@link JwtAuthenticationFilter} 與 WebSocket 的 {@code StompAuthInterceptor}（CONNECT 與每次 SUBSCRIBE）
 * 共用這一條規則。兩邊各寫一份，就會出現「HTTP 已經擋掉的帳號，推播還能照收」的漏洞（N7）。
 *
 * <p>管理員（ADMIN，N-28）只在傳入 {@link AdminAccountRepository} 時可用，目前只有 HTTP 過濾器會傳入。
 * WebSocket 不接受管理員連線（管理後台不使用推播），因此使用不含管理員儲存庫的版本，管理員權杖一律不可用。
 *
 * <p>品牌與門市的停用機制見 N-brand-store disable；停用旗標日後應加在這裡，而不是另寫一處。
 */
public final class AccountStatus {

    private AccountStatus() {
    }

    /** 不含管理員儲存庫的版本（WebSocket 使用）：管理員權杖一律不可用（fail-closed）。 */
    public static boolean isActive(Long userId, String role, int tokenVersion,
                                   UserRepository users, BrandRepository brands, StoreRepository stores) {
        return isActive(userId, role, tokenVersion, users, brands, stores, null);
    }

    public static boolean isActive(Long userId, String role, int tokenVersion,
                                   UserRepository users, BrandRepository brands, StoreRepository stores,
                                   AdminAccountRepository admins) {
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
                    .map(brand -> true
                            && isCurrent(brand.getRole(), brand.getTokenVersion(), role, tokenVersion))
                    .orElse(false);
        }
        if ("STORE".equalsIgnoreCase(role)) {
            return stores.findById(userId)
                    .map(store -> isCurrent(store.getRole(), store.getTokenVersion(), role, tokenVersion))
                    .orElse(false);
        }
        if ("ADMIN".equalsIgnoreCase(role)) {
            return admins == null || admins.findById(userId)
                    .map(admin -> isCurrent(admin.getRole(), admin.getTokenVersion(), role, tokenVersion))
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
