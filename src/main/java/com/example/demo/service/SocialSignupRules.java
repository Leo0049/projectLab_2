package com.example.demo.service;

/**
 * 第三方登入（社群身分）的帳號狀態決策（純函式，與 Spring 無關，有單元測試）。
 * AuthService 只負責依決策結果執行對應的讀寫，決策本身不在那裡分支。
 *
 * <p>不變量：只要手機已屬於任一帳號，就絕不另建新帳號（CREATE_NEW 只會在手機未被使用時出現）。
 */
public final class SocialSignupRules {

    public enum Action {
        /** 社群身分已綁定，且帳號已有手機：直接登入，不改動帳號 */
        LOGIN_LINKED,
        /** 社群身分已綁定，但帳號沒有手機，且此手機未被使用：補上已驗證的手機並登入（不另建帳號） */
        BIND_PHONE_AND_LOGIN,
        /** 此手機已屬於其他帳號：回 203，走整合流程，絕不另建 */
        MERGE_CONFLICT,
        /** 社群身分與手機都未被使用：建立新帳號並綁定社群身分 */
        CREATE_NEW
    }

    private SocialSignupRules() {
    }

    /**
     * @param linked            此社群身分是否已綁定某個帳號
     * @param linkedUserHasPhone 已綁定的帳號是否已有手機（linked 為 false 時忽略）
     * @param phoneTaken        此手機是否已屬於任一帳號
     */
    public static Action decide(boolean linked, boolean linkedUserHasPhone, boolean phoneTaken) {
        if (linked) {
            if (linkedUserHasPhone) {
                return Action.LOGIN_LINKED;
            }
            return phoneTaken ? Action.MERGE_CONFLICT : Action.BIND_PHONE_AND_LOGIN;
        }
        return phoneTaken ? Action.MERGE_CONFLICT : Action.CREATE_NEW;
    }
}
