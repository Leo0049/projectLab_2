package com.example.demo.config;

import com.example.demo.common.JwtUtils;
import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * STOMP 入站攔截器的端到端行為（不載入 Spring context）。
 *
 * <p>走的是真實的 {@link ExecutorSubscribableChannel}、真實的 {@link JwtUtils}（HS512 簽章與過期檢查），
 * 以及真實的 {@link StompAuthInterceptor}；只有資料庫查詢以 mock 代替，時鐘可撥動以模擬 token 過期。
 * 被拒絕的訊息不會送達訂閱者，每個拒絕案例都會確認這一點。
 */
class StompAuthInterceptorTest {

    private static final String SECRET =
            "stomp-interceptor-test-secret-key-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final String OTHER_SECRET =
            "another-secret-that-is-long-enough-to-pass-the-length-check-0123456789";

    private JwtUtils jwt;
    private GroupOrderRepository groupOrders;
    private OrderItemRepository orderItems;
    private UserRepository users;
    private BrandRepository brands;
    private StoreRepository stores;
    private ShiftableClock clock;
    private ExecutorSubscribableChannel channel;
    private List<Message<?>> delivered;

    @BeforeEach
    void setUp() {
        jwt = jwtUtils(SECRET, 3_600_000L);
        groupOrders = mock(GroupOrderRepository.class);
        orderItems = mock(OrderItemRepository.class);
        users = mock(UserRepository.class);
        brands = mock(BrandRepository.class);
        stores = mock(StoreRepository.class);
        // 預設所有帳號都是啟用中的；需要停用或刪除的案例再個別覆寫
        when(users.findById(anyLong())).thenAnswer(inv -> Optional.of(customer(inv.getArgument(0))));
        when(stores.findById(anyLong())).thenAnswer(inv -> Optional.of(store(inv.getArgument(0))));
        clock = new ShiftableClock();
        channel = new ExecutorSubscribableChannel();
        channel.addInterceptor(new StompAuthInterceptor(jwt, groupOrders, orderItems, users, brands, stores, clock));
        delivered = new ArrayList<>();
        channel.subscribe(delivered::add);
    }

    // ── CONNECT ──────────────────────────────────────────────

    @Test
    @DisplayName("以有效 JWT 連線時，連線身分綁定為 token 內的 userId 與角色")
    void connectWithValidTokenBindsTheTokenIdentity() {
        Principal principal = connect("Bearer " + jwt.generateToken(7L, "CUSTOMER", "0912345678"));

        assertNotNull(principal);
        assertEquals("7", principal.getName());
        assertEquals("CUSTOMER", ((StompAuthInterceptor.StompUserPrincipal) principal).role());
        assertEquals(1, delivered.size());
    }

