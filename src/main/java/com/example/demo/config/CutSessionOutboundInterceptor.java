package com.example.demo.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

/**
 * 出站方向：已被切斷的工作階段（帳號停用）不再收到任何訊息（stage 6）。
 *
 * <p>SimpleBroker 送出推播時，仍會送到該工作階段既有的訂閱；訂閱不會因權杖失效而消失，
 * 所以必須在出站端丟棄。新的 CONNECT 與 SUBSCRIBE 由 {@link StompAuthInterceptor} 拒絕。
 * 回傳 null 即代表丟棄該訊息。
 */
public class CutSessionOutboundInterceptor implements ChannelInterceptor {

    private final StompSessionRegistry sessions;

    public CutSessionOutboundInterceptor(StompSessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        return sessions.isCut(sessionId) ? null : message;
    }
}
