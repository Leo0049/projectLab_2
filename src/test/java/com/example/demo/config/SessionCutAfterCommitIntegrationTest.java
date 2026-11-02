package com.example.demo.config;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountsRevokedEvent;
import com.example.demo.common.AccountsSuspendedEvent;
import com.example.demo.common.BrandApproval;
import com.example.demo.entity.Brand;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AdminService;
import com.example.demo.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切斷連線的交易語意，以真實的 Spring 交易管理器（JPA）驗證（stage 22）。
 *
 * <p>既有的監聽器單元測試直接呼叫處理方法，不經過交易，因此看不出 {@code AFTER_COMMIT} 的時機。
 * 這裡覆蓋四種結局：提交前不切斷、提交後才切斷、回滾不切斷、提交失敗不切斷。
 * 在這組測試中，只有提交失敗能分辨 {@code BEFORE_COMMIT} 與 {@code AFTER_COMMIT}：
 * 回滾（包括只設定 rollback-only）不會觸發 {@code BEFORE_COMMIT}。
 *
 * <p>最後四個測試走真實服務（登出所有裝置、改密碼、停用品牌），驗證整段流程。
 * 測試類別本身不標註交易：類別上的交易會包住整個測試，{@code AFTER_COMMIT} 就永遠不會在測試中觸發，
 * 所以交易一律由 {@link TransactionTemplate} 明確開啟。
 */
@SpringBootTest
class SessionCutAfterCommitIntegrationTest {

    private static final String PASSWORD = "pw";

    /** 交易的結局：提交、回滾（設定 rollback-only）、提交失敗（提交時的 flush 失敗）。 */
    private enum Outcome { COMMIT, ROLLBACK, COMMIT_FAILS }

    @Autowired private StompSessionRegistry registry;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserService userService;
    @Autowired private AdminService adminService;
    @Autowired private UserRepository userRepository;
    @Autowired private BrandRepository brandRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /** 本測試登錄過的工作階段，結束後移除，避免影響其他測試。 */
    private final List<String> registered = new ArrayList<>();

    @AfterEach
    void unregisterSessions() {
        registered.forEach(registry::unregister);
        registered.clear();
    }

    @Test
    @DisplayName("撤銷事件：提交前不切斷，提交後才切斷列出的顧客，其他顧客不受影響")
    void revocationCutsOnlyAfterTheCommit() {
        long customerId = freshId();
        String target = register("CUSTOMER", customerId);
        String bystander = register("CUSTOMER", customerId + 1);

        endTransaction(Outcome.COMMIT, () -> {
            publisher.publishEvent(AccountsRevokedEvent.forCustomer(customerId));
            assertFalse(registry.isCut(target), "提交前不應切斷");
        });

        assertTrue(registry.isCut(target), "提交後應切斷");
        assertFalse(registry.isCut(bystander), "其他顧客不受影響");
    }

    @Test
    @DisplayName("撤銷交易回滾：不切斷，連線維持原狀")
    void revocationIsNotCutWhenTheTransactionRollsBack() {
        long customerId = freshId();
        String target = register("CUSTOMER", customerId);

        endTransaction(Outcome.ROLLBACK, () -> {
            publisher.publishEvent(AccountsRevokedEvent.forCustomer(customerId));
            assertFalse(registry.isCut(target), "提交前不應切斷");
        });

        assertFalse(registry.isCut(target), "回滾後不應切斷");
    }

    @Test
    @DisplayName("撤銷交易提交失敗：不切斷（BEFORE_COMMIT 會在提交前就先切斷）")
    void revocationIsNotCutWhenTheCommitFails() {
        long customerId = freshId();
        String target = register("CUSTOMER", customerId);

        endTransaction(Outcome.COMMIT_FAILS, () -> {
            publisher.publishEvent(AccountsRevokedEvent.forCustomer(customerId));
            assertFalse(registry.isCut(target), "提交前不應切斷");
        });

        assertFalse(registry.isCut(target), "提交失敗後不應切斷");
    }

    @Test
    @DisplayName("停用事件：提交後才切斷列出的帳號，且只切斷相同角色（同 id 的顧客不受影響）")
    void suspensionCutsOnlyTheListedRoleAfterTheCommit() {
        long storeId = freshId();
        String store = register("STORE", storeId);
        String sameIdCustomer = register("CUSTOMER", storeId);
        String bystander = register("STORE", storeId + 1);

        endTransaction(Outcome.COMMIT, () -> {
            publisher.publishEvent(suspension("STORE", storeId));
            assertFalse(registry.isCut(store), "提交前不應切斷");
        });

        assertTrue(registry.isCut(store), "提交後應切斷");
        assertFalse(registry.isCut(sameIdCustomer), "角色不同即使 id 相同也不切斷（N13）");
        assertFalse(registry.isCut(bystander), "其他分店不受影響");
    }

    @Test
    @DisplayName("停用交易回滾：不切斷")
    void suspensionIsNotCutWhenTheTransactionRollsBack() {
        long storeId = freshId();
        String store = register("STORE", storeId);

        endTransaction(Outcome.ROLLBACK, () -> {
            publisher.publishEvent(suspension("STORE", storeId));
            assertFalse(registry.isCut(store), "提交前不應切斷");
        });

        assertFalse(registry.isCut(store), "回滾後不應切斷");
    }

    @Test
    @DisplayName("停用交易提交失敗：不切斷（BEFORE_COMMIT 會在提交前就先切斷）")
    void suspensionIsNotCutWhenTheCommitFails() {
        long storeId = freshId();
        String store = register("STORE", storeId);

        endTransaction(Outcome.COMMIT_FAILS, () -> {
            publisher.publishEvent(suspension("STORE", storeId));
            assertFalse(registry.isCut(store), "提交前不應切斷");
        });

        assertFalse(registry.isCut(store), "提交失敗後不應切斷");
    }

