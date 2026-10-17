package com.example.demo.service;

import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.TransactionRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 列表端點的每頁筆數與頁碼夾限（不載入 Spring context）。
 *
 * <p>列表會隨營運時間累積，所以每頁筆數必須有上限，頁碼也不能是負數。
 * 夾限被拿掉時，用戶端指定的 size 會直接落到資料庫查詢，回應又會回到整份歷史的大小。
 */
class PageSizeClampTest {

    // ── 錢包交易紀錄（上限 100） ───────────────────────────

    @Test
    @DisplayName("交易紀錄：size 超過 100 時截為 100")
    void userProfileTransactionsCapsPageSize() {
        TransactionRecordRepository txRepo = mock(TransactionRecordRepository.class);
        when(txRepo.findByUserIdOrderByCreatedAtDesc(eq(7L), any(Pageable.class))).thenReturn(Page.empty());
        UserProfileService service = new UserProfileService();
        ReflectionTestUtils.setField(service, "transactionRecordRepository", txRepo);

        Map<String, Object> out = service.getTransactions(7L, null, 1, 100_000);

        assertEquals(100, out.get("size"));
        ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
        verify(txRepo).findByUserIdOrderByCreatedAtDesc(eq(7L), sent.capture());
        assertEquals(100, sent.getValue().getPageSize());
    }

    @Test
    @DisplayName("交易紀錄：page=0 視為第一頁，不會送出負的頁碼")
    void userProfileTransactionsTreatsPageZeroAsFirstPage() {
        TransactionRecordRepository txRepo = mock(TransactionRecordRepository.class);
        when(txRepo.findByUserIdOrderByCreatedAtDesc(eq(7L), any(Pageable.class))).thenReturn(Page.empty());
        UserProfileService service = new UserProfileService();
        ReflectionTestUtils.setField(service, "transactionRecordRepository", txRepo);

        service.getTransactions(7L, null, 0, 20);

        ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
        verify(txRepo).findByUserIdOrderByCreatedAtDesc(eq(7L), sent.capture());
        assertEquals(0, sent.getValue().getPageNumber());
        assertEquals(20, sent.getValue().getPageSize());
    }

    // ── 分店訂單（上限 200） ───────────────────────────────

    @Test
    @DisplayName("分店訂單：size 超過 200 時截為 200")
    void storeOrdersCapsPageSize() {
        GroupOrderRepository orders = mock(GroupOrderRepository.class);
        when(orders.findByStoreIdAndOptionalStatus(eq(7L), isNull(), any(Pageable.class))).thenReturn(Page.empty());
        AnalyticsService service = new AnalyticsService();
        ReflectionTestUtils.setField(service, "groupOrderRepository", orders);

        service.getStoreOrders(7L, null, 0, 5_000);

        ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
        verify(orders).findByStoreIdAndOptionalStatus(eq(7L), isNull(), sent.capture());
        assertEquals(200, sent.getValue().getPageSize());
    }

    @Test
    @DisplayName("分店訂單：page 為負數時視為第一頁")
    void storeOrdersTreatsNegativePageAsFirstPage() {
        GroupOrderRepository orders = mock(GroupOrderRepository.class);
        when(orders.findByStoreIdAndOptionalStatus(eq(7L), isNull(), any(Pageable.class))).thenReturn(Page.empty());
        AnalyticsService service = new AnalyticsService();
        ReflectionTestUtils.setField(service, "groupOrderRepository", orders);

        service.getStoreOrders(7L, null, -3, 20);

        ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
        verify(orders).findByStoreIdAndOptionalStatus(eq(7L), isNull(), sent.capture());
        assertEquals(0, sent.getValue().getPageNumber());
        assertEquals(20, sent.getValue().getPageSize());
    }

    // ── 附近店家，無定位時依 id 分頁（每頁上限 50、頁碼上限 100000） ──

    @Test
    @DisplayName("附近店家：size 超過 50 時截為 50")
    void nearbyStoresCapsPageSize() {
        StoreRepository stores = mock(StoreRepository.class);
        PublicService service = new PublicService();
        ReflectionTestUtils.setField(service, "storeRepository", stores);

        Map<String, Object> out = service.getNearbyStoresPage(null, null, null, null, 1, 1_000, null);

        assertEquals(50, out.get("size"));
        ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
        verify(stores).findByStatusOrderByIdAsc(eq("active"), sent.capture());
        assertEquals(50, sent.getValue().getPageSize());
    }

    @Test
    @DisplayName("附近店家：頁碼超過 100000 時截為上限")
    void nearbyStoresCapsPageNumber() {
        StoreRepository stores = mock(StoreRepository.class);
        PublicService service = new PublicService();
        ReflectionTestUtils.setField(service, "storeRepository", stores);

        Map<String, Object> out = service.getNearbyStoresPage(null, null, null, null, Integer.MAX_VALUE, 20, null);

        assertEquals(100_000, out.get("page"));
    }

    // ── 搜尋店家（每頁上限 200，預設 50 在控制器） ─────────────

    @Test
    @DisplayName("搜尋店家：size 超過 200 時截為 200")
    void searchStoresCapsPageSize() {
        StoreRepository stores = mock(StoreRepository.class);
        PublicService service = new PublicService();
        ReflectionTestUtils.setField(service, "storeRepository", stores);

        Map<String, Object> out = service.searchStores("春", null, null, 1, 5_000, null);

        assertEquals(200, out.get("size"));
        ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
        verify(stores).searchByBrandName(eq("春"), sent.capture());
        assertEquals(200, sent.getValue().getPageSize());
    }

    @Test
    @DisplayName("搜尋店家：page=0 視為第一頁，不會送出負的頁碼")
    void searchStoresTreatsPageZeroAsFirstPage() {
        StoreRepository stores = mock(StoreRepository.class);
        PublicService service = new PublicService();
        ReflectionTestUtils.setField(service, "storeRepository", stores);

        Map<String, Object> out = service.searchStores("春", null, null, 0, 12, null);

        assertEquals(1, out.get("page"));
        ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
        verify(stores).searchByBrandName(eq("春"), sent.capture());
        assertEquals(0, sent.getValue().getPageNumber());
    }
}
