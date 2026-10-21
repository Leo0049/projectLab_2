package com.example.demo.common;

import com.example.demo.entity.AdminAccount;
import com.example.demo.entity.Brand;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.repository.AdminAccountRepository;
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
 * <p>HTTP 的 {@link JwtAuthenticationFilter} 與 WebSocket 的 StompAuthInterceptor 共用這一條規則（N7、N13），
 * 因此這裡只測規則本身；兩端是否確實帶入權杖版本，由各自的測試負責。
 * 儲存庫以 {@link Proxy} 代替，只允許規則會呼叫到的方法，不需要 mock 框架。
 */
class AccountStatusTest {

    @Test
    @DisplayName("未刪除、角色相符、版本相符的顧客可使用")
    void activeCustomerIsUsable() {
        assertTrue(AccountStatus.isActive(1L, "CUSTOMER", 0,
                users(Map.of(1L, customer(false, 0))), noBrands(), noStores()));
    }

    @Test
    @DisplayName("已刪除的顧客不可使用，即使 token 仍在有效期內（N7）")
    void deletedCustomerIsNotUsable() {
        assertFalse(AccountStatus.isActive(1L, "CUSTOMER", 0,
                users(Map.of(1L, customer(true, 0))), noBrands(), noStores()));
    }

    @Test
    @DisplayName("資料庫中不存在的顧客不可使用")
    void missingCustomerIsNotUsable() {
        assertFalse(AccountStatus.isActive(9L, "CUSTOMER", 0, users(Map.of()), noBrands(), noStores()));
    }

    @Test
    @DisplayName("品牌與門市帳號只在資料列存在時可使用（N7）")
    void brandAndStoreAreUsableOnlyWhileTheyExist() {
        UserRepository users = users(Map.of());
        assertTrue(AccountStatus.isActive(10L, "BRAND", 0,
                users, brands(Map.of(10L, brand("BRAND", 0))), noStores()));
        assertFalse(AccountStatus.isActive(11L, "BRAND", 0,
                users, brands(Map.of(10L, brand("BRAND", 0))), noStores()));
        assertTrue(AccountStatus.isActive(20L, "STORE", 0,
                users, noBrands(), stores(Map.of(20L, store("STORE", 0)))));
        assertFalse(AccountStatus.isActive(21L, "STORE", 0,
                users, noBrands(), stores(Map.of(20L, store("STORE", 0)))));
    }

    @Test
    @DisplayName("角色比對不分大小寫")
    void roleMatchIgnoresCase() {
        assertTrue(AccountStatus.isActive(1L, "customer", 0,
                users(Map.of(1L, customer(false, 0))), noBrands(), noStores()));
    }

    @Test
    @DisplayName("未知角色、缺少角色或缺少 id 一律不可使用")
    void unknownOrIncompleteIdentityIsNotUsable() {
        UserRepository users = users(Map.of(1L, customer(false, 0)));
        assertFalse(AccountStatus.isActive(1L, "ROOT", 0, users, noBrands(), noStores()));
        assertFalse(AccountStatus.isActive(1L, null, 0, users, noBrands(), noStores()));
        assertFalse(AccountStatus.isActive(null, "CUSTOMER", 0, users, noBrands(), noStores()));
    }

    @Test
    @DisplayName("改密碼之前簽發的權杖不可使用，三種帳號皆然（N13）")
    void staleTokenVersionIsRejectedForEveryAccountType() {
        assertFalse(AccountStatus.isActive(1L, "CUSTOMER", 0,
                users(Map.of(1L, customer(false, 1))), noBrands(), noStores()));
        assertFalse(AccountStatus.isActive(10L, "BRAND", 0,
                users(Map.of()), brands(Map.of(10L, brand("BRAND", 1))), noStores()));
        assertFalse(AccountStatus.isActive(20L, "STORE", 0,
                users(Map.of()), noBrands(), stores(Map.of(20L, store("STORE", 1)))));
    }

