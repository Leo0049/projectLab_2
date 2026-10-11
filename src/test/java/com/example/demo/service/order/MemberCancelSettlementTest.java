package com.example.demo.service.order;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 純邏輯測試（不載入 Spring context）。
 *
 * <p>第一支測試就是這輪的 P0：未結帳的揪團被成員取消時，<strong>不得</strong>有任何餘額異動。
 * 修補前 {@code cancelMemberItems} 會把品項金額當成「要退給團長的代墊款」直接入帳。
 */
class MemberCancelSettlementTest {

    private static final MemberCancelSettlement.Item UNPAID_35 =
            MemberCancelSettlement.Item.of("UNPAID", new BigDecimal("35.00"), null);

    @Test
    @DisplayName("尚未結帳（escrow=0）的未付款品項取消時不得退款給任何人")
    void openGroupCancelMovesNoMoney() {
        var result = MemberCancelSettlement.settle(List.of(UNPAID_35), BigDecimal.ZERO);

        assertEquals(0, result.memberRefund().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.hostRefund().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.amountRemoved().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("escrow 為 null 時同樣不得退款")
    void nullEscrowMovesNoMoney() {
        var result = MemberCancelSettlement.settle(List.of(UNPAID_35), null);

        assertEquals(0, result.hostRefund().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.remainingEscrow().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("團長代墊的品項：退還淨額給團長並扣減 escrow")
    void escrowedItemRefundsHostAndReducesEscrow() {
        var escrowed = MemberCancelSettlement.Item.of("ESCROWED", new BigDecimal("35.00"), null);

        var result = MemberCancelSettlement.settle(List.of(escrowed), new BigDecimal("35.00"));

        assertEquals("35.00", result.hostRefund().toPlainString());
        assertEquals("0.00", result.remainingEscrow().toPlainString());
        assertEquals(0, result.memberRefund().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("退款給團長不得超過實際代墊金額")
    void hostRefundIsCappedByEscrow() {
        var escrowed = MemberCancelSettlement.Item.of("ESCROWED", new BigDecimal("35.00"), null);

        var result = MemberCancelSettlement.settle(List.of(escrowed), new BigDecimal("20.00"));

        assertEquals("20.00", result.hostRefund().toPlainString());
        assertEquals("0.00", result.remainingEscrow().toPlainString());
        assertEquals("20.00", result.amountRemoved().toPlainString());
    }

    @Test
    @DisplayName("代墊退款要扣掉品項折扣（escrow 當初就是淨額）")
    void discountIsDeductedFromHostRefund() {
        var escrowed = MemberCancelSettlement.Item.of("ESCROWED",
                new BigDecimal("40.00"), new BigDecimal("5.00"));

        var result = MemberCancelSettlement.settle(List.of(escrowed), new BigDecimal("40.00"));

        assertEquals("35.00", result.hostRefund().toPlainString());
    }

    @Test
    @DisplayName("團員自己付過的品項退給團員")
    void paidItemRefundsMember() {
        var paid = MemberCancelSettlement.Item.of("PAID",
                new BigDecimal("35.00"), new BigDecimal("5.00"));

        var result = MemberCancelSettlement.settle(List.of(paid), new BigDecimal("40.00"));

        assertEquals("30.00", result.memberRefund().toPlainString());
        assertEquals(0, result.hostRefund().compareTo(BigDecimal.ZERO));
        // 團員自己付的錢不在 escrow 裡，不該動到它
        assertEquals("40.00", result.remainingEscrow().toPlainString());
    }

    @Test
    @DisplayName("混合情境：只退該退的、只動該動的")
    void mixedItemsAreSettledItemByItem() {
        var items = List.of(
                MemberCancelSettlement.Item.of("PAID", new BigDecimal("30.00"), null),
                MemberCancelSettlement.Item.of("ESCROWED", new BigDecimal("35.00"), null),
                MemberCancelSettlement.Item.of("UNPAID", new BigDecimal("99.00"), null));

        var result = MemberCancelSettlement.settle(items, new BigDecimal("35.00"));

        assertEquals("30.00", result.memberRefund().toPlainString());
        assertEquals("35.00", result.hostRefund().toPlainString());
        assertEquals("65.00", result.amountRemoved().toPlainString());
        assertEquals("0.00", result.remainingEscrow().toPlainString());
    }

    @Test
    @DisplayName("負值與 null 金額不得讓退款變成負數或爆掉")
    void malformedAmountsAreTreatedAsZero() {
        var weird = new MemberCancelSettlement.Item(true, false, null, new BigDecimal("50.00"));

        var result = MemberCancelSettlement.settle(List.of(weird), new BigDecimal("-5.00"));

        assertEquals(0, result.memberRefund().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.hostRefund().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.remainingEscrow().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("空清單不得有任何異動")
    void emptyListIsNoOp() {
        var result = MemberCancelSettlement.settle(List.of(), new BigDecimal("35.00"));

        assertEquals(0, result.amountRemoved().compareTo(BigDecimal.ZERO));
        assertEquals("35.00", result.remainingEscrow().toPlainString());
    }
}
