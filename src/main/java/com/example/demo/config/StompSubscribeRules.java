package com.example.demo.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP SUBSCRIBE 的授權規則（純函式，與 Spring 無關，方便單元測試）。
 *
 * <ul>
 *   <li>{@code /topic/order/{id}}：只限 CUSTOMER，且必須是該訂單的發起人或參與者。</li>
 *   <li>{@code /topic/group/{token}}：只限 CUSTOMER；token 即分享憑證。</li>
 *   <li>{@code /topic/store/{id}}：只允許 STORE，且 id 必須等於連線者的門市 id
 *       （門市 token 的 userId claim 就是門市 id）。</li>
 *   <li>其餘目的地一律拒絕（預設拒絕）。</li>
 * </ul>
 *
 * <p>角色必須一併比對：門市與顧客的 id 會撞號，只比數字會讓門市冒充同號的顧客。
 */
public final class StompSubscribeRules {

    public static final String ROLE_CUSTOMER = "CUSTOMER";
    public static final String ROLE_STORE = "STORE";

    static final Pattern ORDER_TOPIC = Pattern.compile("^/topic/order/(\\d+)$");
    static final Pattern STORE_TOPIC = Pattern.compile("^/topic/store/(\\d+)$");
    static final Pattern GROUP_TOPIC = Pattern.compile("^/topic/group/[^/]+$");

    /** 查詢顧客是否為某訂單的發起人或參與者（由 WebSocketConfig 注入資料庫查詢） */
    @FunctionalInterface
    public interface OrderParticipantCheck {
        boolean isParticipant(long orderId, long userId);
    }

    private StompSubscribeRules() {
    }

    /** 回傳拒絕原因；允許時回傳 null。不會因為輸入而拋出例外。 */
    public static String denyReason(String role, Long userId, String destination, OrderParticipantCheck participants) {
        if (destination == null || destination.isBlank()) {
            return "訂閱目的地不得為空";
        }
        if (userId == null) {
            return "尚未認證，無法訂閱";
        }
        Matcher m = ORDER_TOPIC.matcher(destination);
        if (m.matches()) {
            if (!ROLE_CUSTOMER.equals(role)) {
                return "僅顧客可訂閱訂單推播";
            }
            Long orderId = parseId(m.group(1));
            if (orderId == null) {
                return "無權訂閱此訂單的推播";
            }
            return participants.isParticipant(orderId, userId) ? null : "無權訂閱此訂單的推播";
        }
        m = STORE_TOPIC.matcher(destination);
        if (m.matches()) {
            Long storeId = parseId(m.group(1));
            if (!ROLE_STORE.equals(role) || storeId == null || !storeId.equals(userId)) {
                return "無權訂閱此門市的推播";
            }
            return null;
        }
        if (GROUP_TOPIC.matcher(destination).matches()) {
            return ROLE_CUSTOMER.equals(role) ? null : "僅顧客可訂閱揪團推播";
        }
        return "不支援的訂閱目的地";
    }

    /** 超過 Long 範圍的數字視為無效 id（回 null），而不是拋出例外 */
    private static Long parseId(String digits) {
        try {
            return Long.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
