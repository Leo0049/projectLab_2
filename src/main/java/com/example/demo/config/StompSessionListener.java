package com.example.demo.config;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountStatus;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * 登錄與移除 STOMP 工作階段（stage 6）。
 *
 * <p>CONNECT 成功後，工作階段在登錄之前就可能已經遇到停用。停用的切斷事件只會切斷「已登錄」的工作階段，
 * 所以登錄後必須再判斷一次帳號是否仍可使用；否則會留下一條漏掉切斷的連線。
 */
@Component
@RequiredArgsConstructor
public class StompSessionListener {

    private final StompSessionRegistry sessions;
    private final UserRepository users;
    private final BrandRepository brands;
    private final StoreRepository stores;

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        if (sessionId == null) {
            return;
        }
        if (!(event.getUser() instanceof StompAuthInterceptor.StompUserPrincipal principal)
                || principal.userId() == null) {
            // 正常流程不會出現未經認證的連線；保守起見直接切斷
            sessions.cutSession(sessionId);
            return;
        }
        sessions.register(sessionId, new AccountRef(principal.role(), principal.userId()));
        if (!AccountStatus.isActive(principal.userId(), principal.role(), principal.tokenVersion(),
                users, brands, stores)) {
            sessions.cutSession(sessionId);
        }
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        sessions.unregister(event.getSessionId());
    }
}
