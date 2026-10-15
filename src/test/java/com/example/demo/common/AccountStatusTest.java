package com.example.demo.common;

import com.example.demo.entity.User;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帳號可用性規則（純函式，不載入 Spring context）。
 *
 * <p>HTTP 的 {@link JwtAuthenticationFilter} 與 WebSocket 的 StompAuthInterceptor 共用這一條規則（N7），
 * 因此這裡只測規則本身；兩端是否確實呼叫它，由各自的測試負責。
 * 儲存庫以 {@link Proxy} 代替，只允許規則會呼叫到的方法，不需要 mock 框架。
 */
class AccountStatusTest {

    @Test
    @DisplayName("未刪除的顧客可使用")
    void activeCustomerIsUsable() {
        assertTrue(AccountStatus.isActive(1L, "CUSTOMER",
                users(Map.of(1L, user(false))), noBrands(), noStores()));
    }

    @Test
    @DisplayName("已刪除的顧客不可使用，即使 token 仍在有效期內（N7）")
    void deletedCustomerIsNotUsable() {
        assertFalse(AccountStatus.isActive(1L, "CUSTOMER",
                users(Map.of(1L, user(true))), noBrands(), noStores()));
    }

    @Test
    @DisplayName("資料庫中不存在的顧客不可使用")
    void missingCustomerIsNotUsable() {
        assertFalse(AccountStatus.isActive(9L, "CUSTOMER", users(Map.of()), noBrands(), noStores()));
    }

    @Test
    @DisplayName("品牌與門市帳號只在資料列存在時可使用（N7）")
    void brandAndStoreAreUsableOnlyWhileTheyExist() {
        UserRepository users = users(Map.of());
        assertTrue(AccountStatus.isActive(10L, "BRAND", users, brands(Set.of(10L)), noStores()));
        assertFalse(AccountStatus.isActive(11L, "BRAND", users, brands(Set.of(10L)), noStores()));
        assertTrue(AccountStatus.isActive(20L, "STORE", users, noBrands(), stores(Set.of(20L))));
        assertFalse(AccountStatus.isActive(21L, "STORE", users, noBrands(), stores(Set.of(20L))));
    }

    @Test
    @DisplayName("角色比對不分大小寫")
    void roleMatchIgnoresCase() {
        assertTrue(AccountStatus.isActive(1L, "customer",
                users(Map.of(1L, user(false))), noBrands(), noStores()));
    }

    @Test
    @DisplayName("未知角色、缺少角色或缺少 id 一律不可使用")
    void unknownOrIncompleteIdentityIsNotUsable() {
        UserRepository users = users(Map.of(1L, user(false)));
        assertFalse(AccountStatus.isActive(1L, "ADMIN", users, noBrands(), noStores()));
        assertFalse(AccountStatus.isActive(1L, null, users, noBrands(), noStores()));
        assertFalse(AccountStatus.isActive(null, "CUSTOMER", users, noBrands(), noStores()));
    }

    // ── 測試用的儲存庫與資料 ─────────────────────────────────

    private static User user(boolean deleted) {
        User user = new User();
        user.setIsDeleted(deleted);
        return user;
    }

    private static UserRepository users(Map<Long, User> rows) {
        return proxy(UserRepository.class, (method, args) -> {
            expect(method, "findById");
            return Optional.ofNullable(rows.get((Long) args[0]));
        });
    }

    private static BrandRepository brands(Set<Long> ids) {
        return proxy(BrandRepository.class, (method, args) -> {
            expect(method, "existsById");
            return ids.contains((Long) args[0]);
        });
    }

    private static StoreRepository stores(Set<Long> ids) {
        return proxy(StoreRepository.class, (method, args) -> {
            expect(method, "existsById");
            return ids.contains((Long) args[0]);
        });
    }

    private static BrandRepository noBrands() {
        return brands(Set.of());
    }

    private static StoreRepository noStores() {
        return stores(Set.of());
    }

    @FunctionalInterface
    private interface Handler {
        Object handle(Method method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> handler.handle(method, args)));
    }

    /** 規則只會查詢單一列；若被要求做其他操作，代表規則變了，測試應該失敗 */
    private static void expect(Method method, String name) {
        if (!method.getName().equals(name)) {
            throw new UnsupportedOperationException("帳號規則不應呼叫 " + method.getName());
        }
    }
}
