package com.example.demo.common;

import java.util.List;

/**
 * 帳號被停用（stage 6）時發出的事件：列出受影響的帳號，供 WebSocket 切斷既有連線。
 *
 * <p>事件在交易中發出，監聽器以 {@code AFTER_COMMIT} 執行，所以只有停用真的寫入資料庫後才會切斷連線。
 */
public record AccountsSuspendedEvent(List<AccountRef> accounts) {

    public AccountsSuspendedEvent {
        accounts = List.copyOf(accounts);
    }
}