    @Test
    @DisplayName("登出所有裝置（真實服務）：方法回傳後該顧客已切斷，其他顧客不受影響")
    void logoutAllDevicesCutsTheCustomerOnceItReturns() {
        User target = newCustomer(PASSWORD);
        User bystander = newCustomer(PASSWORD);
        String targetSession = register("CUSTOMER", target.getId());
        String bystanderSession = register("CUSTOMER", bystander.getId());

        userService.logoutAllDevices(target.getId());

        assertTrue(registry.isCut(targetSession), "方法回傳（即已提交）後應切斷");
        assertFalse(registry.isCut(bystanderSession), "其他顧客不受影響");
    }

    @Test
    @DisplayName("改密碼（真實服務）：方法回傳後該顧客已切斷")
    void changePasswordCutsTheCustomerOnceItReturns() {
        User target = newCustomer(PASSWORD);
        String targetSession = register("CUSTOMER", target.getId());

        userService.changePassword(target.getId(), PASSWORD, "new-" + PASSWORD);

        assertTrue(registry.isCut(targetSession), "方法回傳（即已提交）後應切斷");
    }

    @Test
    @DisplayName("改密碼時舊密碼錯誤：交易回滾，連線不切斷，權杖版本也不變")
    void failedChangePasswordCutsNothing() {
        User target = newCustomer(PASSWORD);
        String targetSession = register("CUSTOMER", target.getId());
        int versionBefore = userRepository.findById(target.getId()).orElseThrow().getTokenVersion();

        assertThrows(RuntimeException.class,
                () -> userService.changePassword(target.getId(), "wrong-" + PASSWORD, "new-" + PASSWORD));

        assertFalse(registry.isCut(targetSession), "失敗的改密碼不應切斷");
        assertEquals(versionBefore, userRepository.findById(target.getId()).orElseThrow().getTokenVersion(),
                "失敗的改密碼不應遞增版本");
    }

    @Test
    @DisplayName("停用品牌（真實服務）：方法回傳後品牌與底下分店已切斷，同 id 的顧客不受影響")
    void suspendBrandCutsTheBrandAndItsStoresOnceItReturns() {
        Brand brand = newBrand();
        Store store = newStore(brand);
        String brandSession = register("BRAND", brand.getId());
        String storeSession = register("STORE", store.getId());
        String sameIdCustomer = register("CUSTOMER", brand.getId());

        adminService.suspendBrand(0L, brand.getId());

        assertTrue(registry.isCut(brandSession), "品牌應切斷");
        assertTrue(registry.isCut(storeSession), "底下的分店應切斷");
        assertFalse(registry.isCut(sameIdCustomer), "角色不同即使 id 相同也不切斷（N13）");
    }

    /**
     * 在交易中執行 publish（它負責發布事件與提交前的斷言），再依 outcome 結束交易。
     * 提交失敗的情境：在回呼的最後把一個受管的實體改成別人的手機號碼，唯一索引的錯誤要到提交時的 flush 才會出現。
     */
    private void endTransaction(Outcome outcome, Runnable publish) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        switch (outcome) {
            case COMMIT -> tx.executeWithoutResult(status -> publish.run());
            case ROLLBACK -> tx.executeWithoutResult(status -> {
                publish.run();
                status.setRollbackOnly();
            });
            case COMMIT_FAILS -> {
                User holder = newCustomer(PASSWORD);
                User victim = newCustomer(PASSWORD);
                AtomicBoolean callbackFinished = new AtomicBoolean();
                assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
                    publish.run();
                    User managed = userRepository.findById(victim.getId()).orElseThrow();
                    managed.setPhone(holder.getPhone());
                    callbackFinished.set(true);
                }));
                assertTrue(callbackFinished.get(), "回呼必須先完成，失敗才是發生在提交階段");
            }
        }
    }

    private String register(String role, long accountId) {
        String sessionId = "cut-it-" + UUID.randomUUID();
        registry.register(sessionId, new AccountRef(role, accountId));
        registered.add(sessionId);
        return sessionId;
    }

    /** 不需要資料列的情境使用的隨機 id，避免與其他測試或真實資料列撞號。 */
    private static long freshId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 9_000_000_000_000L);
    }

    private static AccountsSuspendedEvent suspension(String role, long accountId) {
        return new AccountsSuspendedEvent(List.of(new AccountRef(role, accountId)));
    }

    private User newCustomer(String password) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setName("切斷測試顧客-" + tag);
        user.setPhone(String.format("09%08d", ThreadLocalRandom.current().nextInt(100_000_000)));
        user.setPasswordHash(passwordEncoder.encode(password));
        return userRepository.save(user);
    }

    private Brand newBrand() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Brand brand = new Brand();
        brand.setName("切斷測試品牌-" + tag);
        brand.setAccount("cut-brand-" + tag);
        brand.setPasswordHash(passwordEncoder.encode(PASSWORD));
        brand.setRole("BRAND");
        brand.setApprovalStatus(BrandApproval.APPROVED);
        return brandRepository.save(brand);
    }

    private Store newStore(Brand brand) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Store store = new Store();
        store.setBrand(brand);
        store.setStoreName("切斷測試分店-" + tag);
        store.setAccount("cut-store-" + tag);
        store.setPasswordHash(passwordEncoder.encode(PASSWORD));
        store.setRole("STORE");
        store.setStatus("active");
        return storeRepository.save(store);
    }
}
