package com.example.demo.service.order;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * 揪團退款與取消權限的規則（純函式，可單獨測試）。
 *
 * <p>團長於 2026-10-13 定案兩條規則；2026-10-14 補充現金與信用卡的去向，並確認截單後團員不可自行取消：
 * <ol>
 *   <li><b>送單前</b>（OPEN）：團員已付款（已結帳）的品項可自行取消並退款。錢包付款退回團員錢包；
 *       現金或信用卡付款的退款<b>入團長帳號</b>，團員再向團長領回。品項在送單前已取消，無後續收款與對帳。
 *       截單（LOCKED）後團員不可自行取消，見 {@link #memberMaySelfCancel}。</li>
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
        /** 截單中（團長鎖定）：不可加點、團員不可自行取消；團長仍可移除品項或整單取消，店家接單前皆可 */
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
        /** 入團長帳號（送單後不論付款方式；送單前的現金與信用卡） */
        HOST_WALLET,
        /** 不入帳：付款方式未記錄，或製作中與已結束的訂單，本來就無需退款 */
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

    /** 團員可否自行取消自己的品項並退款：只有開放中（OPEN）。截單（LOCKED）與送單後一律由團長處理（團長 2026-10-14 確認）。 */
    public static boolean memberMaySelfCancel(String groupStatus) {
        return stageOf(groupStatus) == Stage.OPEN;
    }

    /** 團長可否移除任一成員的品項：送單前（OPEN、LOCKED）與送單後（SUBMITTED）。 */
    public static boolean hostMayRemoveItems(String groupStatus) {
        Stage stage = stageOf(groupStatus);
        return stage == Stage.OPEN || stage == Stage.LOCKED || stage == Stage.SUBMITTED;
    }

    /**
     * 整單可否取消（團長取消或刪除揪團）：店家接單（PREPARING 以後）之前都可以，包含截單中（LOCKED）。
     * 店家接單後退款會變成店家已在製作、款項卻已退回，因此不可。
     */
    public static boolean wholeGroupCancellable(String groupStatus) {
        Stage stage = stageOf(groupStatus);
        return stage == Stage.OPEN || stage == Stage.LOCKED || stage == Stage.SUBMITTED;
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
            case OPEN, LOCKED -> {
                if (isWallet(paymentType)) {
                    yield PaidRefundTo.MEMBER_WALLET;
                }
                yield isHostCollected(paymentType) ? PaidRefundTo.HOST_WALLET : PaidRefundTo.NONE;
            }
            case SUBMITTED -> PaidRefundTo.HOST_WALLET;
            case FULFILLING, CLOSED -> PaidRefundTo.NONE;
        };
    }

    /** 付款方式是否為線下收款（現金或信用卡）。這類款項不經團員錢包，送單前的退款入團長帳號（團長 2026-10-14 定案）。 */
    public static boolean isHostCollected(String paymentType) {
        String p = normalize(paymentType);
        return "CASH".equals(p) || "CREDIT_CARD".equals(p);
    }

    /**
     * 結帳後的整單代墊金（escrowAmount）：取消或移除品項時，這筆金額會退還給團長。
     *
     * <p>只有錢包付款才真的從團長錢包扣過款，因此只有錢包才能留下待退金額。現金或信用卡沒有扣款，
     * 若留下待退金額，取消時就會把一筆從未扣過的錢退進團長錢包（2026-10-19 審查時發現；
     * 自動拒單會直接觸發這條路徑）。判斷方式必須與 {@code GroupOrderService.checkout} 的扣款條件完全相同。
     *
     * @param paymentMethod  結帳選擇的付款方式（與扣款條件一樣，只認大寫 WALLET）
     * @param amountToCharge 結帳時團長應付的金額
     */
    public static BigDecimal escrowAfterCheckout(String paymentMethod, BigDecimal amountToCharge) {
        if (!"WALLET".equals(paymentMethod)) {
            return BigDecimal.ZERO;
        }
        return amountToCharge;
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
