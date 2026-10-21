package com.example.demo.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 出站攔截器（stage 6）：切斷的工作階段不再收到任何訊息。 */
class CutSessionOutboundInterceptorTest {

    private static Message<?> messageTo(String sessionId) {
        return MessageBuilder.withPayload("{\"status\":\"READY\"}")
                .setHeader(SimpMessageHeaderAccessor.SESSION_ID_HEADER, sessionId)
                .build();
    }

    @Test
    @DisplayName("切斷的工作階段的訊息一律丟棄，其他工作階段不受影響")
    void dropsMessagesOnlyForCutSessions() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.cutSession("cut");
        CutSessionOutboundInterceptor interceptor = new CutSessionOutboundInterceptor(registry);

        assertNull(interceptor.preSend(messageTo("cut"), null));

        Message<?> live = messageTo("live");
        assertSame(live, interceptor.preSend(live, null));
    }

    @Test
    @DisplayName("沒有工作階段 id 的訊息（例如系統訊息）原樣放行")
    void messagesWithoutASessionPassThrough() {
        CutSessionOutboundInterceptor interceptor = new CutSessionOutboundInterceptor(new StompSessionRegistry());
        Message<?> untagged = MessageBuilder.withPayload("ping").build();

        assertSame(untagged, interceptor.preSend(untagged, null));
    }
}
