package com.example.demo.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** 待接單自動拒單的排程必須經 ExclusiveJobRunner 包住（N-32，2026-10-19）。 */
@ExtendWith(MockitoExtension.class)
class ScheduledTasksAutoRejectTest {

    @Mock
    private CouponService couponService;
    @Mock
    private ExclusiveJobRunner exclusiveJobRunner;
    @Mock
    private OrderAutoRejectJob orderAutoRejectJob;
    @InjectMocks
    private ScheduledTasks scheduledTasks;

    @Test
    @DisplayName("多實例時只由取得鎖的實例掃描：任務以 order-auto-reject 名稱、120 秒租約交給 ExclusiveJobRunner")
    void autoRejectRunsThroughTheExclusiveRunner() {
        scheduledTasks.autoRejectExpiredPendingOrders();

        ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        verify(exclusiveJobRunner).runExclusively(eq("order-auto-reject"), eq(120L), job.capture());

        // 取得鎖之後真正執行的是掃描
        job.getValue().run();
        verify(orderAutoRejectJob).sweep(any());
    }
}
