package com.example.demo.config;

import com.example.demo.config.StompAuthInterceptor.StompUserPrincipal;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

import java.security.Principal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作階段登錄（stage 6）：登錄後會再判斷一次帳號是否可用，避免停用交易早於登錄時漏切斷。
 */
class StompSessionListenerTest {

    private final StompSessionRegistry registry = new StompSessionRegistry();
    private final UserRepository users = mock(UserRepository.class);
    private final BrandRepository brands = mock(BrandRepository.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final StompSessionListener listener =
            new StompSessionListener(registry, users, brands, stores);

    private static Message<byte[]> connectMessage(String sessionId) {
        return MessageBuilder.withPayload(new byte[0])
                .setHeader(SimpMessageHeaderAccessor.SESSION_ID_HEADER, sessionId)
                .build();
    }

    private static StompUserPrincipal principal(Long id, String role) {
        return new StompUserPrincipal(id, role, 0, Instant.now().plusSeconds(3600));
    }

    @Test
    @DisplayName("可使用的帳號登錄工作階段，且不被切斷")
    void activeAccountIsRegisteredAndNotCut() {
        User customer = new User();
        customer.setIsDeleted(false);
        customer.setRole("CUSTOMER");
        when(users.findById(1L)).thenReturn(Optional.of(customer));

        listener.onConnected(new SessionConnectedEvent(this, connectMessage("s1"), principal(1L, "CUSTOMER")));

        assertFalse(registry.isCut("s1"));
    }

    @Test
    @DisplayName("登錄時帳號已被停用（停用交易早於登錄）：登錄後立即切斷（stage 6）")
    void suspendedAtRegistrationIsCutImmediately() {
        Store suspended = new Store();
        suspended.setRole("STORE");
        suspended.setSuspended(true);
        when(stores.findById(20L)).thenReturn(Optional.of(suspended));

        listener.onConnected(new SessionConnectedEvent(this, connectMessage("s2"), principal(20L, "STORE")));

        assertTrue(registry.isCut("s2"));
    }

    @Test
    @DisplayName("未經認證的連線直接切斷")
    void unauthenticatedSessionIsCut() {
        Principal anonymous = () -> "anonymous";

        listener.onConnected(new SessionConnectedEvent(this, connectMessage("s3"), anonymous));

        assertTrue(registry.isCut("s3"));
    }
}
