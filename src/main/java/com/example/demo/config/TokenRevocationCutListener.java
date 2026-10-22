package com.example.demo.config;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountsRevokedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 登出所有裝置交易提交之後，切斷該帳號既有的 STOMP 連線（stage 8）。
 *
 * <p>使用 AFTER_COMMIT：若撤銷交易回滾，連線不會被切斷。權杖版本本身已擋住新的 CONNECT 與 SUBSCRIBE，
 * 這裡處理的是已建立、仍在接收推播的工作階段。
 */
@Component
@RequiredArgsConstructor
public class TokenRevocationCutListener {

    private final StompSessionRegistry sessions;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAccountsRevoked(AccountsRevokedEvent event) {
        for (AccountRef account : event.accounts()) {
            sessions.cut(account);
        }
    }
}
