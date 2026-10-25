package com.example.demo.service;

import com.example.demo.entity.Brand;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.CartItemRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 停用對購物車加入的影響（stage 17）：分店或品牌停用時加入購物車回 400，而且在查詢商品、寫入品項之前就拒絕；
 * 可用的分店則通過守衛。不載入 Spring context。
 */
@ExtendWith(MockitoExtension.class)
class CartSuspensionGuardTest {

    private static final String REFUSAL = "此分店目前停用，無法加入購物車";
    private static final long USER_ID = 1L;
    private static final long STORE_ID = 10L;
    private static final long PRODUCT_ID = 20L;

    @Mock private UserRepository userRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private ProductTemplateRepository productTemplateRepository;
    @Mock private CartItemRepository cartItemRepository;
    @InjectMocks private CartService cartService;

    @Test
    @DisplayName("停用的分店不可加入購物車，且不查商品也不寫入品項")
    void addItemRefusesASuspendedStore() {
        stubUserAndStore(store(true, false));

        CustomException ex = assertThrows(CustomException.class, () -> cartService.addItem(USER_ID, request()));

        assertEquals("400", ex.getCode());
        assertEquals(REFUSAL, ex.getMsg());
        verify(productTemplateRepository, never()).findById(any());
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("品牌停用時，底下分店也不可加入購物車")
    void addItemRefusesAStoreUnderASuspendedBrand() {
        stubUserAndStore(store(false, true));

        CustomException ex = assertThrows(CustomException.class, () -> cartService.addItem(USER_ID, request()));

        assertEquals("400", ex.getCode());
        assertEquals(REFUSAL, ex.getMsg());
        verify(productTemplateRepository, never()).findById(any());
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("可用的分店通過守衛，之後才因商品不存在而失敗（證明守衛沒有誤擋）")
    void addItemLetsAReachableStorePastTheGuard() {
        stubUserAndStore(store(false, false));
        when(productTemplateRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class, () -> cartService.addItem(USER_ID, request()));

        assertEquals("404", ex.getCode());
        assertEquals("找不到商品", ex.getMsg());
    }

    private void stubUserAndStore(Store store) {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(new User()));
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
    }

    private static Map<String, Object> request() {
        return Map.of("storeId", String.valueOf(STORE_ID), "productId", String.valueOf(PRODUCT_ID));
    }

    private static Store store(boolean storeSuspended, boolean brandSuspended) {
        Brand brand = new Brand();
        brand.setSuspended(brandSuspended);
        Store store = new Store();
        store.setId(STORE_ID);
        store.setSuspended(storeSuspended);
        store.setBrand(brand);
        return store;
    }
}
