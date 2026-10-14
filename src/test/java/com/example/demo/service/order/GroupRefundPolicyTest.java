package com.example.demo.service.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 揪團退款與取消權限的規則（團長於 2026-10-13 定案，見 CLAUDE.md「揪團品項的取消與退款」）。
 *
 * <ul>
 *   <li>送單前（OPEN）：團員已付款的品項可自行取消；退款回到「當下的付款方式」——
 *       錢包付款退回錢包，現金與信用卡不經團員錢包，退款入團長帳號（2026-10-14 補充）（品項在送單前取消，無後續收款與對帳）。</li>
 *   <li>送單後（SUBMITTED）：團員不可自行取消並退款；取消或退款一律由團長操作，且退款入團長帳號。</li>
 *   <li>製作中（PREPARING 以後）與已結束：品項不可再被移除，也不產生任何退款。</li>
 * </ul>
 */
class GroupRefundPolicyTest {

    private static final GroupRefundPolicy.Stage OPEN = GroupRefundPolicy.Stage.OPEN;
    private static final GroupRefundPolicy.Stage LOCKED = GroupRefundPolicy.Stage.LOCKED;
    private static final GroupRefundPolicy.Stage SUBMITTED = GroupRefundPolicy.Stage.SUBMITTED;
    private static final GroupRefundPolicy.Stage FULFILLING = GroupRefundPolicy.Stage.FULFILLING;
    private static final GroupRefundPolicy.Stage CLOSED = GroupRefundPolicy.Stage.CLOSED;

    private static final GroupRefundPolicy.PaidRefundTo MEMBER_WALLET = GroupRefundPolicy.PaidRefundTo.MEMBER_WALLET;
    private static final GroupRefundPolicy.PaidRefundTo HOST_WALLET = GroupRefundPolicy.PaidRefundTo.HOST_WALLET;
    private static final GroupRefundPolicy.PaidRefundTo NONE = GroupRefundPolicy.PaidRefundTo.NONE;

    @Test
    @DisplayName("狀態歸類：OPEN、LOCKED 為送單前；SUBMITTED 為送單後；製作中為 FULFILLING；未知或已取消為 CLOSED")
    void classifiesStatuses() {
        assertEquals(OPEN, GroupRefundPolicy.stageOf("OPEN"));
        assertEquals(OPEN, GroupRefundPolicy.stageOf(" open "));
        assertEquals(LOCKED, GroupRefundPolicy.stageOf("LOCKED"));
        assertEquals(SUBMITTED, GroupRefundPolicy.stageOf("SUBMITTED"));
        assertEquals(FULFILLING, GroupRefundPolicy.stageOf("PREPARING"));
        assertEquals(FULFILLING, GroupRefundPolicy.stageOf("READY"));
        assertEquals(FULFILLING, GroupRefundPolicy.stageOf("COMPLETED"));
        assertEquals(CLOSED, GroupRefundPolicy.stageOf("CANCELLED"));
        assertEquals(CLOSED, GroupRefundPolicy.stageOf("REJECTED"));
        assertEquals(CLOSED, GroupRefundPolicy.stageOf(null));
        assertEquals(CLOSED, GroupRefundPolicy.stageOf(""));
        assertEquals(CLOSED, GroupRefundPolicy.stageOf("SOMETHING_NEW"));
    }

    @Test
    @DisplayName("團員只能在 OPEN 自行取消；送單後（含 LOCKED）一律不可，改由團長處理")
    void memberSelfCancelOnlyWhileOpen() {
        assertTrue(GroupRefundPolicy.memberMaySelfCancel("OPEN"));
        assertFalse(GroupRefundPolicy.memberMaySelfCancel("LOCKED"));
        assertFalse(GroupRefundPolicy.memberMaySelfCancel("SUBMITTED"));
        assertFalse(GroupRefundPolicy.memberMaySelfCancel("PREPARING"));
        assertFalse(GroupRefundPolicy.memberMaySelfCancel("CANCELLED"));
        assertFalse(GroupRefundPolicy.memberMaySelfCancel(null));
    }

    @Test
    @DisplayName("團長可移除品項的階段：OPEN、LOCKED、SUBMITTED；製作中與已結束不可")
    void hostMayRemoveOnlyBeforeFulfillment() {
        assertTrue(GroupRefundPolicy.hostMayRemoveItems("OPEN"));
        assertTrue(GroupRefundPolicy.hostMayRemoveItems("LOCKED"));
        assertTrue(GroupRefundPolicy.hostMayRemoveItems("SUBMITTED"));
        assertFalse(GroupRefundPolicy.hostMayRemoveItems("PREPARING"));
        assertFalse(GroupRefundPolicy.hostMayRemoveItems("READY"));
        assertFalse(GroupRefundPolicy.hostMayRemoveItems("COMPLETED"));
        assertFalse(GroupRefundPolicy.hostMayRemoveItems("CANCELLED"));
    }

