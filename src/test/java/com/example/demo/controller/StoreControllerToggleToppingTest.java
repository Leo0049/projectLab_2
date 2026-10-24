package com.example.demo.controller;

import com.example.demo.entity.Brand;
import com.example.demo.entity.BrandToppingSetting;
import com.example.demo.entity.Store;
import com.example.demo.entity.StoreToppingStatus;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.BrandToppingSettingRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.StoreToppingStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 門市切換配料供應狀態（N-33，2026-10-24）：只能切換本品牌的配料；
 * 他品牌與不存在的 id 回同一個 404，不洩漏 id 是否存在；isAvailable 必填。
 */
@ExtendWith(MockitoExtension.class)
class StoreControllerToggleToppingTest {

    private static final Long STORE_ID = 11L;
    private static final Long TOPPING_ID = 33L;

    @Mock
    private StoreRepository storeRepository;
    @Mock
    private BrandToppingSettingRepository brandToppingSettingRepository;
    @Mock
    private StoreToppingStatusRepository storeToppingStatusRepository;
    @InjectMocks
    private StoreController controller;

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

    private static BrandToppingSetting toppingOfBrand(Long brandId) {
        BrandToppingSetting topping = new BrandToppingSetting();
        topping.setId(TOPPING_ID);
        topping.setBrand(brandOf(brandId));
        return topping;
    }

    @Test
    @DisplayName("本品牌的配料：寫入分店缺貨狀態（isAvailable=false 即缺貨）")
    void ownBrandToppingIsSaved() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(storeOfBrand(5L)));
        when(brandToppingSettingRepository.findById(TOPPING_ID)).thenReturn(Optional.of(toppingOfBrand(5L)));
        when(storeToppingStatusRepository.findById(any())).thenReturn(Optional.empty());

        controller.toggleStoreTopping(STORE_ID, TOPPING_ID, Map.of("isAvailable", false));

        ArgumentCaptor<StoreToppingStatus> saved = ArgumentCaptor.forClass(StoreToppingStatus.class);
        verify(storeToppingStatusRepository).save(saved.capture());
        assertTrue(saved.getValue().getIsOutOfStock());
    }

    @Test
    @DisplayName("他品牌的配料：404 且不寫入")
    void otherBrandToppingIsRejectedWithoutWriting() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(storeOfBrand(5L)));
        when(brandToppingSettingRepository.findById(TOPPING_ID)).thenReturn(Optional.of(toppingOfBrand(6L)));

        CustomException ex = assertThrows(CustomException.class,
                () -> controller.toggleStoreTopping(STORE_ID, TOPPING_ID, Map.of("isAvailable", false)));

        assertEquals("404", ex.getCode());
        verify(storeToppingStatusRepository, never()).save(any());
    }

    @Test
    @DisplayName("不存在的配料：與他品牌回同一個 404，且不寫入")
    void unknownToppingIsRejectedAsNotFound() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(storeOfBrand(5L)));
        when(brandToppingSettingRepository.findById(TOPPING_ID)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> controller.toggleStoreTopping(STORE_ID, TOPPING_ID, Map.of("isAvailable", false)));

        assertEquals("404", ex.getCode());
        verify(storeToppingStatusRepository, never()).save(any());
    }

    @Test
    @DisplayName("isAvailable 缺少：400，且在查詢任何資料前就拒絕")
    void missingAvailabilityIsRejectedBeforeAnyLookup() {
        CustomException ex = assertThrows(CustomException.class,
                () -> controller.toggleStoreTopping(STORE_ID, TOPPING_ID, Map.of()));

        assertEquals("400", ex.getCode());
        verifyNoInteractions(storeRepository, brandToppingSettingRepository, storeToppingStatusRepository);
    }
}
