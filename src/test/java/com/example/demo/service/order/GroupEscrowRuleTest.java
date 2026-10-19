package com.example.demo.service.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 結帳後的整單代墊金（N-32，2026-10-19）。
 *
 * <p>取消時 escrowAmount 會退還給團長；只有錢包付款真的扣過款，現金與信用卡留下的待退金額
 * 會在取消時憑空退給團長（自動拒單會直接觸發）。
 */
class GroupEscrowRuleTest {

    private static final BigDecimal AMOUNT = new BigDecimal("35.00");

    @Test
    @DisplayName("錢包結帳：保留整單代墊金，供取消時退還")
    void walletCheckoutKeepsTheEscrow() {
        assertEquals(0, AMOUNT.compareTo(GroupRefundPolicy.escrowAfterCheckout("WALLET", AMOUNT)));
    }

    @Test
    @DisplayName("現金或信用卡結帳：沒有扣款，不留待退金額")
    void cashAndCardCheckoutLeavesNothingToRefund() {
        assertEquals(0, BigDecimal.ZERO.compareTo(GroupRefundPolicy.escrowAfterCheckout("CASH", AMOUNT)));
        assertEquals(0, BigDecimal.ZERO.compareTo(GroupRefundPolicy.escrowAfterCheckout("CREDIT_CARD", AMOUNT)));
    }

    @Test
    @DisplayName("判斷條件與扣款相同：只認大寫 WALLET，其餘（含 null）都不留待退金額")
    void onlyTheExactWalletCodeCountsLikeTheDebitCheck() {
        assertEquals(0, BigDecimal.ZERO.compareTo(GroupRefundPolicy.escrowAfterCheckout(null, AMOUNT)));
        assertEquals(0, BigDecimal.ZERO.compareTo(GroupRefundPolicy.escrowAfterCheckout("wallet", AMOUNT)));
    }

    @Test
    @DisplayName("沒有金額時為 0")
    void missingAmountIsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(GroupRefundPolicy.escrowAfterCheckout("WALLET", null)));
    }
}
