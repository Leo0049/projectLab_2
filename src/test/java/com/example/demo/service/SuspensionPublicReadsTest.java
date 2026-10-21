package com.example.demo.service;

import com.example.demo.common.BrandApproval;
import com.example.demo.entity.Brand;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Store;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.BrandSpecSettingRepository;
import com.example.demo.repository.BrandToppingSettingRepository;
import com.example.demo.repository.MenuCategoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.ProductSpecRelationRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.SpecMasterRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.StoreSpecSettingRepository;
import com.example.demo.repository.StoreToppingStatusRepository;
import com.example.demo.repository.UserCouponRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 停用對對外讀取與轉盤的影響（stage 6）：商品清單、商品詳情、規格、客製化選項與轉盤，
 * 不可讀到已停用的分店或品牌。建構子參數順序與欄位宣告順序一致（見 ProductService、DailySpinService）。
 */
class SuspensionPublicReadsTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductTemplateRepository productTemplateRepository = mock(ProductTemplateRepository.class);
    private final ProductSpecRelationRepository productSpecRelationRepository = mock(ProductSpecRelationRepository.class);
    private final BrandSpecSettingRepository brandSpecSettingRepository = mock(BrandSpecSettingRepository.class);
    private final MenuCategoryRepository menuCategoryRepository = mock(MenuCategoryRepository.class);
    private final SpecMasterRepository specMasterRepository = mock(SpecMasterRepository.class);
    private final BrandToppingSettingRepository brandToppingSettingRepository = mock(BrandToppingSettingRepository.class);
    private final StoreSpecSettingRepository storeSpecSettingRepository = mock(StoreSpecSettingRepository.class);
    private final StoreToppingStatusRepository storeToppingStatusRepository = mock(StoreToppingStatusRepository.class);
    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final BrandRepository brandRepository = mock(BrandRepository.class);
    private final UserCouponRepository userCouponRepository = mock(UserCouponRepository.class);
    private final RedisLockService redisLockService = mock(RedisLockService.class);
    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);

    private ProductService productService() {
        return new ProductService(productRepository, productTemplateRepository, productSpecRelationRepository,
                brandSpecSettingRepository, menuCategoryRepository, specMasterRepository,
                brandToppingSettingRepository, storeSpecSettingRepository, storeToppingStatusRepository,
                storeRepository);
    }

    private DailySpinService dailySpinService() {
        return new DailySpinService(productRepository, userCouponRepository, menuCategoryRepository,
                redisLockService, stringRedisTemplate, brandRepository);
    }

    private static Brand brand(boolean suspended) {
        Brand brand = new Brand();
        brand.setId(5L);
        brand.setName("茶");
        brand.setApprovalStatus(BrandApproval.APPROVED);
        brand.setSuspended(suspended);
        return brand;
    }

    private static Store store(Brand brand, boolean suspended) {
        Store store = new Store();
        store.setId(31L);
        store.setStoreName("店 31");
        store.setStatus("active");
        store.setBrand(brand);
        store.setSuspended(suspended);
        return store;
    }

    private static ProductTemplate product(Brand brand) {
        ProductTemplate product = new ProductTemplate();
        product.setId(9L);
        product.setName("珍珠奶茶");
        product.setBrand(brand);
        return product;
    }

    @Test
    @DisplayName("分店被停用時，上架商品清單對外為空，且不查詢商品")
    void suspendedStoreHasNoPublicProducts() {
        when(storeRepository.findById(31L)).thenReturn(Optional.of(store(brand(false), true)));

        assertTrue(productService().getActiveProductsByStoreId(31L).isEmpty());
        verify(productRepository, never()).findByStoreId(any());
    }

    @Test
    @DisplayName("商品所屬品牌被停用時，商品詳情回 404，規格與客製化選項不對外")
    void productsOfASuspendedBrandAreHidden() {
        ProductTemplate product = product(brand(true));
        when(productTemplateRepository.findById(9L)).thenReturn(Optional.of(product));
        when(productRepository.findById(9L)).thenReturn(Optional.of(product));

        CustomException e = assertThrows(CustomException.class, () -> productService().getProductDetail(9L));
        assertEquals("404", e.getCode());
        assertTrue(productService().getProductSpecs(9L).isEmpty());
        assertNull(productService().getProductCustomization(9L, null));
    }

    @Test
    @DisplayName("客製化選項帶入已停用的分店時回 null")
    void customizationForASuspendedStoreIsHidden() {
        when(productRepository.findById(9L)).thenReturn(Optional.of(product(brand(false))));
        when(storeRepository.findById(31L)).thenReturn(Optional.of(store(brand(false), true)));

        assertNull(productService().getProductCustomization(9L, 31L));
    }

    @Test
    @DisplayName("已停用品牌的轉盤分類為空，且不能抽獎（不取得鎖、不寫入今日標記）")
    void suspendedBrandCannotSpin() {
        when(brandRepository.findById(5L)).thenReturn(Optional.of(brand(true)));

        assertTrue(dailySpinService().getCategoriesByBrand(5L).isEmpty());
        verify(menuCategoryRepository, never()).findByBrandIdOrderBySortOrderAsc(any());

        CustomException e = assertThrows(CustomException.class, () -> dailySpinService().spin(7L, 5L));
        assertEquals("404", e.getCode());
        verifyNoInteractions(redisLockService);
    }
}
