package com.example.demo.service;

import com.example.demo.common.BrandApproval;
import com.example.demo.entity.Brand;
import com.example.demo.entity.Store;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 停用對公開讀取與菜單的影響（stage 6）。列表的可見條件在 SQL 內（見 StoreVisibilityGuardTest），
 * 這裡驗證單筆讀取與品牌層的 Java 端過濾。
 */
class SuspensionVisibilityServiceTest {

    private final BrandRepository brands = mock(BrandRepository.class);
    private final StoreRepository stores = mock(StoreRepository.class);

    private PublicService publicService() {
        PublicService service = new PublicService();
        ReflectionTestUtils.setField(service, "brandRepository", brands);
        ReflectionTestUtils.setField(service, "storeRepository", stores);
        return service;
    }

    private static Brand brand(long id, boolean suspended) {
        Brand brand = new Brand();
        brand.setId(id);
        brand.setName("茶");
        brand.setApprovalStatus(BrandApproval.APPROVED);
        brand.setSuspended(suspended);
        return brand;
    }

    private static Store store(long id, Brand brand, boolean suspended) {
        Store store = new Store();
        store.setId(id);
        store.setStoreName("店 " + id);
        store.setStatus("active");
        store.setBrand(brand);
        store.setSuspended(suspended);
        return store;
    }

    @Test
    @DisplayName("品牌列表只查已核准且未停用的品牌")
    void brandListUsesTheSuspensionFilter() {
        when(brands.findByApprovalStatusAndSuspendedFalse(BrandApproval.APPROVED)).thenReturn(List.of(brand(1L, false)));

        assertEquals(1, publicService().getBrands().size());
        verify(brands).findByApprovalStatusAndSuspendedFalse(BrandApproval.APPROVED);
    }

    @Test
    @DisplayName("停用的品牌詳情回 404；品牌底下已停用的分店不列出（stage 6）")
    void brandDetailHidesSuspendedBrandAndSuspendedStores() {
        Brand suspended = brand(5L, true);
        when(brands.findById(5L)).thenReturn(Optional.of(suspended));
        assertEquals("404", assertThrows(CustomException.class, () -> publicService().getBrandDetail(5L)).getCode());

        Brand live = brand(6L, false);
        when(brands.findById(6L)).thenReturn(Optional.of(live));
        when(stores.findByBrandId(6L)).thenReturn(List.of(store(31L, live, false), store(32L, live, true)));
        Map<String, Object> detail = publicService().getBrandDetail(6L);
        assertEquals(1, detail.get("storeCount"));
    }

    @Test
    @DisplayName("分店詳情：分店或品牌被停用時回 404（stage 6）")
    void storeDetailHidesSuspendedStoreOrBrand() {
        when(stores.findById(31L)).thenReturn(Optional.of(store(31L, brand(5L, false), true)));
        assertEquals("404", assertThrows(CustomException.class,
                () -> publicService().getStoreDetail(31L, null)).getCode());

        when(stores.findById(32L)).thenReturn(Optional.of(store(32L, brand(5L, true), false)));
        assertEquals("404", assertThrows(CustomException.class,
                () -> publicService().getStoreDetail(32L, null)).getCode());
    }

    @Test
    @DisplayName("地圖用的分店清單改走可見查詢")
    void mapUsesTheVisibleStoreQuery() {
        when(stores.findAllVisible()).thenReturn(List.of());

        publicService().getMapStores(null, null);

        verify(stores).findAllVisible();
    }

    @Test
    @DisplayName("分店菜單：分店或品牌被停用時回 404，不讀取菜單（stage 6）")
    void menuHidesSuspendedStoreOrBrand() {
        MenuService menu = new MenuService();
        ReflectionTestUtils.setField(menu, "storeRepository", stores);

        when(stores.findById(31L)).thenReturn(Optional.of(store(31L, brand(5L, false), true)));
        assertEquals("404", assertThrows(CustomException.class, () -> menu.getStoreMenu(31L)).getCode());

        when(stores.findById(32L)).thenReturn(Optional.of(store(32L, brand(5L, true), false)));
        when(stores.isBrandSuspended(32L)).thenReturn(true);
        assertEquals("404", assertThrows(CustomException.class, () -> menu.getStoreMenu(32L)).getCode());
    }
}
