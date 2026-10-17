package com.example.demo.service;

import com.example.demo.entity.Store;
import com.example.demo.repository.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 店家搜尋的分頁規則（不載入 Spring context）。
 *
 * <p>守住三件事：頁碼與筆數換算成查詢參數時不能差一頁；total 必須是真實總筆數，
 * 而不是本頁長度；hasNext 只在還有下一頁時為 true。
 */
class PublicServiceSearchTest {

    private static PublicService serviceWith(StoreRepository stores) {
        PublicService service = new PublicService();
        ReflectionTestUtils.setField(service, "storeRepository", stores);
        return service;
    }

    private static Store store(long id, String name) {
        Store s = new Store();
        s.setId(id);
        s.setStoreName(name);
        return s;
    }

    @Test
    @DisplayName("品牌路徑：第 3 頁、每頁 12 筆 → 頁索引為 2（從 0 開始）")
    void brandPathUsesZeroBasedPageIndex() {
        StoreRepository stores = mock(StoreRepository.class);
        PublicService service = serviceWith(stores);

        service.searchStores("春", null, null, 3, 12, null);

        ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
        verify(stores).searchByBrandName(eq("春"), sent.capture());
        assertEquals(2, sent.getValue().getPageNumber());
        assertEquals(12, sent.getValue().getPageSize());
    }

    @Test
    @DisplayName("距離路徑：第 3 頁、每頁 12 筆 → LIMIT 12 OFFSET 24")
    void distancePathUsesLimitAndOffset() {
        StoreRepository stores = mock(StoreRepository.class);
        PublicService service = serviceWith(stores);

        service.searchStores("春", 25.03, 121.56, 3, 12, null);

        verify(stores).searchByKeywordWithDistance("%春%", 25.03, 121.56, 12, 24);
    }

    @Test
    @DisplayName("total 是真實總筆數，不是本頁長度")
    void totalIsTheRealCountNotThePageLength() {
        StoreRepository stores = mock(StoreRepository.class);
        when(stores.searchByBrandName(eq("春"), any(Pageable.class))).thenReturn(List.of(store(1L, "甲店")));
        when(stores.countSearchByKeyword("春")).thenReturn(25L);
        PublicService service = serviceWith(stores);

        Map<String, Object> out = service.searchStores("春", null, null, 1, 12, null);

        assertEquals(1, ((List<?>) out.get("stores")).size());
        assertEquals(25L, out.get("total"));
    }

    @Test
    @DisplayName("hasNext：還有下一頁時為 true，最後一頁為 false；totalPages 向上取整")
    void hasNextOnlyBeforeLastPage() {
        StoreRepository stores = mock(StoreRepository.class);
        when(stores.countSearchByKeyword("春")).thenReturn(25L);
        PublicService service = serviceWith(stores);

        Map<String, Object> first = service.searchStores("春", null, null, 1, 12, null);
        Map<String, Object> middle = service.searchStores("春", null, null, 2, 12, null);
        Map<String, Object> last = service.searchStores("春", null, null, 3, 12, null);

        assertEquals(3, first.get("totalPages"));
        assertEquals(Boolean.TRUE, first.get("hasNext"));
        assertEquals(Boolean.TRUE, middle.get("hasNext"));
        assertEquals(Boolean.FALSE, last.get("hasNext"));
    }

    @Test
    @DisplayName("總筆數剛好是頁數的整數倍時，最後一頁之後沒有下一頁")
    void exactMultipleHasNoExtraPage() {
        StoreRepository stores = mock(StoreRepository.class);
        when(stores.countSearchByKeyword("春")).thenReturn(24L);
        PublicService service = serviceWith(stores);

        Map<String, Object> out = service.searchStores("春", null, null, 2, 12, null);

        assertEquals(2, out.get("totalPages"));
        assertEquals(Boolean.FALSE, out.get("hasNext"));
    }

    @Test
    @DisplayName("沒有符合的店家：totalPages 為 0，hasNext 為 false")
    void emptyResultHasNoNextPage() {
        StoreRepository stores = mock(StoreRepository.class);
        PublicService service = serviceWith(stores);

        Map<String, Object> out = service.searchStores("zzz", null, null, 1, 12, null);

        assertEquals(0, out.get("totalPages"));
        assertEquals(Boolean.FALSE, out.get("hasNext"));
        assertTrue(((List<?>) out.get("stores")).isEmpty());
    }

    @Test
    @DisplayName("距離路徑：輸出順序依查詢回傳的順序，而不是 findAllById 的順序")
    void distanceResultsKeepQueryOrder() {
        StoreRepository stores = mock(StoreRepository.class);
        when(stores.searchByKeywordWithDistance("%春%", 25.03, 121.56, 12, 0))
                .thenReturn(List.<Object[]>of(new Object[]{2L, 0.5d}, new Object[]{1L, 1.2d}));
        when(stores.findAllById(any())).thenReturn(List.of(store(1L, "甲店"), store(2L, "乙店")));
        PublicService service = serviceWith(stores);

        List<?> cards = (List<?>) service.searchStores("春", 25.03, 121.56, 1, 12, null).get("stores");

        assertEquals(2, cards.size());
        assertEquals(2L, ((Map<?, ?>) cards.get(0)).get("storeId"));
        assertEquals(0.5, ((Map<?, ?>) cards.get(0)).get("distance"));
        assertEquals(1L, ((Map<?, ?>) cards.get(1)).get("storeId"));
        assertEquals(1.2, ((Map<?, ?>) cards.get(1)).get("distance"));
    }

    @Test
    @DisplayName("距離為 NULL（店家沒有座標）時仍可回傳，卡片不含距離")
    void nullDistanceDoesNotFailTheSearch() {
        StoreRepository stores = mock(StoreRepository.class);
        when(stores.searchByKeywordWithDistance("%春%", 25.03, 121.56, 12, 0))
                .thenReturn(List.<Object[]>of(new Object[]{3L, null}));
        when(stores.findAllById(any())).thenReturn(List.of(store(3L, "丙店")));
        PublicService service = serviceWith(stores);

        List<?> cards = (List<?>) service.searchStores("春", 25.03, 121.56, 1, 12, null).get("stores");

        assertEquals(1, cards.size());
        assertFalse(((Map<?, ?>) cards.get(0)).containsKey("distance"));
    }
}
