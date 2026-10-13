package com.example.demo.config;

import com.example.demo.common.JwtUtils;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
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
     * <p>攔截邏輯在 {@link StompAuthInterceptor}：CONNECT 必須帶有效的 JWT，
     * SUBSCRIBE 依角色與訂單參與關係授權。
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 接線已拿掉（MUT-22）
    }
}
