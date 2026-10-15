package com.example.demo.service;

import com.example.demo.entity.Store;
import com.example.demo.repository.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 分店回應不得洩漏店長的個人手機（N6）。
 *
 * <p>公開端點（{@code /api/stores/{id}/v2}、{@code /api/stores/brand/{id}}）與店家後台共用 {@code toMap}。
 * 公開回應只能帶店家電話（storePhone）；店長手機只在店家後台本人的 profile 中出現。
 *
 * <p>不載入 Spring context：這兩支方法只用到 StoreRepository，其餘依賴不會被呼叫。
 */
class StorePhoneExposureTest {

    private static final String MANAGER_PHONE = "0912345678";
    private static final String STORE_PHONE = "02-2345-6789";

    @Test
    @DisplayName("公開的分店資料不含店長手機，只含店家電話（N6）")
    void publicStoreViewNeverCarriesTheManagerPhone() {
        StoreService service = new StoreService(repositoryHolding(store(7L)));

        Map<String, Object> view = service.getStoreByIdV2(7L);

        assertFalse(view.containsKey("phone"), "公開回應不應有 phone 鍵");
        assertEquals(STORE_PHONE, view.get("storePhone"));
        assertFalse(view.toString().contains(MANAGER_PHONE), "店長手機不得出現在任何欄位");
    }

    @Test
    @DisplayName("店家後台本人的 profile 仍回傳店長手機，供表單使用（N6）")
    void storeOwnProfileStillCarriesTheManagerPhone() {
        StoreService service = new StoreService(repositoryHolding(store(7L)));

        assertEquals(MANAGER_PHONE, service.getProfile(7L).get("phone"));
    }

    private static Store store(long id) {
        Store store = new Store();
        store.setId(id);
        store.setStoreName("測試分店");
        store.setManagerPhone(MANAGER_PHONE);
        store.setStorePhone(STORE_PHONE);
        return store;
    }

    private static StoreRepository repositoryHolding(Store store) {
        return (StoreRepository) Proxy.newProxyInstance(StoreRepository.class.getClassLoader(),
                new Class<?>[]{StoreRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findById")) {
                        return Optional.of(store);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
