package com.example.demo.service;

import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.service.order.AutoRejectPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 待接單逾時自動拒單（N-32，2026-10-19）：改由伺服器排程執行，不再依賴門市後台開著。
 *
 * <p>每輪先以一條查詢找出逾時的 SUBMITTED 訂單 id（一批最多 {@link #BATCH_SIZE} 筆），
 * 再逐筆呼叫 {@link AnalyticsService#autoRejectIfStillPending}。每筆一個交易：單筆失敗不影響其他筆，
 * 下一輪會再試。推播在該筆交易提交之後才送，店家端不會收到尚未生效的狀態。
 *
 * <p>本類別本身不是交易。若把整批包在同一個交易裡，一筆失敗就會把已完成的退款一起回滾。
 *
 * <p>多實例時由 {@link ScheduledTasks} 以 {@link ExclusiveJobRunner} 包住，同一時間只有一個實例掃描。
 * 即使互斥鎖逾期造成重疊，逐筆的列鎖與鎖內的狀態判斷仍保證每張單只會退款一次。
 */
@Slf4j
@Component
public class OrderAutoRejectJob {

    static final int BATCH_SIZE = 100;

    @Autowired
    private GroupOrderRepository groupOrderRepository;
    @Autowired
    private AnalyticsService analyticsService;
    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 掃描一輪。{@code now} 由呼叫端傳入，方便測試固定時間。
     */
    public void sweep(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(AutoRejectPolicy.PENDING_WINDOW);
        List<Long> expired = groupOrderRepository.findExpiredPendingOrderIds(cutoff, PageRequest.of(0, BATCH_SIZE));
        int rejected = 0;
        for (Long orderId : expired) {
            try {
                if (analyticsService.autoRejectIfStillPending(orderId, now)) {
                    rejected++;
                    notifyRejected(orderId);
                }
            } catch (RuntimeException e) {
                log.warn("待接單自動拒單失敗，訂單 {} 下一輪再試：{}", orderId, e.getMessage());
            }
        }
        if (rejected > 0) {
            log.info("待接單逾時自動拒單：本輪 {} 筆", rejected);
        }
    }

    private void notifyRejected(Long orderId) {
        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/order/" + orderId, Map.of("status", "REJECTED"));
        }
    }
}
