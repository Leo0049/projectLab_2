package com.example.demo.config;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountsSuspendedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 停用交易提交之後，切斷受影響帳號的既有 STOMP 連線（stage 6）。
 *
 * <p>使用 AFTER_COMMIT：若停用交易回滾，連線不會被切斷。
 */
@Component
@RequiredArgsConstructor
public class AccountSuspensionCutListener {

    private final StompSessionRegistry sessions;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAccountsSuspended(AccountsSuspendedEvent event) {
        for (AccountRef account : event.accounts()) {
            sessions.cut(account);
        }
    }
}
