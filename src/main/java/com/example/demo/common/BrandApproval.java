package com.example.demo.common;

/**
 * 品牌的審核狀態（N-28）。純函式，不依賴 Spring，有單元測試（BrandApprovalTest）。
 *
 * <ul>
 *   <li>{@link #PENDING}：自助註冊後的預設狀態。不能登入，既有權杖也不被接受。</li>
 *   <li>{@link #APPROVED}：管理員核准。可登入，也會出現在公開的品牌列表。既有品牌與示範資料都是這個狀態。</li>
 *   <li>{@link #REJECTED}：管理員退件。不能登入，也不會出現在公開列表。</li>
 * </ul>
 *
 * <p>判斷採 fail-closed：只有完全等於 APPROVED 的狀態才算已核准，空值與未知狀態一律視為未核准。
 */
public final class BrandApproval {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private BrandApproval() {
    }

    /** 是否已核准。登入、權杖驗證與公開列表都以此為準。 */
    public static boolean isApproved(String status) {
        return APPROVED.equals(status);
    }

    /** 只有待審的品牌可以被核准或退件；已處理過的品牌不再改動，避免同一筆紀錄被反覆切換。 */
    public static boolean canDecide(String status) {
        return PENDING.equals(status);
    }

    /** 登入被擋下時回給前端的說明。只在密碼正確之後才會使用，因此不會洩漏給不知道密碼的人。 */
    public static String loginBlockedMessage(String status) {
        if (PENDING.equals(status)) {
            return "帳號待審核，審核通過後即可登入";
        }
        if (REJECTED.equals(status)) {
            return "帳號未通過審核";
        }
        return "帳號目前無法使用";
    }
}
