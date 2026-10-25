package com.example.demo.controller;

import com.example.demo.entity.Brand;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.entity.UserFavorite;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserFavoriteRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 停用對收藏的影響（stage 17）：收藏清單不列出停用的分店，也不列出品牌已停用的分店；
 * 新增收藏時分店或品牌停用回 400 且不寫入；移除收藏不受停用限制。不載入 Spring context。
 */
@ExtendWith(MockitoExtension.class)
class UserFavoriteVisibilityTest {

    private static final long USER_ID = 9L;
    private static final long STORE_ID = 10L;

    @Mock private UserFavoriteRepository userFavoriteRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private UserFavoriteController controller;

    @Test
    @DisplayName("收藏清單不列出停用的分店，也不列出品牌已停用的分店")
    void listHidesSuspendedStoresAndStoresUnderASuspendedBrand() {
        when(userFavoriteRepository.findByUserId(USER_ID)).thenReturn(List.of(
                favorite(store(10L, false, false)),
                favorite(store(11L, true, false)),
                favorite(store(12L, false, true))));

        ResponseEntity<List<UserFavoriteController.UserFavoriteDTO>> response =
                controller.getUserFavorites(USER_ID, USER_ID);

        List<UserFavoriteController.UserFavoriteDTO> body = response.getBody();
        assertEquals(1, body.size());
        assertEquals(10L, body.get(0).getStore().getId());
    }

    @Test
    @DisplayName("停用的分店不可新增收藏，回 400 且不寫入")
    void toggleRefusesToFavoriteASuspendedStore() {
        stubAddPath(store(STORE_ID, true, false));

        CustomException ex = assertThrows(CustomException.class,
                () -> controller.toggleFavorite(toggle(STORE_ID), USER_ID));

        assertEquals("400", ex.getCode());
        assertEquals("此分店目前停用，無法收藏", ex.getMsg());
        verify(userFavoriteRepository, never()).save(any());
    }

    @Test
    @DisplayName("品牌停用時，底下分店也不可新增收藏")
    void toggleRefusesToFavoriteAStoreUnderASuspendedBrand() {
        stubAddPath(store(STORE_ID, false, true));
        when(storeRepository.isBrandSuspended(STORE_ID)).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> controller.toggleFavorite(toggle(STORE_ID), USER_ID));

        assertEquals("400", ex.getCode());
        verify(userFavoriteRepository, never()).save(any());
    }

    @Test
    @DisplayName("移除收藏不受停用限制：分店已停用，既有收藏仍可刪除")
    void toggleStillRemovesAFavoriteWhenItsStoreIsSuspended() {
        UserFavorite existing = favorite(store(STORE_ID, true, false));
        when(userFavoriteRepository.findByUserIdAndStoreId(USER_ID, STORE_ID)).thenReturn(Optional.of(existing));

        ResponseEntity<Map<String, Object>> response = controller.toggleFavorite(toggle(STORE_ID), USER_ID);

        assertEquals("removed", response.getBody().get("status"));
        verify(userFavoriteRepository).delete(existing);
        verify(storeRepository, never()).findById(any());
    }

    /** 新增收藏的共同前置：使用者與分店查得到，且尚未收藏。 */
    private void stubAddPath(Store store) {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(new User()));
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store));
        when(userFavoriteRepository.findByUserIdAndStoreId(USER_ID, STORE_ID)).thenReturn(Optional.empty());
    }

    private static UserFavorite favorite(Store store) {
        UserFavorite favorite = new UserFavorite();
        favorite.setStore(store);
        return favorite;
    }

    private static Store store(long id, boolean storeSuspended, boolean brandSuspended) {
        Brand brand = new Brand();
        brand.setSuspended(brandSuspended);
        Store store = new Store();
        store.setId(id);
        store.setSuspended(storeSuspended);
        store.setBrand(brand);
        return store;
    }

    private static UserFavoriteController.FavoriteToggleRequest toggle(long storeId) {
        UserFavoriteController.FavoriteToggleRequest request = new UserFavoriteController.FavoriteToggleRequest();
        request.setStoreId(storeId);
        return request;
    }
}
