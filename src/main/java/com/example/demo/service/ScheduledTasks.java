package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    private final CouponService couponService;

    // Cron expression for 00:00:00 every day
    @Scheduled(cron = "0 0 0 * * *")
    public void expireCouponsDaily() {
        couponService.expireOldCoupons();
    }
}
