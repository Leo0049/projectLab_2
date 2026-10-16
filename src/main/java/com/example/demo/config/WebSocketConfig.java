package com.example.demo.config;

import com.example.demo.common.JwtUtils;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@RequiredArgsConstructor
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtils jwtUtils;
    private final GroupOrderRepository groupOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final BrandRepository brandRepository;
    private final StoreRepository storeRepository;

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
     * 把 STOMP 認證與訂閱授權掛到入站通道上。
     *
     * <p>攔截邏輯在 {@link StompAuthInterceptor}：CONNECT 必須帶有效的 JWT 且帳號仍可使用；
     * SUBSCRIBE 依角色與參與關係授權，並重新檢查 token 期限與帳號狀態；客戶端的 SEND 一律拒絕。
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthInterceptor(jwtUtils, groupOrderRepository, orderItemRepository,
                userRepository, brandRepository, storeRepository));
    }
}
