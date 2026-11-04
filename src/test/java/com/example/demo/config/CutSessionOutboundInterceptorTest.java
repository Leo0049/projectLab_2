package com.example.demo.config;

import com.example.demo.common.AccountRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 出站攔截器（stage 6、stage 26）：切斷的工作階段不再收到任何訊息；權杖到期後同樣不再收到。 */
class CutSessionOutboundInterceptorTest {

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");

    private static Message<?> messageTo(String sessionId) {
        return MessageBuilder.withPayload("{\"status\":\"READY\"}")
                .setHeader(SimpMessageHeaderAccessor.SESSION_ID_HEADER, sessionId)
                .build();
    }

    /** 可手動撥動的時鐘，讓測試能跨過到期時間而不必 sleep。 */
    private static final class ManualClock extends Clock {
        private Instant now;

        ManualClock(Instant start) {
            this.now = start;
        }

        void moveTo(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
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

    @Test
    @DisplayName("權杖到期後，既有訂閱的推播一律丟棄；到期前收到的不受影響（stage 26）")
    void messagesStopOnceTheSessionTokenExpires() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("s1", new AccountRef("CUSTOMER", 7L), EXPIRES_AT);
        ManualClock clock = new ManualClock(EXPIRES_AT.minusSeconds(1));
        CutSessionOutboundInterceptor interceptor = new CutSessionOutboundInterceptor(registry, clock);

        Message<?> beforeExpiry = messageTo("s1");
        assertSame(beforeExpiry, interceptor.preSend(beforeExpiry, null));

        clock.moveTo(EXPIRES_AT);
        assertNull(interceptor.preSend(messageTo("s1"), null));
    }

    @Test
    @DisplayName("到期只影響到期的工作階段，同一帳號的其他有效工作階段不受影響（stage 26）")
    void expiryOnlyAffectsTheExpiredSession() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("expired", new AccountRef("CUSTOMER", 7L), EXPIRES_AT);
        registry.register("laptop", new AccountRef("CUSTOMER", 7L), EXPIRES_AT.plusSeconds(3600));
        ManualClock clock = new ManualClock(EXPIRES_AT);
        CutSessionOutboundInterceptor interceptor = new CutSessionOutboundInterceptor(registry, clock);

        assertNull(interceptor.preSend(messageTo("expired"), null));
        Message<?> live = messageTo("laptop");
        assertSame(live, interceptor.preSend(live, null));
    }

    @Test
    @DisplayName("經真實的出站通道：到期後訂閱者不再收到訊息，到期前已送出的仍收到（stage 26）")
    void subscriberStopsReceivingOnceTheTokenExpires() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("s1", new AccountRef("CUSTOMER", 7L), EXPIRES_AT);
        ManualClock clock = new ManualClock(EXPIRES_AT.minusSeconds(1));
        ExecutorSubscribableChannel outbound = new ExecutorSubscribableChannel();
        outbound.addInterceptor(new CutSessionOutboundInterceptor(registry, clock));
        List<Message<?>> received = new ArrayList<>();
        outbound.subscribe(received::add);

        outbound.send(messageTo("s1"));
        clock.moveTo(EXPIRES_AT);
        outbound.send(messageTo("s1"));

        assertEquals(1, received.size());
    }
}
