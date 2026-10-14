package com.example.demo.service.order;

import com.example.demo.exception.CustomException;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 顧客端（訂單發起人）可用的訂單狀態變更白名單（純函式，可單獨測試）。
 *
 * <p>⚠️ 原本 {@code OrderService.updateOrderStatusWithUserCheck} 只驗「是不是發起人」，
 * 之後就 {@code order.setStatus(status)} 直接把字串寫進去，沒有任何白名單或流程驗證。
 * 授權修好了（S-6），但狀態本身仍可被發起人任意指定，實測可造成：
 *
 * <ul>
 *   <li>自行設為 {@code COMPLETED} → 繞過門市流程，立刻取得評分資格
 *       （{@code OrderRatingService.upsertRating} 要求 COMPLETED），污染 {@code stores.avg_rating}；</li>
 *   <li>把已結帳的 {@code SUBMITTED} 改回 {@code OPEN} → 揪團重新開放加點，
 *       再走一次 {@code checkout}（該流程的守衛只看 OPEN／LOCKED），同一批品項被重複扣款；</li>
 *   <li>送 {@code PAID} 以外的任意字串 → 門市後台依狀態分區的畫面出現不存在的狀態。</li>
 * </ul>
 *
 * <p>因此這裡只放行一個真正屬於顧客的動作：<strong>現金單的付款通知</strong>
 * （{@code OPEN}/{@code SUBMITTED} → {@code PAID}）。取消一律走
 * {@code PUT /api/orders/{orderId}/cancel/v2}，那條路徑才有退款與優惠券還原；
 * 門市端的接單／製作／完成則走 {@code /api/stores/dashboard/orders/{id}/…}。
 */
public final class OrderStatusGuard {

    private OrderStatusGuard() {
    }

    /** 允許的顧客端轉移：目前只有「通知已付款」 */
    private static final Map<String, Set<String>> CUSTOMER_ALLOWED = Map.of(
            "OPEN", Set.of("PAID"),
            "LOCKED", Set.of("PAID"),
            "SUBMITTED", Set.of("PAID"));

    /**
     * @return 正規化（大寫、trim）後的目標狀態
     * @throws CustomException 目標狀態不在白名單內時
     */
    public static String requireCustomerTransition(String currentStatus, String requestedStatus) {
        String to = normalize(requestedStatus);
        if (to == null) {
            throw new CustomException("400", "狀態不可為空");
        }
        if ("CANCELLED".equals(to)) {
            // 這條端點沒有退款與優惠券還原，直接改狀態會讓「已取消但沒退錢」
            throw new CustomException("400", "取消訂單請使用 /api/orders/{orderId}/cancel/v2（該路徑會處理退款與優惠券還原）");
        }
        String from = normalize(currentStatus);
        if (from == null || !CUSTOMER_ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new CustomException("400",
                    "不允許將訂單狀態由 " + currentStatus + " 變更為 " + requestedStatus
                            + "；門市端流程請使用 /api/stores/dashboard/orders/{orderId}/…");
        }
        return to;
    }

    private static String normalize(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }
}
