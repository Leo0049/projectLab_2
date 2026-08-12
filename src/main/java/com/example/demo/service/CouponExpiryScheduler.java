package com.example.demo.service;

import com.example.demo.entity.UserCoupon;
import com.example.demo.repository.UserCouponRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class CouponExpiryScheduler {

    @Autowired
    private UserCouponRepository userCouponRepository;

    // 每天 00:01 執行，把過期的券標記為 expired
    @Scheduled(cron = "0 1 0 * * *")
    public void expireCoupons() {
        LocalDateTime now = LocalDateTime.now();
        List<UserCoupon> expired = userCouponRepository.findByStatusAndExpiredAtBefore("unused", now);
        expired.forEach(c -> c.setStatus("expired"));
        userCouponRepository.saveAll(expired);
        log.info("已標記 {} 張優惠券為過期", expired.size());
    }
}
