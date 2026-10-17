package com.example.demo.service.order;

import com.example.demo.exception.CustomException;

/**
 * 結帳失敗的分類（純函式，不依賴 Spring）。
 *
 * <p>「系統忙碌」與「業務拒絕」必須分開回應：忙碌回 503，表示沒有建單、沒有扣款，請稍後再試；
 * 業務拒絕（餘額不足、品項無效等）回 400。原本 {@code OrderController} 把所有例外都轉成 400，
 * 前端無從分辨，忙碌時也會被當成業務拒絕（見 docs/IMPROVEMENT_ASSESSMENT.md 第 5 項）。
 */
public final class CheckoutFailure {

    /** 結帳使用者鎖忙碌時的 code，也是回應的 HTTP 狀態碼（GlobalExceptionHandler 直接轉用）。 */
    public static final String BUSY_CODE = "503";

    private CheckoutFailure() {
    }

    /**
     * 是否為系統忙碌。只看 code：明確標示 503 的 {@link CustomException} 才算。
     * 預設 code 為 500 的業務例外不算，訊息文字也不作為判斷依據。
     */
    public static boolean isBusy(Throwable e) {
        return e instanceof CustomException ce && BUSY_CODE.equals(ce.getCode());
    }
}
