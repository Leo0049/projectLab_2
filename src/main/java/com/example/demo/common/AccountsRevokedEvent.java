package com.example.demo.common;

import java.util.List;

/**
 * 帳號的所有權杖被撤銷（登出所有裝置，stage 8）時發出的事件：列出受影響的帳號，供 WebSocket 切斷既有連線。
 *
 * <p>事件在交易中發出，監聽器以 {@code AFTER_COMMIT} 執行，所以只有撤銷真的寫入資料庫後才會切斷連線。
 */
public record AccountsRevokedEvent(List<AccountRef> accounts) {

    public AccountsRevokedEvent {
        accounts = List.copyOf(accounts);
    }
}
