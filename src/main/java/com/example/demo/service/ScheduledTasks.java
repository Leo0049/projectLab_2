package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    private final CouponService couponService;
    private final ExclusiveJobRunner exclusiveJobRunner;

    // Cron expression for 00:00:00 every day
    // 多實例部署時只由取得鎖的實例執行（見 ExclusiveJobRunner）；任務本身是冪等的。
    @Scheduled(cron = "0 0 0 * * *")
    public void expireCouponsDaily() {
        exclusiveJobRunner.runExclusively("coupon-expire-old", 600, couponService::expireOldCoupons);
    }
}
