package com.example.demo.config;

import com.example.demo.common.JwtUtils;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.security.Principal;

/**
 * STOMP 入站攔截器：CONNECT 認證、SUBSCRIBE 授權。由 {@link WebSocketConfig} 掛在 clientInboundChannel 上。
 *
 * <p>⚠️ 在此之前完全沒有攔截器：任何人都能連上 /ws-cart 並訂閱
 *    /topic/order/{orderId}，而 orderId 是連續整數可直接列舉，
 *    等於能旁觀他人訂單狀態。
 *
 * <ul>
 *   <li>CONNECT：必須帶 Authorization: Bearer &lt;jwt&gt;，驗證後綁定為該連線的 Principal。</li>
 *   <li>SUBSCRIBE：/topic/order/{id} 僅限該訂單的發起人或參與者；
 *       /topic/group/{token} 的 token 本身即為分享憑證（知道 token 才能加入揪團），
 *       故只要求已通過認證。規則本體在 {@link StompSubscribeRules}。</li>
 * </ul>
 *
 * <p>獨立成類別（而不是 WebSocketConfig 裡的匿名類別），是為了讓測試能以真實的通道與真實的 JWT 走一遍，
 * 不必啟動整個 Spring context。
 */
@Slf4j
public class StompAuthInterceptor implements ChannelInterceptor {

    private final JwtUtils jwtUtils;
    private final GroupOrderRepository groupOrderRepository;
    private final OrderItemRepository orderItemRepository;

    public StompAuthInterceptor(JwtUtils jwtUtils,
                                GroupOrderRepository groupOrderRepository,
                                OrderItemRepository orderItemRepository) {
        this.jwtUtils = jwtUtils;
        this.groupOrderRepository = groupOrderRepository;
        this.orderItemRepository = orderItemRepository;
    }

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
                (o, u) -> true);
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
    public record StompUserPrincipal(Long userId, String role) implements Principal {
        @Override
        public String getName() {
            return String.valueOf(userId);
        }
    }
}
