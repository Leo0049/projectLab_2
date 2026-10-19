package com.example.demo.service;

import com.example.demo.repository.GroupOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 待接單逾時自動拒單的掃描流程（N-32，2026-10-19）。 */
@ExtendWith(MockitoExtension.class)
class OrderAutoRejectJobTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 30, 0);

    @Mock
    private GroupOrderRepository groupOrderRepository;
    @Mock
    private AnalyticsService analyticsService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @InjectMocks
    private OrderAutoRejectJob job;

    @Test
    @DisplayName("逐筆拒單：只推播真的被拒的訂單；單筆失敗不影響後面的訂單")
    void sweepRejectsEachAndNotifiesOnlyTheRejected() {
        when(groupOrderRepository.findExpiredPendingOrderIds(eq(NOW.minusMinutes(10)), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L, 3L));
        when(analyticsService.autoRejectIfStillPending(1L, NOW)).thenReturn(true);
        when(analyticsService.autoRejectIfStillPending(2L, NOW)).thenThrow(new RuntimeException("db busy"));
        when(analyticsService.autoRejectIfStillPending(3L, NOW)).thenReturn(true);

        job.sweep(NOW);

        verify(analyticsService).autoRejectIfStillPending(3L, NOW);
        verify(messagingTemplate).convertAndSend("/topic/order/1", Map.of("status", "REJECTED"));
        verify(messagingTemplate).convertAndSend("/topic/order/3", Map.of("status", "REJECTED"));
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("鎖內判定為不逾時（店家已接單）的訂單不推播")
    void notRejectedMeansNoNotification() {
        when(groupOrderRepository.findExpiredPendingOrderIds(any(), any(Pageable.class))).thenReturn(List.of(5L));
        when(analyticsService.autoRejectIfStillPending(5L, NOW)).thenReturn(false);

        job.sweep(NOW);

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("沒有逾時訂單時什麼都不做")
    void nothingExpiredDoesNothing() {
        when(groupOrderRepository.findExpiredPendingOrderIds(any(), any(Pageable.class))).thenReturn(List.of());

        job.sweep(NOW);

        verifyNoInteractions(analyticsService, messagingTemplate);
    }
}
