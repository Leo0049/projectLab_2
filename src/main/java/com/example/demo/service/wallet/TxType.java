package com.example.demo.service.wallet;

/**
 * 帳本的交易種類。{@code transaction_records.type} 只能放這裡的常數。
 *
 * <p>用常數而不是 enum，是因為資料庫裡還有一批舊資料的 type 是顯示字串
 * （{@code "消費扣款\n個人訂單 #12 結帳扣款"}），用 enum 會在讀取時直接炸掉。
 * 讀取一律經過 {@link TxDisplay#normalize}，寫入一律用這裡的常數。
 */
public final class TxType {

    private TxType() {
    }

    /** 儲值 */
    public static final String TOPUP = "TOPUP";
    /** 下單扣款（個人訂單／揪團結帳） */
    public static final String PAYMENT = "PAYMENT";
    /** 團長代墊的託管款（訂單的 escrow_amount） */
    public static final String ESCROW = "ESCROW";
    /** 取消／拒單退款 */
    public static final String REFUND = "REFUND";
    /** 團員補款給團長（付出方） */
    public static final String REPAYMENT = "REPAYMENT";
    /** 團長收到團員補款（收取方） */
    public static final String REPAYMENT_RECEIVED = "REPAYMENT_RECEIVED";
}
