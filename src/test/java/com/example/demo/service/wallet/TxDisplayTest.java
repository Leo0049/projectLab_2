package com.example.demo.service.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 帳本顯示規則的純邏輯測試（不載入 Spring context）。
 *
 * <p>重點在**舊資料**：正式資料庫裡有一批 type 是「標題\n說明」的紀錄，
 * 這些列不能因為換了格式就顯示成「其他交易」。
 */
class TxDisplayTest {

    private static final BigDecimal OUT = new BigDecimal("-35");
    private static final BigDecimal IN = new BigDecimal("35");

    @Test
    @DisplayName("新格式：type 是 token，description 獨立一欄")
    void newFormatKeepsTypeAndDescription() {
        var e = TxDisplay.normalize(TxType.PAYMENT, "個人訂單 #12 結帳扣款", OUT);
        assertEquals(TxType.PAYMENT, e.type());
        assertEquals("個人訂單 #12 結帳扣款", e.description());
        assertEquals("消費扣款", e.label());
    }

    @Test
    @DisplayName("舊資料：type 塞了「標題\\n說明」要能拆回兩欄")
    void legacyTwoLineTypeIsSplit() {
        var e = TxDisplay.normalize("消費扣款\n揪團結帳扣款 (已扣除團員已付部分)", null, OUT);
        assertEquals(TxType.PAYMENT, e.type());
        assertEquals("揪團結帳扣款 (已扣除團員已付部分)", e.description());
        assertEquals("消費扣款", e.label());
    }

    @Test
    @DisplayName("舊資料：Refund 開頭要歸到 REFUND")
    void legacyRefundIsRecognised() {
        var e = TxDisplay.normalize("Refund\n揪團取消退款 (品項: 四季春青茶)", null, IN);
        assertEquals(TxType.REFUND, e.type());
        assertEquals("退款", e.label());
    }

    @Test
    @DisplayName("舊資料：ESCROW 直接黏在說明前面（少了換行）也要拆對")
    void legacyEscrowWithoutSeparator() {
        var e = TxDisplay.normalize("ESCROWOrder #7 結帳扣款", null, OUT);
        assertEquals(TxType.ESCROW, e.type());
        assertEquals("Order #7 結帳扣款", e.description());
    }

    @Test
    @DisplayName("補款要靠正負號分辨付出方與收取方")
    void repaymentDirectionComesFromSign() {
        assertEquals(TxType.REPAYMENT, TxDisplay.normalize("支付補款\n揪團轉付給團長 (補款)", null, OUT).type());
        assertEquals("主動補款", TxDisplay.normalize("支付補款\n揪團轉付給團長 (補款)", null, OUT).label());
        assertEquals(TxType.REPAYMENT_RECEIVED,
                TxDisplay.normalize("收到團員補款 (團員名稱: 小明, 團員ID: 9)", null, IN).type());
        assertEquals("接收補款", TxDisplay.normalize("收到團員補款 (團員名稱: 小明, 團員ID: 9)", null, IN).label());
    }

    @Test
    @DisplayName("TOPUP 與舊的 Recharge 都算儲值")
    void topUpAliases() {
        assertEquals(TxType.TOPUP, TxDisplay.normalize("TOPUP", null, IN).type());
        assertEquals(TxType.TOPUP, TxDisplay.normalize("Recharge", null, IN).type());
        assertEquals("帳戶儲值", TxDisplay.normalize("TOPUP", null, IN).label());
    }

    @Test
    @DisplayName("type 為 null 或空字串時不可丟例外")
    void nullTypeIsSafe() {
        assertEquals("", TxDisplay.normalize(null, null, IN).description());
        assertEquals(TxType.PAYMENT, TxDisplay.normalize("", null, OUT).type());
    }
}