    @Test
    @DisplayName("整單取消：店家接單前（OPEN、LOCKED、SUBMITTED）都可以；接單後與已結束不可（大小寫與空白不敏感）")
    void wholeGroupCancelUntilStoreAccepts() {
        assertTrue(GroupRefundPolicy.wholeGroupCancellable("OPEN"));
        assertTrue(GroupRefundPolicy.wholeGroupCancellable("LOCKED"));
        assertTrue(GroupRefundPolicy.wholeGroupCancellable(" submitted "));
        assertFalse(GroupRefundPolicy.wholeGroupCancellable("PREPARING"));
        assertFalse(GroupRefundPolicy.wholeGroupCancellable("READY"));
        assertFalse(GroupRefundPolicy.wholeGroupCancellable("COMPLETED"));
        assertFalse(GroupRefundPolicy.wholeGroupCancellable("CANCELLED"));
        assertFalse(GroupRefundPolicy.wholeGroupCancellable(null));
    }

    @Test
    @DisplayName("截單與重開只能在 OPEN、LOCKED 之間切換；送單後的狀態不可被覆寫")
    void lockStateOnlyBeforeSubmission() {
        assertTrue(GroupRefundPolicy.mayToggleLock("OPEN"));
        assertTrue(GroupRefundPolicy.mayToggleLock("LOCKED"));
        assertFalse(GroupRefundPolicy.mayToggleLock("SUBMITTED"));
        assertFalse(GroupRefundPolicy.mayToggleLock("PREPARING"));
        assertFalse(GroupRefundPolicy.mayToggleLock("COMPLETED"));
        assertFalse(GroupRefundPolicy.mayToggleLock("CANCELLED"));
        assertFalse(GroupRefundPolicy.mayToggleLock(null));
    }

    @Test
    @DisplayName("送單前已付款的錢包品項退回團員錢包；沒有記錄付款方式的舊資料不入帳，避免憑空生出餘額")
    void paidWalletItemsGoBackToMemberBeforeSubmission() {
        assertEquals(MEMBER_WALLET, GroupRefundPolicy.paidRefundTo(OPEN, "WALLET"));
        assertEquals(MEMBER_WALLET, GroupRefundPolicy.paidRefundTo(LOCKED, " wallet "));
        assertEquals(NONE, GroupRefundPolicy.paidRefundTo(OPEN, null));
        assertEquals(NONE, GroupRefundPolicy.paidRefundTo(OPEN, "STORE_CREDIT"));
    }

    @Test
    @DisplayName("送單前現金或信用卡的已付款品項，退款入團長帳號（線下收款，退款一律入團長）")
    void paidCashOrCardBeforeSubmissionGoesToHost() {
        assertEquals(HOST_WALLET, GroupRefundPolicy.paidRefundTo(OPEN, "CASH"));
        assertEquals(HOST_WALLET, GroupRefundPolicy.paidRefundTo(OPEN, "credit_card"));
        assertEquals(HOST_WALLET, GroupRefundPolicy.paidRefundTo(LOCKED, "CASH"));
        assertTrue(GroupRefundPolicy.isHostCollected("CASH"));
        assertTrue(GroupRefundPolicy.isHostCollected(" CREDIT_CARD "));
        assertFalse(GroupRefundPolicy.isHostCollected("WALLET"));
        assertFalse(GroupRefundPolicy.isHostCollected(null));
    }

    @Test
    @DisplayName("送單後的已付款品項一律入團長帳號，不論當初的付款方式")
    void paidItemsAfterSubmissionGoToHost() {
        assertEquals(HOST_WALLET, GroupRefundPolicy.paidRefundTo(SUBMITTED, "WALLET"));
        assertEquals(HOST_WALLET, GroupRefundPolicy.paidRefundTo(SUBMITTED, "CASH"));
        assertEquals(HOST_WALLET, GroupRefundPolicy.paidRefundTo(SUBMITTED, null));
    }

    @Test
    @DisplayName("製作中與已結束的訂單不產生任何已付款品項的退款")
    void nothingRefundedOnceFulfillingOrClosed() {
        assertEquals(NONE, GroupRefundPolicy.paidRefundTo(FULFILLING, "WALLET"));
        assertEquals(NONE, GroupRefundPolicy.paidRefundTo(CLOSED, "WALLET"));
    }
}
