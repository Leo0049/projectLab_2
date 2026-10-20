package com.example.demo.service.order;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 待接單逾時自動拒單的規則（純函式，可單獨測試）。
 *
 * <p>規則（2026-10-19，N-32）：
 * <ul>
 *   <li><b>只適用 SUBMITTED</b>：已送單、等待店家接單的訂單。揪團仍在加點的 OPEN 不在此列。
 *       原本的瀏覽器版本把 OPEN 也算進待接單，等於在團長還沒送單時就把整團拒掉。</li>
 *   <li><b>計時起點是送單時間</b> {@code submittedAt}；沒有送單時間的舊資料退回建立時間 {@code createdAt}。</li>
 *   <li><b>嚴格超過 10 分鐘才逾時</b>：剛好滿 10 分鐘仍可接單。</li>
 * </ul>
 *
 * <p>排程的查詢只做粗篩（見 {@code GroupOrderRepository.findExpiredPendingOrderIds}），
 * 真正的判斷在 {@code AnalyticsService.autoRejectIfStillPending} 的列鎖之內再呼叫這裡一次，
 * 所以掃描與拒單之間若店家已接單，這裡會回傳 false。
 */
public final class AutoRejectPolicy {

    /** 待接單的時限。 */
    public static final Duration PENDING_WINDOW = Duration.ofMinutes(10);

    private AutoRejectPolicy() {
    }

    /** 是否屬於自動拒單的範圍（只有 SUBMITTED）。大小寫與前後空白不敏感；null 不屬於。 */
    public static boolean isEligibleStatus(String status) {
        return status != null && "SUBMITTED".equals(status.trim().toUpperCase(Locale.ROOT));
    }

    /** 計時起點：送單時間；沒有送單時間時（舊資料）退回建立時間。兩者都沒有時回 null。 */
    public static LocalDateTime anchorOf(LocalDateTime submittedAt, LocalDateTime createdAt) {
        return createdAt;
    }

    /** 逾時期限：計時起點加上時限。沒有起點時回 null。 */
    public static LocalDateTime deadlineOf(LocalDateTime submittedAt, LocalDateTime createdAt) {
        LocalDateTime anchor = anchorOf(submittedAt, createdAt);
        return anchor == null ? null : anchor.plus(PENDING_WINDOW);
    }

    /** 此刻是否應自動拒單：狀態為 SUBMITTED，且 {@code now} 嚴格晚於期限。 */
    public static boolean isExpired(String status, LocalDateTime submittedAt, LocalDateTime createdAt,
                                    LocalDateTime now) {
        if (!isEligibleStatus(status) || now == null) {
            return false;
        }
        LocalDateTime deadline = deadlineOf(submittedAt, createdAt);
        return deadline != null && now.isAfter(deadline);
    }
}
