package com.example.demo.service;

import com.example.demo.common.AccountStatus;
import com.example.demo.common.BrandApproval;
import com.example.demo.entity.Brand;
import com.example.demo.entity.Store;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 品牌與門市停用（stage 6）的資料庫層驗證：條件式更新、版本遞增、公開查詢的可見條件、登入與權杖。
 *
 * <p>每個測試建立自己的品牌與分店（名稱帶 UUID），不依賴示範資料，也不會影響其他測試。
 * 權杖是否有效以 {@link AccountStatus} 判斷，並傳入簽發時的版本，等同於 JWT 過濾器的檢查。
 */
@SpringBootTest
class StoreSuspensionIntegrationTest {

    @Autowired private AdminService adminService;
    @Autowired private BrandService brandService;
    @Autowired private StoreService storeService;
    @Autowired private PublicService publicService;
    @Autowired private BrandRepository brandRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String PASSWORD = "pw";

    private Brand newBrand(String approvalStatus) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Brand brand = new Brand();
        brand.setName("停用測試品牌-" + tag);
        brand.setAccount("suspend-brand-" + tag);
        brand.setPasswordHash(passwordEncoder.encode(PASSWORD));
        brand.setRole("BRAND");
        brand.setApprovalStatus(approvalStatus);
        return brandRepository.save(brand);
    }

    private Store newStore(Brand brand) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Store store = new Store();
        store.setBrand(brand);
        store.setStoreName("停用測試分店-" + tag);
        store.setAccount("suspend-store-" + tag);
        store.setPasswordHash(passwordEncoder.encode(PASSWORD));
        store.setRole("STORE");
        store.setStatus("active");
        return storeRepository.save(store);
    }

    private boolean usableBrand(Brand brand, int tokenVersion) {
        return AccountStatus.isActive(brand.getId(), "BRAND", tokenVersion,
                userRepository, brandRepository, storeRepository);
    }

    private boolean usableStore(Store store, int tokenVersion) {
        return AccountStatus.isActive(store.getId(), "STORE", tokenVersion,
                userRepository, brandRepository, storeRepository);
    }

    private boolean listedByBrand(Store store) {
        return storeRepository.findVisibleByBrandIdOrderByIdAsc(store.getBrand().getId(), PageRequest.of(0, 50))
                .stream().anyMatch(s -> s.getId().equals(store.getId()));
    }

    @Test
    @DisplayName("停用品牌：分店立即不可見、品牌與分店的舊權杖失效；恢復後舊權杖仍無效，須重新登入")
    void suspendingABrandHidesItsStoresAndRevokesTokens() {
        Brand brand = newBrand(BrandApproval.APPROVED);
        Store store = newStore(brand);
        int brandVersion = brand.getTokenVersion();
        int storeVersion = store.getTokenVersion();

        assertTrue(listedByBrand(store));
        assertTrue(usableBrand(brand, brandVersion));
        assertTrue(usableStore(store, storeVersion));

        adminService.suspendBrand(0L, brand.getId());

        assertFalse(usableBrand(brand, brandVersion));
        assertFalse(usableStore(store, storeVersion));
        assertFalse(listedByBrand(store));
        assertFalse(storeRepository.findAllVisible().stream().anyMatch(s -> s.getId().equals(store.getId())));
        assertFalse(storeRepository.searchByBrandName(brand.getName(), PageRequest.of(0, 50))
                .stream().anyMatch(s -> s.getId().equals(store.getId())));
        assertEquals(0L, storeRepository.countSearchByKeyword(brand.getName()));
        assertEquals("404", assertThrows(CustomException.class,
                () -> publicService.getBrandDetail(brand.getId())).getCode());
        assertEquals("404", assertThrows(CustomException.class,
                () -> publicService.getStoreDetail(store.getId(), null)).getCode());
        assertEquals("403", assertThrows(CustomException.class,
                () -> brandService.login(brand.getAccount(), PASSWORD)).getCode());
        assertEquals("403", assertThrows(CustomException.class,
                () -> storeService.login(store.getAccount(), PASSWORD)).getCode());
        assertEquals("409", assertThrows(CustomException.class,
                () -> adminService.suspendBrand(0L, brand.getId())).getCode());

        adminService.reinstateBrand(0L, brand.getId());

        // 恢復不會讓停用前的權杖重新有效
        assertFalse(usableBrand(brand, brandVersion));
        assertFalse(usableStore(store, storeVersion));
        assertTrue(listedByBrand(store));

        // 重新登入後取得的權杖是目前版本，可以使用
        int reloadedBrandVersion = brandRepository.findById(brand.getId()).orElseThrow().getTokenVersion();
        assertTrue(usableBrand(brand, reloadedBrandVersion));
        assertNotNull(brandService.login(brand.getAccount(), PASSWORD).get("token"));
        assertNotNull(storeService.login(store.getAccount(), PASSWORD).get("token"));
    }

    @Test
    @DisplayName("停用單一分店：只有該分店不可見、權杖失效；同品牌的其他分店與品牌不受影響")
    void suspendingAStoreLeavesTheBrandAndSiblingsAlone() {
        Brand brand = newBrand(BrandApproval.APPROVED);
        Store suspended = newStore(brand);
        Store sibling = newStore(brand);
        int suspendedVersion = suspended.getTokenVersion();
        int siblingVersion = sibling.getTokenVersion();

        adminService.suspendStore(0L, suspended.getId());

        assertFalse(usableStore(suspended, suspendedVersion));
        assertFalse(listedByBrand(suspended));
        assertEquals("404", assertThrows(CustomException.class,
                () -> publicService.getStoreDetail(suspended.getId(), null)).getCode());

        assertTrue(usableStore(sibling, siblingVersion));
        assertTrue(listedByBrand(sibling));
        assertTrue(usableBrand(brand, brand.getTokenVersion()));
        // 重複停用是衝突（409），不是查無此分店（404）；見 AdminService.suspendStore
        assertEquals("409", assertThrows(CustomException.class,
                () -> adminService.suspendStore(0L, suspended.getId())).getCode());

        adminService.reinstateStore(0L, suspended.getId());
        assertFalse(usableStore(suspended, suspendedVersion));
        assertTrue(listedByBrand(suspended));
    }

    @Test
    @DisplayName("待審品牌不能停用（409）")
    void pendingBrandCannotBeSuspended() {
        Brand pending = newBrand(BrandApproval.PENDING);

        assertEquals("409", assertThrows(CustomException.class,
                () -> adminService.suspendBrand(0L, pending.getId())).getCode());
    }
}
