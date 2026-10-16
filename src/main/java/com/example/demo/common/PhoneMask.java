package com.example.demo.common;

/**
 * 記錄檔用的手機遮罩（N17，純函式）。
 *
 * <p>手機號碼是個資。log 會被保留、轉送，也常被多人檢視，因此不可原文寫入。
 * 只保留末三碼，讓客服仍能分辨是哪一支號碼；要精確比對請改用 userId。
 *
 * <p>輸出只含 {@code *} 與數字：先去掉所有非數字字元，所以換行、控制字元與格式符號
 * 都不會被原樣寫進 log（也防止偽造 log 行）。
 */
public final class PhoneMask {

    /** 保留末幾碼供辨認 */
    private static final int VISIBLE_TAIL = 3;

    private PhoneMask() {
    }

    public static String mask(String phone) {
        if (phone == null) {
            return "(無)";
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() <= VISIBLE_TAIL) {
            return "***";
        }
        int hidden = digits.length() - VISIBLE_TAIL;
        return "*".repeat(hidden) + digits.substring(hidden);
    }
}