    @Test
    @DisplayName("沒有 Authorization 標頭的 CONNECT 被拒絕，且訊息不會送達")
    void connectWithoutAuthorizationIsRejected() {
        assertThrows(MessageDeliveryException.class, () -> connect(null));
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("Authorization 不是 Bearer 格式（例如 Basic）被拒絕")
    void connectWithNonBearerSchemeIsRejected() {
        assertThrows(MessageDeliveryException.class, () -> connect("Basic dXNlcjpwYXNz"));
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("以其他金鑰簽發的 token（偽造）被拒絕")
    void connectWithForgedTokenIsRejected() {
        String forged = jwtUtils(OTHER_SECRET, 3_600_000L).generateToken(1L, "CUSTOMER", "0912345678");

        assertThrows(MessageDeliveryException.class, () -> connect("Bearer " + forged));
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("已過期的 token 被拒絕")
    void connectWithExpiredTokenIsRejected() {
        String expired = jwtUtils(SECRET, -60_000L).generateToken(1L, "CUSTOMER", "0912345678");

        assertThrows(MessageDeliveryException.class, () -> connect("Bearer " + expired));
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("無法解析的字串被拒絕")
    void connectWithMalformedTokenIsRejected() {
        assertThrows(MessageDeliveryException.class, () -> connect("Bearer not-a-jwt"));
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("已刪除的顧客即使持有未過期的 token，也無法連線（N7）")
    void deletedCustomerCannotConnect() {
        when(users.findById(1L)).thenReturn(Optional.of(deletedCustomer(1L)));

        assertThrows(MessageDeliveryException.class,
                () -> connect("Bearer " + jwt.generateToken(1L, "CUSTOMER", "0912345678")));
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("資料庫中已不存在的門市 token 無法連線（N7）")
    void storeMissingFromDatabaseCannotConnect() {
        when(stores.findById(7L)).thenReturn(Optional.empty());

        assertThrows(MessageDeliveryException.class,
                () -> connect("Bearer " + jwt.generateToken(7L, "STORE", "0912345678")));
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("沒有到期時間（exp）的 token 無法連線（缺期限即拒絕，N7）")
    void tokenWithoutExpiryCannotConnect() {
        String noExpiry = Jwts.builder()
                .claim("userId", 1L)
                .claim("role", "CUSTOMER")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(MessageDeliveryException.class, () -> connect("Bearer " + noExpiry));
        assertTrue(delivered.isEmpty());
    }

    // ── SUBSCRIBE ────────────────────────────────────────────

    @Test
    @DisplayName("訂單參與者（透過 order_items）可訂閱該訂單推播")
    void participantCanSubscribeToOrder() {
        when(orderItems.existsByGroupOrderIdAndUserId(10L, 1L)).thenReturn(true);
        Principal customer = connect("Bearer " + jwt.generateToken(1L, "CUSTOMER", "0912345678"));

        assertDoesNotThrow(() -> subscribe(customer, "/topic/order/10"));
        assertEquals(2, delivered.size());
    }

    @Test
    @DisplayName("發起人即使沒有任何品項，也可訂閱自己發起的訂單推播")
    void initiatorCanSubscribeWithoutItems() {
        when(groupOrders.findById(10L)).thenReturn(Optional.of(orderInitiatedBy(1L)));
        Principal initiator = connect("Bearer " + jwt.generateToken(1L, "CUSTOMER", "0912345678"));

        assertDoesNotThrow(() -> subscribe(initiator, "/topic/order/10"));
    }

    @Test
    @DisplayName("非參與者不可訂閱訂單推播")
    void outsiderCannotSubscribeToOrder() {
        Principal outsider = connect("Bearer " + jwt.generateToken(3L, "CUSTOMER", "0912345678"));

        assertThrows(MessageDeliveryException.class, () -> subscribe(outsider, "/topic/order/10"));
        assertEquals(1, delivered.size());
    }

    @Test
    @DisplayName("門市即使與訂單參與者的數字 id 相同，也不可訂閱顧客的訂單推播")
    void storeWithParticipantsNumericIdCannotSubscribeToOrder() {
        when(orderItems.existsByGroupOrderIdAndUserId(10L, 1L)).thenReturn(true);
        Principal store = connect("Bearer " + jwt.generateToken(1L, "STORE", "0912345678"));

        assertThrows(MessageDeliveryException.class, () -> subscribe(store, "/topic/order/10"));
    }

    @Test
    @DisplayName("門市只能訂閱自己的頻道")
    void storeMayOnlySubscribeToItsOwnChannel() {
        Principal store = connect("Bearer " + jwt.generateToken(7L, "STORE", "0912345678"));

        assertDoesNotThrow(() -> subscribe(store, "/topic/store/7"));
        assertThrows(MessageDeliveryException.class, () -> subscribe(store, "/topic/store/8"));
    }

    @Test
    @DisplayName("揪團團長即使還沒有任何品項，也可訂閱揪團推播")
    void groupInitiatorCanSubscribeWithoutItems() {
        when(groupOrders.findByShareToken("AbC123")).thenReturn(Optional.of(groupWithId(5L, 1L)));
        Principal initiator = connect("Bearer " + jwt.generateToken(1L, "CUSTOMER", "0912345678"));

        assertDoesNotThrow(() -> subscribe(initiator, "/topic/group/AbC123"));
        assertEquals(2, delivered.size());
    }

    @Test
    @DisplayName("已在團內有品項的團員可訂閱揪團推播")
    void groupMemberWithItemCanSubscribe() {
        when(groupOrders.findByShareToken("AbC123")).thenReturn(Optional.of(groupWithId(5L, 2L)));
        when(orderItems.existsByGroupOrderIdAndUserId(5L, 1L)).thenReturn(true);
        Principal member = connect("Bearer " + jwt.generateToken(1L, "CUSTOMER", "0912345678"));

        assertDoesNotThrow(() -> subscribe(member, "/topic/group/AbC123"));
    }

    @Test
    @DisplayName("只持有分享連結、還沒加入（沒有品項）的顧客不可訂閱揪團推播（N12）")
    void shareLinkHolderWithoutItemsCannotSubscribe() {
        when(groupOrders.findByShareToken("AbC123")).thenReturn(Optional.of(groupWithId(5L, 2L)));
        Principal outsider = connect("Bearer " + jwt.generateToken(3L, "CUSTOMER", "0912345678"));

        assertThrows(MessageDeliveryException.class, () -> subscribe(outsider, "/topic/group/AbC123"));
        assertEquals(1, delivered.size());
    }

    @Test
    @DisplayName("不存在的揪團 token 一律拒絕")
    void unknownGroupTokenIsDenied() {
        Principal customer = connect("Bearer " + jwt.generateToken(1L, "CUSTOMER", "0912345678"));

        assertThrows(MessageDeliveryException.class, () -> subscribe(customer, "/topic/group/NoSuchTok"));
    }

    @Test
    @DisplayName("揪團推播不開放門市，即使門市 id 與團長相同")
    void storeCannotSubscribeToGroup() {
        when(groupOrders.findByShareToken("AbC123")).thenReturn(Optional.of(groupWithId(5L, 7L)));
        Principal store = connect("Bearer " + jwt.generateToken(7L, "STORE", "0912345678"));

        assertThrows(MessageDeliveryException.class, () -> subscribe(store, "/topic/group/AbC123"));
    }

    @Test
    @DisplayName("沒有經過 CONNECT 認證的 SUBSCRIBE 被拒絕")
    void subscribeWithoutConnectIsRejected() {
        assertThrows(MessageDeliveryException.class, () -> subscribe(null, "/topic/order/10"));
        assertEquals(0, delivered.size());
    }

    @Test
    @DisplayName("連線後帳號被刪除，之後的 SUBSCRIBE 一律拒絕（N7）")
    void accountDeletedAfterConnectCannotSubscribe() {
        when(orderItems.existsByGroupOrderIdAndUserId(10L, 1L)).thenReturn(true);
        Principal customer = connect("Bearer " + jwt.generateToken(1L, "CUSTOMER", "0912345678"));
        when(users.findById(1L)).thenReturn(Optional.of(deletedCustomer(1L)));

        assertThrows(MessageDeliveryException.class, () -> subscribe(customer, "/topic/order/10"));
        assertEquals(1, delivered.size());
    }

    @Test
    @DisplayName("連線後 token 超過到期時間，之後的 SUBSCRIBE 一律拒絕（N7）")
    void tokenExpiringAfterConnectCannotSubscribe() {
        when(orderItems.existsByGroupOrderIdAndUserId(10L, 1L)).thenReturn(true);
        Principal customer = connect("Bearer " + jwt.generateToken(1L, "CUSTOMER", "0912345678"));

        clock.advance(Duration.ofHours(2)); // setUp 中 token 的有效期為 1 小時

        assertThrows(MessageDeliveryException.class, () -> subscribe(customer, "/topic/order/10"));
        assertEquals(1, delivered.size());
    }

    // ── 帳號版本（N13）──────────────────────────────────────

    @Test
    @DisplayName("改密碼之前簽發的 token 無法連線（N13）")
    void connectWithTokenIssuedBeforePasswordChangeIsRejected() {
        when(users.findById(7L)).thenReturn(Optional.of(customerWithTokenVersion(7L, 1)));

        assertThrows(MessageDeliveryException.class,
                () -> connect("Bearer " + jwt.generateToken(7L, "CUSTOMER", "0912345678")));
        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("改密碼之後重新登入取得的 token 可以連線（N13）")
    void connectWithTokenIssuedAfterPasswordChangeSucceeds() {
        when(users.findById(7L)).thenReturn(Optional.of(customerWithTokenVersion(7L, 1)));

        assertNotNull(connect("Bearer " + jwt.generateToken(7L, "CUSTOMER", "0912345678", 1)));
        assertEquals(1, delivered.size());
    }

    @Test
    @DisplayName("連線後才改密碼，之後的 SUBSCRIBE 一律拒絕（N13）")
    void passwordChangedAfterConnectCannotSubscribe() {
        when(orderItems.existsByGroupOrderIdAndUserId(10L, 1L)).thenReturn(true);
        Principal customer = connect("Bearer " + jwt.generateToken(1L, "CUSTOMER", "0912345678"));
        when(users.findById(1L)).thenReturn(Optional.of(customerWithTokenVersion(1L, 1)));

        assertThrows(MessageDeliveryException.class, () -> subscribe(customer, "/topic/order/10"));
        assertEquals(1, delivered.size());
    }

    // ── 客戶端 SEND（N5）─────────────────────────────────────

    @Test
    @DisplayName("客戶端直接 SEND 到推播頻道一律拒絕，不會送達訂閱者（N5）")
    void clientSendToTopicIsRejected() {
        Principal customer = connect("Bearer " + jwt.generateToken(1L, "CUSTOMER", "0912345678"));

        assertThrows(MessageDeliveryException.class, () -> send(customer, "/topic/group/AbC123"));
        assertThrows(MessageDeliveryException.class, () -> send(customer, "/app/anything"));
        assertEquals(1, delivered.size());
    }

    // ── 輔助方法 ─────────────────────────────────────────────

    /**
     * 送出 CONNECT 並回傳攔截器綁在連線上的 Principal。
     * 被拒絕時會拋出 MessageDeliveryException，由呼叫端的 assertThrows 接住。
     */
    private Principal connect(String authorization) {
        Message<byte[]> connectFrame = frame(StompCommand.CONNECT, accessor -> {
            if (authorization != null) {
                accessor.setNativeHeader("Authorization", authorization);
            }
        });
        channel.send(connectFrame);
        return StompHeaderAccessor.wrap(connectFrame).getUser();
    }

    /**
     * 送出 SUBSCRIBE。連線身分由呼叫端帶入，模擬 Spring 從 WebSocket session 取得的 Principal。
     */
    private void subscribe(Principal user, String destination) {
        Message<byte[]> subscribeFrame = frame(StompCommand.SUBSCRIBE, accessor -> {
            accessor.setDestination(destination);
            accessor.setUser(user);
        });
        channel.send(subscribeFrame);
    }

    /** 送出客戶端的 SEND；不論目的地為何，都應被攔截器拒絕 */
    private void send(Principal user, String destination) {
        Message<byte[]> sendFrame = frame(StompCommand.SEND, accessor -> {
            accessor.setDestination(destination);
            accessor.setUser(user);
        });
        channel.send(sendFrame);
    }

    private static Message<byte[]> frame(StompCommand command, Consumer<StompHeaderAccessor> customize) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        customize.accept(accessor);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static JwtUtils jwtUtils(String secret, long expirationMillis) {
        JwtUtils utils = new JwtUtils();
        ReflectionTestUtils.setField(utils, "secret", secret);
        ReflectionTestUtils.setField(utils, "expiration", expirationMillis);
        ReflectionTestUtils.invokeMethod(utils, "initSigningKey");
        return utils;
    }

    private static User customer(Long id) {
        User user = new User();
        user.setId(id);
        user.setIsDeleted(false);
        return user;
    }

    private static User deletedCustomer(long id) {
        User user = customer(id);
        user.setIsDeleted(true);
        return user;
    }

    private static User customerWithTokenVersion(long id, int tokenVersion) {
        User user = customer(id);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    private static Store store(Long id) {
        Store store = new Store();
        store.setId(id);
        return store;
    }

    private static GroupOrder orderInitiatedBy(long userId) {
        User initiator = new User();
        initiator.setId(userId);
        GroupOrder order = new GroupOrder();
        order.setInitiator(initiator);
        return order;
    }

    private static GroupOrder groupWithId(long id, long initiatorId) {
        GroupOrder order = orderInitiatedBy(initiatorId);
        order.setId(id);
        return order;
    }

    /** 可往後撥的時鐘：預設與系統時間相同，advance 之後模擬時間流逝 */
    private static final class ShiftableClock extends Clock {
        private Duration shift = Duration.ZERO;

        void advance(Duration by) {
            shift = shift.plus(by);
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
            return Instant.now().plus(shift);
        }
    }
}
