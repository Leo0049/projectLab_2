package com.example.demo.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

import java.time.Clock;

/**
 * 出站方向：已被切斷的工作階段（帳號停用或撤銷），以及權杖已到期的工作階段，不再收到任何訊息（stage 6、stage 26）。
 *
 * <p>SimpleBroker 送出推播時，仍會送到該工作階段既有的訂閱；訂閱不會因權杖失效而消失，
 * 所以必須在出站端丟棄。新的 CONNECT 與 SUBSCRIBE 由 {@link StompAuthInterceptor} 拒絕。
 * 判斷全部交給 {@link StompSessionRegistry#mayDeliver}；回傳 null 即代表丟棄該訊息。
 */
public class CutSessionOutboundInterceptor implements ChannelInterceptor {

    private final StompSessionRegistry sessions;
    private final Clock clock;

    public CutSessionOutboundInterceptor(StompSessionRegistry sessions) {
        this(sessions, Clock.systemUTC());
    }

    /** 注入時鐘，讓「送出之後 token 到期」可以不靠 sleep 驗證（測試用） */
    CutSessionOutboundInterceptor(StompSessionRegistry sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        return sessions.mayDeliver(sessionId, clock.instant()) ? message : null;
    }
}
