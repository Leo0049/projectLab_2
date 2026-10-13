package com.example.demo.service.order;

import java.util.Locale;

/**
 * 揪團退款與取消權限的規則（純函式，可單獨測試）。
 *
 * <p>團長於 2026-10-13 定案，兩條規則：
 * <ol>
 *   <li><b>送單前</b>（OPEN）：團員已付款（已結帳）的品項可自行取消並退款。退款回到該品項<b>當下的付款方式</b>：
 *       錢包付款退回錢包；現金、信用卡不經錢包，系統不入帳。品項在送單前已取消，因此無後續收款與對帳。</li>
 *   <li><b>送單後</b>（SUBMITTED）：團員不可自行取消並退款。取消或退款一律由團長操作，且退款<b>入團長帳號</b>。</li>
 * </ol>
 *
 * <p>送單後進入製作（PREPARING、READY、COMPLETED）的訂單，品項不可再被移除，也不產生退款；
 * 這一段由店家端掌握，見 {@code OrderStatusGuard} 與 {@code GroupOrderService.deleteGroupOrder}。
 */
public final class GroupRefundPolicy {

    private GroupRefundPolicy() {
    }

    /** 揪團所在的階段。由 {@code GroupOrder.status} 歸類而來，不是資料庫欄位。 */
    public enum Stage {
        /** 開放中：可加點、可截單、團員可自行取消未送單的品項 */
        OPEN,
        /** 截單中（團長鎖定）：不可加點，尚未送單 */
        LOCKED,
        /** 已送單給店家：團員不可自行取消，團長操作退款入團長帳號 */
        SUBMITTED,
        /** 店家已接單、製作中或已完成：不可再移除品項，不產生退款 */
        FULFILLING,
        /** 已取消、已拒單，或未知狀態（一律拒絕） */
        CLOSED
    }

    /** 已付款品項被移除或整單取消時，退款要進哪個帳本 */
    public enum PaidRefundTo {
        /** 退回團員錢包（送單前、錢包付款） */
        MEMBER_WALLET,
        /** 入團長錢包（送單後，不論付款方式） */
        HOST_WALLET,
        /** 不入帳：現金、信用卡或未記錄的付款方式，由線下處理，或本來就無需退款 */
        NONE
    }

    /** 把資料庫的 status 字串歸類成階段。大小寫與前後空白不敏感；未知值歸為 CLOSED，一律拒絕。 */
    public static Stage stageOf(String groupStatus) {
        String s = normalize(groupStatus);
        if (s == null) {
            return Stage.CLOSED;
        }
        return switch (s) {
            case "OPEN" -> Stage.OPEN;
            case "LOCKED" -> Stage.LOCKED;
            case "SUBMITTED" -> Stage.SUBMITTED;
            case "PREPARING", "READY", "COMPLETED" -> Stage.FULFILLING;
            default -> Stage.CLOSED;
        };
    }

    /** 團員可否自行取消自己的品項並退款：只有開放中（OPEN）。送單後一律由團長處理。 */
    public static boolean memberMaySelfCancel(String groupStatus) {
        return stageOf(groupStatus) == Stage.OPEN;
    }

    /** 團長可否移除任一成員的品項：送單前（OPEN、LOCKED）與送單後（SUBMITTED）。 */
    public static boolean hostMayRemoveItems(String groupStatus) {
        Stage stage = stageOf(groupStatus);
        return stage == Stage.OPEN || stage == Stage.LOCKED || stage == Stage.SUBMITTED;
    }

    /** 整單可否取消（團長取消或刪除揪團）：只有 OPEN、SUBMITTED，與 {@link OrderStatusGuard#isOpenOrSubmitted} 一致。 */
    public static boolean wholeGroupCancellable(String groupStatus) {
        Stage stage = stageOf(groupStatus);
        return stage == Stage.OPEN || stage == Stage.SUBMITTED;
    }

    /** 團長可否在截單（LOCKED）與重開（OPEN）之間切換：只有尚未送單的揪團。 */
    public static boolean mayToggleLock(String currentStatus) {
        Stage stage = stageOf(currentStatus);
        return stage == Stage.OPEN || stage == Stage.LOCKED;
    }

    /**
     * 已付款品項被移除或整單取消時的退款去向。
     *
     * @param stage       揪團所在階段（由 {@link #stageOf} 得出）
     * @param paymentType 品項記錄的付款方式（WALLET、CASH、CREDIT_CARD，或 null）
     */
    public static PaidRefundTo paidRefundTo(Stage stage, String paymentType) {
        if (stage == null) {
            return PaidRefundTo.NONE;
        }
        return switch (stage) {
            case OPEN, LOCKED -> isWallet(paymentType) ? PaidRefundTo.MEMBER_WALLET : PaidRefundTo.NONE;
            case SUBMITTED -> PaidRefundTo.HOST_WALLET;
            case FULFILLING, CLOSED -> PaidRefundTo.NONE;
        };
    }

    /** 付款方式是否為錢包。只有明確記錄為 WALLET 才算；null 不視為錢包，避免無來源的入帳。 */
    public static boolean isWallet(String paymentType) {
        String p = normalize(paymentType);
        return "WALLET".equals(p);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
