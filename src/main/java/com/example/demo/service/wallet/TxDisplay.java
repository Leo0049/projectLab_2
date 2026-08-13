package com.example.demo.service.wallet;

import java.math.BigDecimal;

/**
 * 帳本一列要怎麼顯示：把 {@code type / description} 正規化成
 * {@code (type, description, label)} 三件事，由伺服器決定，前端只負責畫。
 *
 * <p>⚠️ 這段判斷原本整份寫在 {@code profile.html} 裡，靠
 * {@code fullType.includes('補款')}、{@code startsWith('ESCROW')} 之類的字串比對
 * 去猜這筆是什麼交易。規則散在畫面上，換一個頁面就得再抄一份，
 * 而且 type 欄位一改格式就整排顯示成「其他交易」。
 *
 * <p>純函式，不碰資料庫，對應的測試在 {@code service/wallet/TxDisplayTest}。
 */
public final class TxDisplay {

    private TxDisplay() {
    }

    /** 正規化結果。{@code type} 一定是 {@link TxType} 的常數；{@code label} 是給人看的中文 */
    public record Entry(String type, String description, String label) {
    }

    /**
     * @param rawType     資料庫的 type，可能是新的 token，也可能是舊的兩行顯示字串
     * @param description 新資料才有，舊資料為 null
     * @param amount      正負號用來區分「主動補款」與「接收補款」
     */
    public static Entry normalize(String rawType, String description, BigDecimal amount) {
        String head = rawType == null ? "" : rawType;
        String desc = description;

        // 舊資料：type 裡塞了「標題\n說明」，或 "ESCROW" 直接黏在說明前面
        if (desc == null) {
            int nl = head.indexOf('\n');
            if (nl >= 0) {
                desc = head.substring(nl + 1);
                head = head.substring(0, nl);
            } else if (head.startsWith(TxType.ESCROW) && head.length() > TxType.ESCROW.length()) {
                desc = head.substring(TxType.ESCROW.length());
                head = TxType.ESCROW;
            }
        }

        boolean positive = amount == null || amount.compareTo(BigDecimal.ZERO) >= 0;
        String type = classify(head, desc, positive);
        return new Entry(type, desc == null ? "" : desc.trim(), label(type, positive));
    }

    private static String classify(String head, String desc, boolean positive) {
        String t = head.trim().toUpperCase();
        String all = (head + " " + (desc == null ? "" : desc)).toUpperCase();

        if (t.equals(TxType.TOPUP) || t.equals("RECHARGE"))
            return TxType.TOPUP;
        if (t.equals(TxType.REPAYMENT) || t.equals(TxType.REPAYMENT_RECEIVED))
            return t;
        if (t.equals(TxType.ESCROW))
            return TxType.ESCROW;
        if (t.equals(TxType.REFUND) || t.equals("REFUND"))
            return TxType.REFUND;
        if (t.equals(TxType.PAYMENT))
            return TxType.PAYMENT;

        // 舊資料只剩中文標題可以判斷
        if (all.contains("補款"))
            return positive ? TxType.REPAYMENT_RECEIVED : TxType.REPAYMENT;
        if (head.contains("退款") || head.contains("REFUND"))
            return TxType.REFUND;
        if (head.contains("扣款") || head.contains("消費"))
            return TxType.PAYMENT;
        return positive ? TxType.TOPUP : TxType.PAYMENT;
    }

    private static String label(String type, boolean positive) {
        return switch (type) {
            case TxType.TOPUP -> "帳戶儲值";
            case TxType.PAYMENT -> "消費扣款";
            case TxType.ESCROW -> "訂單託管";
            case TxType.REFUND -> "退款";
            case TxType.REPAYMENT -> "主動補款";
            case TxType.REPAYMENT_RECEIVED -> "接收補款";
            default -> positive ? "其他收入" : "其他支出";
        };
    }
}
