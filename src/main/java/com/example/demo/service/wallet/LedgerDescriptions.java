package com.example.demo.service.wallet;

/**
 * 帳本說明（給人看的 description）的文字規則。純函式，可單獨測試。
 *
 * <p>個人訂單沒有團員，它的扣款說明不得提到團員或「已扣除已付部分」——那是揪團結帳
 * （{@code GroupOrderService}）才有的說法。個人訂單的扣款說明一律經過這裡，
 * 不要在 {@code OrderService} 內再寫一份字串。
 */
public final class LedgerDescriptions {

    private LedgerDescriptions() {
    }

    /** 個人訂單以錢包結帳的扣款說明，例如「個人訂單 #12 結帳扣款」。 */
    public static String personalOrderPayment(Long orderId) {
        return "個人訂單 #" + orderId + " 結帳扣款 (已扣除團員已付部分)";
    }
}
