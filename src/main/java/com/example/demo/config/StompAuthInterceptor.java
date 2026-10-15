package com.example.demo.config;

import com.example.demo.common.AccountStatus;
import com.example.demo.common.JwtUtils;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

/**
 * STOMP 入站攔截器：CONNECT 認證、SUBSCRIBE 授權，並拒絕客戶端直接送出的 SEND。由 {@link WebSocketConfig} 掛在 clientInboundChannel 上。
 *
 * <p>⚠️ 在此之前完全沒有攔截器：任何人都能連上 /ws-cart 並訂閱
 *    /topic/order/{orderId}，而 orderId 是連續整數可直接列舉，
 *    等於能旁觀他人訂單狀態。
 *
 * <ul>
 *   <li>CONNECT：必須帶 Authorization: Bearer &lt;jwt&gt;。驗證簽章與到期時間後，還要確認帳號仍可使用
 *       （已刪除或不存在的帳號不得連線），才綁定為該連線的 Principal。</li>
 *   <li>SUBSCRIBE：每次都重新檢查 token 是否仍在有效期內、帳號是否仍可使用（連線可能維持很久，期間帳號可能被刪除），
 *       再依目的地授權：/topic/order/{id} 僅限該訂單的發起人或參與者；
 *       /topic/group/{token} 僅限該揪團的成員（團長，或已在團內有品項的團員）。
 *       token 本身只是分享連結，持有它的人不一定是成員，不能據此推播團內的品項異動。
 *       規則本體在 {@link StompSubscribeRules}。</li>
 *   <li>SEND：一律拒絕。SimpleBroker 會把客戶端送來的 SEND 直接轉給訂閱者，放行就能偽造推播（N5）。
 *       目前前端只訂閱、後端也沒有 @MessageMapping，沒有合法用途。</li>
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
    private final UserRepository userRepository;
    private final BrandRepository brandRepository;
    private final StoreRepository storeRepository;
    private final Clock clock;

    public StompAuthInterceptor(JwtUtils jwtUtils,
                                GroupOrderRepository groupOrderRepository,
                                OrderItemRepository orderItemRepository,
                                UserRepository userRepository,
                                BrandRepository brandRepository,
                                StoreRepository storeRepository) {
        this(jwtUtils, groupOrderRepository, orderItemRepository,
                userRepository, brandRepository, storeRepository, Clock.systemUTC());
    }

    /** 注入時鐘，讓「連線之後 token 過期」可以不靠 sleep 驗證（測試用） */
    StompAuthInterceptor(JwtUtils jwtUtils,
                         GroupOrderRepository groupOrderRepository,
                         OrderItemRepository orderItemRepository,
                         UserRepository userRepository,
                         BrandRepository brandRepository,
                         StoreRepository storeRepository,
                         Clock clock) {
        this.jwtUtils = jwtUtils;
        this.groupOrderRepository = groupOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.userRepository = userRepository;
        this.brandRepository = brandRepository;
        this.storeRepository = storeRepository;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            accessor.setUser(authenticate(accessor));
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscribe(accessor);
        } else if (StompCommand.SEND.equals(command)) {
            rejectClientSend(accessor);
        }
        return message;
    }

    /** 從 STOMP CONNECT 的原生 header 取出 JWT，驗證後確認帳號仍可使用 */
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
            Date expiration = jwtUtils.getExpirationFromToken(token);
            if (expiration == null) {
                throw new MessageDeliveryException("WebSocket token 缺少到期時間");
            }
            StompUserPrincipal principal = new StompUserPrincipal(userId, role, expiration.toInstant());
            if (!isActiveAccount(principal)) {
                log.warn("已停用或不存在的帳號嘗試建立 WebSocket 連線 - UID: {}, Role: {}", userId, role);
                throw new MessageDeliveryException("帳號已停用或不存在，無法連線");
            }
            return principal;
        } catch (MessageDeliveryException e) {
            throw e;
        } catch (Exception e) {
            log.warn("WebSocket 認證失敗: {}", e.getMessage());
            throw new MessageDeliveryException("WebSocket token 無效或已過期");
        }
    }

    /**
     * 訂閱時的授權：先確認連線憑證仍在有效期內、帳號仍可使用（N7），再交給規則本體判斷目的地。
     */
    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (!(principal instanceof StompUserPrincipal user)) {
            throw new MessageDeliveryException("尚未認證，無法訂閱");
        }
        if (!clock.instant().isBefore(user.expiresAt())) {
            log.warn("連線 {}（{}）的憑證已過期，拒絕訂閱 {}", user.userId(), user.role(), accessor.getDestination());
            throw new MessageDeliveryException("連線憑證已過期，請重新登入");
        }
        if (!isActiveAccount(user)) {
            log.warn("帳號 {}（{}）已停用或不存在，拒絕訂閱 {}", user.userId(), user.role(), accessor.getDestination());
            throw new MessageDeliveryException("帳號已停用或不存在，無法訂閱");
        }
        // 規則本體在 StompSubscribeRules（純函式，有單元測試）；這裡只負責接上資料庫查詢與例外
        String reason = StompSubscribeRules.denyReason(user.role(), user.userId(), accessor.getDestination(),
                this::isOrderParticipant, this::isGroupMember);
        if (reason != null) {
            log.warn("連線者 {}（{}）訂閱 {} 被拒絕：{}", user.userId(), user.role(), accessor.getDestination(), reason);
            throw new MessageDeliveryException(reason);
        }
    }

    /**
     * 客戶端不得直接送出 SEND（N5）。
     *
     * <p>SimpleBroker 收到客戶端的 SEND 會直接轉給該目的地的訂閱者，包括 /topic/group/{token}。
     * 若放行，攻擊者就能偽造團內事件（例如假的 GROUP_CHECKOUT），完全繞過 SUBSCRIBE 的授權。
     * 伺服器自己的推播（messagingTemplate.convertAndSend）走的是 broker 通道，不經過這裡。
     */
    private void rejectClientSend(StompHeaderAccessor accessor) {
        log.warn("拒絕客戶端直接送出的 SEND：{}", accessor.getDestination());
        throw new MessageDeliveryException("不允許客戶端直接送出 STOMP SEND 訊息");
    }

    /** 帳號目前是否仍可使用；與 HTTP 的 JwtAuthenticationFilter 共用同一條規則（{@link AccountStatus}） */
    private boolean isActiveAccount(StompUserPrincipal user) {
        return AccountStatus.isActive(user.userId(), user.role(), userRepository, brandRepository, storeRepository);
    }

    /** 訂單的發起人或參與者（揪團的團員也會看訂單狀態，不能只認發起人） */
    private boolean isOrderParticipant(long orderId, long userId) {
        boolean isInitiator = groupOrderRepository.findById(orderId)
                .map(o -> o.getInitiator() != null && Long.valueOf(userId).equals(o.getInitiator().getId()))
                .orElse(false);
        return isInitiator || orderItemRepository.existsByGroupOrderIdAndUserId(orderId, userId);
    }

    /**
     * 揪團的成員：團長，或已在團內有品項的團員。與 GroupOrderService 的 isParticipant 採同一條規則
     * （持分享連結但還沒加入的人不是成員，見 N12）。
     */
    private boolean isGroupMember(String shareToken, long userId) {
        return groupOrderRepository.findByShareToken(shareToken)
                .map(go -> (go.getInitiator() != null && Long.valueOf(userId).equals(go.getInitiator().getId()))
                        || orderItemRepository.existsByGroupOrderIdAndUserId(go.getId(), userId))
                .orElse(false);
    }

    /**
     * 綁在 STOMP 連線上的身分：userId、角色（門市與顧客的 id 會撞號，必須一併比對角色），
     * 以及 token 的到期時間（SUBSCRIBE 時據此拒絕已過期的連線）。
     */
    public record StompUserPrincipal(Long userId, String role, Instant expiresAt) implements Principal {
        @Override
        public String getName() {
            return String.valueOf(userId);
        }
    }
}
