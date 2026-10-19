package com.example.demo.service;

import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.Store;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.GroupOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 店家接單／拒單與逾時自動拒單共用的列鎖與狀態判斷（N-32，2026-10-19）。
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceAutoRejectTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 30, 0);

    @Mock
    private GroupOrderRepository groupOrderRepository;
    @Mock
    private GroupOrderService groupOrderService;
    @InjectMocks
    private AnalyticsService analyticsService;

    @Test
    @DisplayName("逾時且仍為 SUBMITTED：以列鎖讀取、退款一次、狀態改為 REJECTED")
    void expiredSubmittedOrderIsRefundedOnceAndRejected() {
        GroupOrder order = newOrder(9L, 3L, "SUBMITTED", NOW.minusMinutes(11));
        when(groupOrderRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(order));
        when(groupOrderService.handleGroupOrderCancellation(9L)).thenReturn(true);

        assertTrue(analyticsService.autoRejectIfStillPending(9L, NOW));

        verify(groupOrderService, times(1)).handleGroupOrderCancellation(9L);
        verify(groupOrderRepository).save(order);
        assertEquals("REJECTED", order.getStatus());
        assertTrue(order.getIsRejected());
        assertEquals(NOW, order.getCancelledOrRejectedAt());
        verify(groupOrderRepository, never()).findById(any());
    }

    @Test
    @DisplayName("剛好滿 10 分鐘仍可接單：不退款、不改狀態")
    void exactlyTenMinutesIsNotRejected() {
        GroupOrder order = newOrder(9L, 3L, "SUBMITTED", NOW.minusMinutes(10));
        when(groupOrderRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(order));

        assertFalse(analyticsService.autoRejectIfStillPending(9L, NOW));

        verifyNoInteractions(groupOrderService);
        verify(groupOrderRepository, never()).save(any());
        assertEquals("SUBMITTED", order.getStatus());
    }

    @Test
    @DisplayName("掃描後、取鎖前店家已接單：鎖內重新判斷，不退款、不拒單")
    void orderAcceptedAfterTheScanIsNotRejected() {
        GroupOrder order = newOrder(9L, 3L, "PREPARING", NOW.minusMinutes(30));
        when(groupOrderRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(order));

        assertFalse(analyticsService.autoRejectIfStillPending(9L, NOW));

        verifyNoInteractions(groupOrderService);
        verify(groupOrderRepository, never()).save(any());
        assertEquals("PREPARING", order.getStatus());
    }

    @Test
    @DisplayName("訂單已不存在：忽略，不拋例外")
    void missingOrderIsIgnored() {
        when(groupOrderRepository.findByIdForUpdate(9L)).thenReturn(Optional.empty());

        assertFalse(analyticsService.autoRejectIfStillPending(9L, NOW));

        verifyNoInteractions(groupOrderService);
    }

    @Test
    @DisplayName("店家不可拒絕已接單的訂單（409）：不退款、也不改狀態；原本會變成 REJECTED 而款項不退")
    void storeCannotRejectAfterAccepting() {
        GroupOrder order = newOrder(9L, 3L, "PREPARING", NOW.minusMinutes(2));
        when(groupOrderRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(order));

        CustomException e = assertThrows(CustomException.class, () -> analyticsService.rejectOrder(9L, 3L));

        assertEquals("409", e.getCode());
        verifyNoInteractions(groupOrderService);
        verify(groupOrderRepository, never()).save(any());
        assertEquals("PREPARING", order.getStatus());
    }

    @Test
    @DisplayName("店家不可拒絕別家門市的訂單（403）")
    void storeCannotRejectAnotherStoresOrder() {
        GroupOrder order = newOrder(9L, 3L, "SUBMITTED", NOW.minusMinutes(2));
        when(groupOrderRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(order));

        CustomException e = assertThrows(CustomException.class, () -> analyticsService.rejectOrder(9L, 4L));

        assertEquals("403", e.getCode());
        verifyNoInteractions(groupOrderService);
    }

    @Test
    @DisplayName("店家拒絕待接單訂單：以列鎖讀取、退款一次、狀態改為 REJECTED")
    void storeRejectsPendingOrderUnderTheLock() {
        GroupOrder order = newOrder(9L, 3L, "SUBMITTED", NOW.minusMinutes(2));
        when(groupOrderRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(order));
        when(groupOrderService.handleGroupOrderCancellation(9L)).thenReturn(true);

        analyticsService.rejectOrder(9L, 3L);

        verify(groupOrderService, times(1)).handleGroupOrderCancellation(9L);
        assertEquals("REJECTED", order.getStatus());
        verify(groupOrderRepository, never()).findById(any());
    }

    @Test
    @DisplayName("接單以列鎖讀取，與逾時自動拒單互斥")
    void acceptUsesTheRowLock() {
        GroupOrder order = newOrder(9L, 3L, "SUBMITTED", NOW.minusMinutes(2));
        when(groupOrderRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(order));

        analyticsService.acceptOrder(9L, 3L);

        assertEquals("PREPARING", order.getStatus());
        verify(groupOrderRepository).save(order);
        verify(groupOrderRepository, never()).findById(any());
    }

    private static GroupOrder newOrder(long id, long storeId, String status, LocalDateTime submittedAt) {
        Store store = new Store();
        store.setId(storeId);
        GroupOrder go = new GroupOrder();
        go.setId(id);
        go.setStore(store);
        go.setStatus(status);
        go.setSubmittedAt(submittedAt);
        go.setCreatedAt(submittedAt.minusMinutes(1));
        return go;
    }
}
