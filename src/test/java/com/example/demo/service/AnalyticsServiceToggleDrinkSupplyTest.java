package com.example.demo.service;

import com.example.demo.entity.Brand;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Store;
import com.example.demo.entity.StoreProductStatus;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.StoreProductStatusRepository;
import com.example.demo.repository.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 門市切換飲品供應狀態（N-33，2026-10-24）：只能切換本品牌的飲品；
 * 他品牌與不存在的 id 回同一個 404，不洩漏 id 是否存在；isAvailable 必填。
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceToggleDrinkSupplyTest {

    private static final Long STORE_ID = 11L;
    private static final Long DRINK_ID = 22L;

    @Mock
    private StoreRepository storeRepository;
    @Mock
    private ProductTemplateRepository productTemplateRepository;
    @Mock
    private StoreProductStatusRepository storeProductStatusRepository;
    @InjectMocks
    private AnalyticsService analyticsService;

    private static Brand brandOf(Long brandId) {
        Brand brand = new Brand();
        brand.setId(brandId);
        return brand;
    }

    private static Store storeOfBrand(Long brandId) {
        Store store = new Store();
        store.setId(STORE_ID);
        store.setBrand(brandOf(brandId));
        return store;
    }

    private static ProductTemplate drinkOfBrand(Long brandId) {
        ProductTemplate drink = new ProductTemplate();
        drink.setId(DRINK_ID);
        drink.setBrand(brandOf(brandId));
        return drink;
    }

    @Test
    @DisplayName("本品牌的飲品：寫入分店供應狀態")
    void ownBrandDrinkIsSaved() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(storeOfBrand(5L)));
        when(productTemplateRepository.findById(DRINK_ID)).thenReturn(Optional.of(drinkOfBrand(5L)));
        when(storeProductStatusRepository.findById(any())).thenReturn(Optional.empty());

        analyticsService.toggleDrinkSupply(STORE_ID, DRINK_ID, false);

        ArgumentCaptor<StoreProductStatus> saved = ArgumentCaptor.forClass(StoreProductStatus.class);
        verify(storeProductStatusRepository).save(saved.capture());
        assertFalse(saved.getValue().getIsEnabled());
    }

    @Test
    @DisplayName("他品牌的飲品：404 且不寫入")
    void otherBrandDrinkIsRejectedWithoutWriting() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(storeOfBrand(5L)));
        when(productTemplateRepository.findById(DRINK_ID)).thenReturn(Optional.of(drinkOfBrand(6L)));

        CustomException ex = assertThrows(CustomException.class,
                () -> analyticsService.toggleDrinkSupply(STORE_ID, DRINK_ID, false));

        assertEquals("404", ex.getCode());
        verify(storeProductStatusRepository, never()).save(any());
    }

    @Test
    @DisplayName("不存在的飲品：與他品牌回同一個 404，且不寫入")
    void unknownDrinkIsRejectedAsNotFound() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(storeOfBrand(5L)));
        when(productTemplateRepository.findById(DRINK_ID)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> analyticsService.toggleDrinkSupply(STORE_ID, DRINK_ID, false));

        assertEquals("404", ex.getCode());
        verify(storeProductStatusRepository, never()).save(any());
    }

    @Test
    @DisplayName("分店沒有品牌：任何飲品都不能切換")
    void storeWithoutBrandCannotToggleAnyDrink() {
        Store store = storeOfBrand(5L);
        store.setBrand(null);
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
        when(productTemplateRepository.findById(DRINK_ID)).thenReturn(Optional.of(drinkOfBrand(5L)));

        CustomException ex = assertThrows(CustomException.class,
                () -> analyticsService.toggleDrinkSupply(STORE_ID, DRINK_ID, false));

        assertEquals("404", ex.getCode());
        verify(storeProductStatusRepository, never()).save(any());
    }

    @Test
    @DisplayName("isAvailable 缺少：400，且在查詢任何資料前就拒絕")
    void missingAvailabilityIsRejectedBeforeAnyLookup() {
        CustomException ex = assertThrows(CustomException.class,
                () -> analyticsService.toggleDrinkSupply(STORE_ID, DRINK_ID, null));

        assertEquals("400", ex.getCode());
        verifyNoInteractions(storeRepository, productTemplateRepository, storeProductStatusRepository);
    }
}
