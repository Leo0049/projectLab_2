package com.example.demo.config;

import com.example.demo.common.JwtUtils;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtils jwtUtils;
    private final GroupOrderRepository groupOrderRepository;
    private final OrderItemRepository orderItemRepository;

    /** 與 SecurityConfig 共用同一份白名單，避免兩邊漂移 */
    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;


    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Topic for broadcasting (one-to-many)
        config.enableSimpleBroker("/topic");
        // Prefix for messages sent from client to server (e.g. /app/hello)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // ⚠️ 這裡曾寫成 setAllowedOriginPatterns("*")，與 HTTP 端的 CORS 白名單自相矛盾，
        //    等於任何網站都能連上並訂閱推播。改為共用 app.cors.allowed-origins。
        registry.addEndpoint("/ws-cart")
                .setAllowedOriginPatterns(allowedOrigins)
                .withSockJS();
    }

    /**
     * STOMP 認證與訂閱授權。
     *
     * ⚠️ 在此之前完全沒有攔截器：任何人都能連上 /ws-cart 並訂閱
     *    /topic/order/{orderId}，而 orderId 是連續整數可直接列舉，
     *    等於能旁觀他人訂單狀態。
     *
     * - CONNECT：必須帶 Authorization: Bearer <jwt>，驗證後綁定為該連線的 Principal。
     * - SUBSCRIBE：/topic/order/{id} 僅限該訂單的發起人或參與者；
     *   /topic/group/{token} 的 token 本身即為分享憑證（知道 token 才能加入揪團），
     *   故只要求已通過認證。
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() == null) {
                    return message;
                }

                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    accessor.setUser(authenticate(accessor));
                } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    authorizeSubscribe(accessor);
                }
                return message;
            }
        });
    }

    /** 從 STOMP CONNECT 的原生 header 取出 JWT 並驗證 */
    private Principal authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessageDeliveryException("WebSocket 連線需要 Authorization: Bearer <token>");
        }
        String token = header.substring(7);
        try {
            Long userId = jwtUtils.getUserIdFromToken(token);
            if (userId == null) {
                throw new MessageDeliveryException("WebSocket token 無法解析出使用者");
            }
            String role = jwtUtils.getRoleFromToken(token);
            return new StompUserPrincipal(userId, role);
        } catch (MessageDeliveryException e) {
            throw e;
        } catch (Exception e) {
            log.warn("WebSocket 認證失敗: {}", e.getMessage());
            throw new MessageDeliveryException("WebSocket token 無效或已過期");
        }
    }

    /** 訂閱時檢查該使用者是否有權接收此 destination */
    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (!(principal instanceof StompUserPrincipal user)) {
            throw new MessageDeliveryException("尚未認證，無法訂閱");
        }
        // 規則本體在 StompSubscribeRules（純函式，有單元測試）；這裡只負責接上資料庫查詢與例外
        String reason = StompSubscribeRules.denyReason(user.role(), user.userId(), accessor.getDestination(),
                this::isOrderParticipant);
        if (reason != null) {
            log.warn("連線者 {}（{}）訂閱 {} 被拒絕：{}", user.userId(), user.role(), accessor.getDestination(), reason);
            throw new MessageDeliveryException(reason);
        }
    }

    /** 訂單的發起人或參與者（揪團的團員也會看訂單狀態，不能只認發起人） */
    private boolean isOrderParticipant(long orderId, long userId) {
        boolean isInitiator = groupOrderRepository.findById(orderId)
                .map(o -> o.getInitiator() != null && Long.valueOf(userId).equals(o.getInitiator().getId()))
                .orElse(false);
        return isInitiator || orderItemRepository.existsByGroupOrderIdAndUserId(orderId, userId);
    }

    /** 綁在 STOMP 連線上的身分：userId 與角色（門市與顧客的 id 會撞號，必須一併比對角色） */
    private record StompUserPrincipal(Long userId, String role) implements Principal {
        @Override
        public String getName() {
            return String.valueOf(userId);
        }
    }
}
