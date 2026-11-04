package com.example.demo.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.InterceptableChannel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接線檢查：應用程式啟動後，STOMP 入站與出站通道確實掛著對應的攔截器。
 *
 * <p>{@link StompAuthInterceptorTest} 直接測攔截器本身；這裡確認 WebSocketConfig 真的把它們掛上去。
 * 若入站的掛載被拿掉，單元測試仍會全綠，但連線就不再驗 JWT、訂閱也不再授權；
 * 出站若被拿掉，停用、撤銷與權杖到期後，既有訂閱仍會收到推播。
 */
@SpringBootTest
class WebSocketWiringTest {

    @Autowired
    @Qualifier("clientInboundChannel")
    private MessageChannel clientInboundChannel;

    @Autowired
    @Qualifier("clientOutboundChannel")
    private MessageChannel clientOutboundChannel;

    @Test
    @DisplayName("STOMP 入站通道掛有 StompAuthInterceptor（否則 CONNECT 不驗證、SUBSCRIBE 不授權）")
    void inboundChannelCarriesTheStompAuthInterceptor() {
        List<ChannelInterceptor> interceptors = ((InterceptableChannel) clientInboundChannel).getInterceptors();

        assertTrue(interceptors.stream().anyMatch(i -> i instanceof StompAuthInterceptor),
                "clientInboundChannel 上找不到 StompAuthInterceptor");
    }

    @Test
    @DisplayName("STOMP 出站通道掛有 CutSessionOutboundInterceptor（否則停用、撤銷與權杖到期後，既有訂閱仍會收到推播）")
    void outboundChannelCarriesTheCutSessionInterceptor() {
        List<ChannelInterceptor> interceptors = ((InterceptableChannel) clientOutboundChannel).getInterceptors();

        assertTrue(interceptors.stream().anyMatch(i -> i instanceof CutSessionOutboundInterceptor),
                "clientOutboundChannel 上找不到 CutSessionOutboundInterceptor");
    }
}
