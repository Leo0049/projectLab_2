package com.example.demo.common;

import java.util.List;

/**
 * 帳號的所有權杖被撤銷時發出的事件：列出受影響的帳號，供 WebSocket 切斷該帳號既有的推播工作階段。
 *
 * <p>撤銷來源：顧客登出所有裝置（stage 8），以及改密碼、重設密碼、刪除帳號（stage 21）。
 * 事件在交易中發出，監聽器以 {@code AFTER_COMMIT} 執行，所以只有撤銷真的寫入資料庫後才會切斷。
 */
public record AccountsRevokedEvent(List<AccountRef> accounts) {

    public AccountsRevokedEvent {
        accounts = List.copyOf(accounts);
    }

    /**
     * 單一顧客帳號的撤銷事件。顧客端的撤銷路徑都經此建立，角色字串只在這裡定義。
     */
    public static AccountsRevokedEvent forCustomer(long userId) {
        return new AccountsRevokedEvent(List.of(new AccountRef("CUSTOMER", userId)));
    }
}
