package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    private final CouponService couponService;
    private final ExclusiveJobRunner exclusiveJobRunner;
    private final OrderAutoRejectJob orderAutoRejectJob;

    // Cron expression for 00:00:00 every day
    // 多實例部署時只由取得鎖的實例執行（見 ExclusiveJobRunner）；任務本身是冪等的。
    @Scheduled(cron = "0 0 0 * * *")
    public void expireCouponsDaily() {
        exclusiveJobRunner.runExclusively("coupon-expire-old", 600, couponService::expireOldCoupons);
    }

    /**
     * 待接單逾時自動拒單（N-32）：每 30 秒掃一次。
     *
     * <p>租約 120 秒只在實例中途當機時才會生效，任務本身只需數秒。任務是冪等的：
     * 同一張單即使被重複掃到，鎖內的狀態判斷也只會拒單一次。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void autoRejectExpiredPendingOrders() {
        exclusiveJobRunner.runExclusively("order-auto-reject", 120,
                () -> orderAutoRejectJob.sweep(LocalDateTime.now(ZoneId.of("Asia/Taipei"))));
    }
}