    @Test
    @DisplayName("版本相符（含改密碼後重新登入取得的新權杖）時可使用（N13）")
    void currentTokenVersionIsAccepted() {
        assertTrue(AccountStatus.isActive(1L, "CUSTOMER", 3,
                users(Map.of(1L, customer(false, 3))), noBrands(), noStores()));
        assertTrue(AccountStatus.isActive(10L, "BRAND", 2,
                users(Map.of()), brands(Map.of(10L, brand("BRAND", 2))), noStores()));
        assertTrue(AccountStatus.isActive(20L, "STORE", 5,
                users(Map.of()), noBrands(), stores(Map.of(20L, store("STORE", 5)))));
    }

    @Test
    @DisplayName("帳號的角色被改成其他值後，舊權杖不可使用（N13）")
    void roleChangeInvalidatesTheOldToken() {
        assertFalse(AccountStatus.isActive(1L, "CUSTOMER", 0,
                users(Map.of(1L, customerWithRole("BRAND"))), noBrands(), noStores()));
        assertFalse(AccountStatus.isActive(10L, "BRAND", 0,
                users(Map.of()), brands(Map.of(10L, brand("STORE", 0))), noStores()));
        assertFalse(AccountStatus.isActive(20L, "STORE", 0,
                users(Map.of()), noBrands(), stores(Map.of(20L, store("BRAND", 0)))));
    }

    @Test
    @DisplayName("顧客 token 不會去比對門市資料列，即使 id 相同（門市與顧客的 id 會撞號，N13）")
    void customerTokenNeverMatchesAStoreRowWithTheSameId() {
        assertFalse(AccountStatus.isActive(7L, "CUSTOMER", 0,
                users(Map.of()), noBrands(), stores(Map.of(7L, store("STORE", 0)))));
    }

    @Test
    @DisplayName("待審或未通過的品牌不可使用，即使權杖仍有效（N-28）")
    void brandThatIsNotApprovedIsNotUsable() {
        assertFalse(AccountStatus.isActive(10L, "BRAND", 0,
                users(Map.of()), brands(Map.of(10L, brandWithStatus("PENDING"))), noStores()));
        assertFalse(AccountStatus.isActive(10L, "BRAND", 0,
                users(Map.of()), brands(Map.of(10L, brandWithStatus("REJECTED"))), noStores()));
        assertTrue(AccountStatus.isActive(10L, "BRAND", 0,
                users(Map.of()), brands(Map.of(10L, brandWithStatus("APPROVED"))), noStores()));
    }

    @Test
    @DisplayName("管理員權杖只在傳入管理員儲存庫時可用（HTTP）；未傳入（WebSocket）一律不可用（N-28）")
    void adminIsUsableOnlyWhenAdminRepositoryIsSupplied() {
        assertTrue(AccountStatus.isActive(30L, "ADMIN", 0,
                users(Map.of()), noBrands(), noStores(), admins(Map.of(30L, admin(0)))));
        assertFalse(AccountStatus.isActive(30L, "ADMIN", 0,
                users(Map.of()), noBrands(), noStores()));
    }

    @Test
    @DisplayName("管理員改過密碼後，舊權杖不可使用（N13）；資料列不存在也不可使用")
    void staleOrMissingAdminIsNotUsable() {
        assertFalse(AccountStatus.isActive(30L, "ADMIN", 0,
                users(Map.of()), noBrands(), noStores(), admins(Map.of(30L, admin(1)))));
        assertFalse(AccountStatus.isActive(31L, "ADMIN", 0,
                users(Map.of()), noBrands(), noStores(), admins(Map.of(30L, admin(0)))));
    }

    @Test
    @DisplayName("管理員權杖不會去比對顧客、品牌或門市資料列（id 會撞號）")
    void adminTokenNeverMatchesOtherAccountTypes() {
        assertFalse(AccountStatus.isActive(7L, "ADMIN", 0,
                users(Map.of(7L, customer(false, 0))), brands(Map.of(7L, brand("BRAND", 0))),
                stores(Map.of(7L, store("STORE", 0))), admins(Map.of())));
    }

    @Test
    @DisplayName("被停用的品牌不可使用，即使已核准、權杖版本也相符（stage 6）")
    void suspendedBrandIsNotUsable() {
        Brand suspended = brand("BRAND", 0);
        suspended.setSuspended(true);
        assertFalse(AccountStatus.isActive(10L, "BRAND", 0,
                users(Map.of()), brands(Map.of(10L, suspended)), noStores()));
    }

    @Test
    @DisplayName("被停用的分店不可使用，即使版本相符（stage 6）")
    void suspendedStoreIsNotUsable() {
        Store suspended = store("STORE", 0);
        suspended.setSuspended(true);
        assertFalse(AccountStatus.isActive(20L, "STORE", 0,
                users(Map.of()), noBrands(), stores(Map.of(20L, suspended))));
    }

    @Test
    @DisplayName("所屬品牌被停用的分店不可使用，即使分店本身未停用、權杖版本也相符（stage 6）")
    void storeUnderSuspendedBrandIsNotUsable() {
        // 品牌停用時分店版本會一併遞增；這裡再檢查品牌，確保停用立即生效，不依賴版本號的時間差
        assertFalse(AccountStatus.isActive(20L, "STORE", 0,
                users(Map.of()), noBrands(), stores(Map.of(20L, store("STORE", 0)), Set.of(20L))));
        assertTrue(AccountStatus.isActive(20L, "STORE", 0,
                users(Map.of()), noBrands(), stores(Map.of(20L, store("STORE", 0)), Set.of(21L))));
    }

    // ── 測試用的儲存庫與資料 ─────────────────────────────────

    private static User customer(boolean deleted, int tokenVersion) {
        User user = new User();
        user.setIsDeleted(deleted);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    private static User customerWithRole(String role) {
        User user = customer(false, 0);
        user.setRole(role);
        return user;
    }

    private static Brand brand(String role, int tokenVersion) {
        Brand brand = new Brand();
        brand.setRole(role);
        brand.setTokenVersion(tokenVersion);
        return brand;
    }

    private static Brand brandWithStatus(String approvalStatus) {
        Brand brand = brand("BRAND", 0);
        brand.setApprovalStatus(approvalStatus);
        return brand;
    }

    private static Store store(String role, int tokenVersion) {
        Store store = new Store();
        store.setRole(role);
        store.setTokenVersion(tokenVersion);
        return store;
    }

    private static AdminAccount admin(int tokenVersion) {
        AdminAccount admin = new AdminAccount();
        admin.setRole("ADMIN");
        admin.setTokenVersion(tokenVersion);
        return admin;
    }

    private static UserRepository users(Map<Long, User> rows) {
        return proxy(UserRepository.class, (method, args) -> {
            expect(method, "findById");
            return Optional.ofNullable(rows.get((Long) args[0]));
        });
    }

    private static BrandRepository brands(Map<Long, Brand> rows) {
        return proxy(BrandRepository.class, (method, args) -> {
            expect(method, "findById");
            return Optional.ofNullable(rows.get((Long) args[0]));
        });
    }

    private static StoreRepository stores(Map<Long, Store> rows) {
        return stores(rows, Set.of());
    }

    /** storesWithSuspendedBrand：所屬品牌已停用的分店 id，供 {@code isBrandSuspended} 回傳。 */
    private static StoreRepository stores(Map<Long, Store> rows, Set<Long> storesWithSuspendedBrand) {
        return proxy(StoreRepository.class, (method, args) -> {
            if ("isBrandSuspended".equals(method.getName())) {
                return storesWithSuspendedBrand.contains((Long) args[0]);
            }
            expect(method, "findById");
            return Optional.ofNullable(rows.get((Long) args[0]));
        });
    }

    private static AdminAccountRepository admins(Map<Long, AdminAccount> rows) {
        return proxy(AdminAccountRepository.class, (method, args) -> {
            expect(method, "findById");
            return Optional.ofNullable(rows.get((Long) args[0]));
        });
    }

    private static BrandRepository noBrands() {
        return brands(Map.of());
    }

    private static StoreRepository noStores() {
        return stores(Map.of());
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
