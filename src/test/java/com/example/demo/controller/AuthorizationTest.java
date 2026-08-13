package com.example.demo.controller;

import com.example.demo.common.JwtUtils;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 授權回歸測試。
 *
 * 這支測試守住的是實際發生過、且已在執行中的系統上重現的漏洞：
 *
 *   S-1 POST /api/products 未認證即可新增／竄改任何品牌的商品（實測售價 65.00 → 0.01）
 *   S-2 任一顧客可修改他人錢包餘額（實測把他人餘額改成 99999）
 *   S-3 任一顧客可讀取他人個資（姓名、手機、餘額）
 *   S-4 debug 端點未經認證對外開放（會列出他人手機號碼）
 *
 * 共同成因是「身分取自用戶端提供的值」。其中 S-2／S-3 的舊寫法是
 * {@code if (authUserId != null && !authUserId.equals(userId))}，
 * 攻擊者只要不帶 authUserId 參數，整段檢查就被跳過——
 * 因此下面特別包含「刻意偽造參數」的案例。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private com.example.demo.repository.GroupOrderRepository groupOrderRepository;

    @Autowired
    private com.example.demo.repository.StoreRepository storeRepository;

    @Autowired
    private com.example.demo.repository.ProductTemplateRepository productTemplateRepository;

    @Autowired
    private com.example.demo.service.PricingService pricingService;

    @Autowired
    private com.example.demo.repository.UserFavoriteRepository userFavoriteRepository;

    @Autowired
    private com.example.demo.repository.UserCouponRepository userCouponRepository;

    @Autowired
    private com.example.demo.repository.BrandRepository brandRepository;

    private Long attackerId;
    private Long victimId;
    private String attackerToken;
    private Long victimOrderId;

    @BeforeEach
    void setUp() {
        attackerId = newCustomer("攻擊者").getId();
        User victim = newCustomer("受害者");
        victimId = victim.getId();
        attackerToken = jwtUtils.generateToken(attackerId, "CUSTOMER", "0900000001");
        victimOrderId = newVictimOrder(victim);
    }

    @AfterEach
    void tearDown() {
        if (victimOrderId != null) {
            groupOrderRepository.deleteById(victimOrderId);
        }
        userRepository.deleteById(attackerId);
        userRepository.deleteById(victimId);
    }

    /** 建一張屬於受害者的訂單，供 S-6 的擁有權測試使用；沒有門市資料時回 null 讓測試跳過 */
    private Long newVictimOrder(User victim) {
        return storeRepository.findAll().stream().findFirst().map(store -> {
            com.example.demo.entity.GroupOrder o = new com.example.demo.entity.GroupOrder();
            o.setInitiator(victim);
            o.setStore(store);
            o.setType("SOLO");
            o.setStatus("SUBMITTED");
            o.setTotalAmount(new BigDecimal("100"));
            return groupOrderRepository.save(o).getId();
        }).orElse(null);
    }

    private User newCustomer(String name) {
        User u = new User();
        u.setName(name);
        u.setPhone("09" + String.format("%08d", System.nanoTime() % 100000000L));
        u.setRole("CUSTOMER");
        u.setBalance(BigDecimal.ZERO);
        return userRepository.save(u);
    }

    private String bearer() {
        return "Bearer " + attackerToken;
    }

    // ── S-1 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("S-1：未認證不得寫入商品")
    void anonymousCannotWriteProducts() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"駭客注入\",\"basePrice\":0.01,\"brand\":{\"id\":1}}"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s >= 200 && s < 300) {
                        throw new AssertionError("未認證的商品寫入竟然成功了，HTTP " + s);
                    }
                });
    }

    @Test
    @DisplayName("S-1：顧客身分也不得寫入商品（僅限品牌端）")
    void customerCannotWriteProducts() throws Exception {
        mockMvc.perform(post("/api/products")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"駭客注入\",\"basePrice\":0.01,\"brand\":{\"id\":1}}"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s >= 200 && s < 300) {
                        throw new AssertionError("顧客竟然可以新增商品，HTTP " + s);
                    }
                });
    }

    // ── S-2 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("S-2：不得對他人錢包儲值")
    void cannotRechargeAnotherUsersWallet() throws Exception {
        mockMvc.perform(post("/api/users/" + victimId + "/recharge")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":99999}"))
                .andExpect(status().isForbidden());

        BigDecimal balance = userRepository.findById(victimId).orElseThrow().getBalance();
        if (balance.compareTo(BigDecimal.ZERO) != 0) {
            throw new AssertionError("他人餘額被異動了：" + balance);
        }
    }

    @Test
    @DisplayName("S-2：偽造 authUserId 參數不得繞過檢查")
    void forgedAuthUserIdParamCannotBypass() throws Exception {
        // 舊版檢查是 if (authUserId != null && !authUserId.equals(userId))，
        // 帶上與路徑相同的 authUserId 就能「通過」，這裡確認新寫法不吃這一套。
        mockMvc.perform(post("/api/users/" + victimId + "/recharge")
                        .param("authUserId", String.valueOf(victimId))
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":99999}"))
                .andExpect(status().isForbidden());
    }

    // ── S-3 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("S-3：不得讀取他人個資")
    void cannotReadAnotherUsersProfile() throws Exception {
        mockMvc.perform(get("/api/users/" + victimId).header("Authorization", bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/" + victimId + "/address").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/" + victimId + "/store-credit-records").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("本人存取自己的資料必須照常運作（避免修過頭）")
    void ownerCanStillAccessOwnData() throws Exception {
        mockMvc.perform(get("/api/users/" + attackerId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(attackerId));

        mockMvc.perform(get("/api/users/" + attackerId + "/address").header("Authorization", bearer()))
                .andExpect(status().isOk());
    }

    // ── S-4 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("S-4：debug 端點必須已移除")
    void debugEndpointsAreGone() throws Exception {
        mockMvc.perform(get("/api/auth/debug/social-logins"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/debug-exception").param("userId", String.valueOf(victimId)))
                .andExpect(status().isNotFound());
    }

    // ── S-6：OrderController 的擁有權檢查 ─────────────────────────

    @Test
    @DisplayName("S-6：不得讀取他人的訂單列表")
    void cannotReadAnotherUsersOrderLists() throws Exception {
        mockMvc.perform(get("/api/orders/user/" + victimId + "/cards")
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orders/user/" + victimId + "/active")
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orders/user/" + victimId + "/recent-cards")
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("S-6：GET /api/orders?userId= 不得吐出他人訂單")
    void ordersQueryParamCannotTargetAnotherUser() throws Exception {
        // 舊寫法直接把查詢參數當身分用；現在一律以 token 為準，
        // 所以這裡即使指名受害者，回來的也只能是攻擊者自己的（空）清單。
        mockMvc.perform(get("/api/orders").param("userId", String.valueOf(victimId))
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    @DisplayName("S-6：不帶 userId 參數不得竄改他人訂單狀態")
    void cannotTamperAnotherUsersOrderStatus() throws Exception {
        Long orderId = victimOrderId;
        if (orderId == null) return; // 沒有可用的門市資料就跳過

        // 舊寫法是 if (userId != null) 檢查 else 直接放行，
        // 因此「不帶參數」正是繞過路徑，這裡必須被擋下。
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/orders/" + orderId + "/status")
                        .param("status", "COMPLETED")
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden());

        String actual = groupOrderRepository.findById(orderId).orElseThrow().getStatus();
        if (!"SUBMITTED".equals(actual)) {
            throw new AssertionError("他人訂單狀態被竄改了：" + actual);
        }
    }

    @Test
    @DisplayName("S-6：不得讀取他人訂單的品項")
    void cannotReadAnotherUsersOrderItems() throws Exception {
        if (victimOrderId == null) return;
        mockMvc.perform(get("/api/orders/" + victimOrderId + "/items")
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("S-6：門市訂單傾印端點必須已移除")
    void storeOrderDumpEndpointIsGone() throws Exception {
        // GET /api/orders/store/{id} 會回傳該門市全部訂單的 entity，
        // 內含其他顧客的外送地址與揪團 shareToken，且掛在 CUSTOMER-only 路徑下。
        mockMvc.perform(get("/api/orders/store/1").header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    // ── S-7：POST /api/orders/checkout ─────────────────────────────

    /**
     * 舊寫法是 {@code orderService.createOrder(request.getUserId(), ...)}——
     * 建單與扣款都用用戶端送來的 userId。實測攻擊者（餘額 0）把 userId 換成受害者，
     * 訂單建立成功且受害者餘額 19255 → 19220，攻擊者一毛沒付。
     */
    @Test
    @DisplayName("S-7：不得用他人 userId 結帳（拿別人的錢包付自己的訂單）")
    @org.springframework.transaction.annotation.Transactional
    void cannotCheckoutOnBehalfOfAnotherUser() throws Exception {
        var fixture = checkoutFixture();
        if (fixture == null) return;

        User victim = userRepository.findById(victimId).orElseThrow();
        victim.setBalance(new BigDecimal("1000"));
        userRepository.save(victim);

        mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(victimId, fixture.storeId(), fixture.productId(), 1)));

        BigDecimal after = userRepository.findById(victimId).orElseThrow().getBalance();
        if (after.compareTo(new BigDecimal("1000")) != 0) {
            throw new AssertionError("他人錢包被拿去付款了：1000 → " + after);
        }
    }

    /**
     * 舊寫法把 {@code totalAmount} 與 {@code item.finalPrice} 直接當成成交價。
     * 實測帶 {@code finalPrice: 1}，$35 的飲料只被扣了 $1。
     */
    @Test
    @DisplayName("S-7：成交價一律以資料庫售價為準，不採信用戶端送的金額")
    @org.springframework.transaction.annotation.Transactional
    void checkoutPriceIsRecomputedServerSide() throws Exception {
        var fixture = checkoutFixture();
        if (fixture == null) return;

        User attacker = userRepository.findById(attackerId).orElseThrow();
        attacker.setBalance(new BigDecimal("1000"));
        userRepository.save(attacker);

        mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(attackerId, fixture.storeId(), fixture.productId(), 1)))
                .andExpect(status().isOk());

        BigDecimal after = userRepository.findById(attackerId).orElseThrow().getBalance();
        BigDecimal charged = new BigDecimal("1000").subtract(after);
        if (charged.compareTo(fixture.expectedPrice()) != 0) {
            throw new AssertionError("實扣金額不等於資料庫售價：應扣 " + fixture.expectedPrice()
                    + "，實扣 " + charged + "（用戶端只送了 1）");
        }
    }

    private record CheckoutFixture(Long storeId, Long productId, BigDecimal expectedPrice) {}

    /** 取一組真的存在的門市＋該品牌的飲品；沒有種子資料就回 null 讓測試跳過 */
    private CheckoutFixture checkoutFixture() {
        return storeRepository.findAll().stream().findFirst().flatMap(store ->
                productTemplateRepository.findAll().stream()
                        .filter(p -> p.getBrand() != null && store.getBrand() != null
                                && p.getBrand().getId().equals(store.getBrand().getId()))
                        .filter(p -> p.getBasePrice() != null && p.getBasePrice().compareTo(BigDecimal.ZERO) > 0)
                        .findFirst()
                        .map(p -> new CheckoutFixture(store.getId(), p.getId(),
                                pricingService.itemPrice(store, p, java.util.List.of()))))
                .orElse(null);
    }

    /** 刻意把金額欄位全部送成 1，驗證伺服器不採信 */
    private String checkoutBody(Long claimedUserId, Long storeId, Long productId, int qty) {
        return "{\"userId\":" + claimedUserId + ",\"storeId\":" + storeId
                + ",\"totalAmount\":1,\"paymentMethod\":\"WALLET\",\"deliveryType\":\"pickup\","
                + "\"items\":[{\"productId\":" + productId + ",\"userId\":" + claimedUserId
                + ",\"quantity\":" + qty + ",\"productNameSnapshot\":\"免費飲料\","
                + "\"unitPriceSnapshot\":1,\"finalPrice\":1,"
                + "\"sugarSnapshot\":\"半糖\",\"iceSnapshot\":\"正常冰\",\"sizeSnapshot\":\"大杯\"}]}";
    }

    // ── S-8：收藏店家 ──────────────────────────────────────────────

    @Test
    @DisplayName("S-8：不得讀取他人的收藏清單")
    void cannotReadAnotherUsersFavorites() throws Exception {
        mockMvc.perform(get("/api/user-favorites/user/" + victimId)
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden());
    }

    /**
     * toggle 原本吃 body 的 userId 且完全沒有擁有權檢查——
     * 實測攻擊者用受害者的 userId 呼叫，把對方收藏的店家直接取消掉。
     */
    @Test
    @DisplayName("S-8：用他人 userId 切換收藏，不得動到對方的收藏")
    @org.springframework.transaction.annotation.Transactional
    void toggleFavoriteCannotTouchAnotherUser() throws Exception {
        Long storeId = storeRepository.findAll().stream().findFirst().map(s -> s.getId()).orElse(null);
        if (storeId == null) return;

        long before = userFavoriteRepository.findByUserId(victimId).size();
        mockMvc.perform(post("/api/user-favorites/toggle")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + victimId + ",\"storeId\":" + storeId + "}"));
        long after = userFavoriteRepository.findByUserId(victimId).size();
        if (before != after) {
            throw new AssertionError("他人的收藏被改動了：" + before + " → " + after);
        }
    }

    // ── S-9：優惠券 ────────────────────────────────────────────────

    /**
     * user_coupons.id 是連續整數，而消耗優惠券的 UPDATE 原本只比對 id 與 status。
     * 實測攻擊者把受害者的 couponId 套到自己的品項上，
     * 受害者的券變成 used、折扣算在攻擊者頭上——等於偷券。
     */
    @Test
    @DisplayName("S-9：不得消耗不屬於自己的優惠券")
    @org.springframework.transaction.annotation.Transactional
    void cannotConsumeAnotherUsersCoupon() {
        var brand = brandRepository.findAll().stream().findFirst().orElse(null);
        if (brand == null) return;

        com.example.demo.entity.UserCoupon c = new com.example.demo.entity.UserCoupon();
        c.setUser(userRepository.findById(victimId).orElseThrow());
        c.setBrand(brand);
        c.setCouponType("ADMIN_GIFT");
        c.setDiscountAmount(new BigDecimal("5.00"));
        c.setStatus("unused");
        Long couponId = userCouponRepository.save(c).getId();

        int affected = userCouponRepository.markUsedIfUnused(couponId, attackerId, java.time.LocalDateTime.now());
        if (affected != 0) {
            throw new AssertionError("別人的券被消耗掉了，受影響列數=" + affected);
        }
        String status = userCouponRepository.findById(couponId).orElseThrow().getStatus();
        if (!"unused".equals(status)) {
            throw new AssertionError("券的狀態被改成了：" + status);
        }
        // 本人用同一張券必須成功，避免修過頭
        if (userCouponRepository.markUsedIfUnused(couponId, victimId, java.time.LocalDateTime.now()) != 1) {
            throw new AssertionError("本人反而用不了自己的券");
        }
    }

    // ── 輸入驗證 ───────────────────────────────────────────────────

    @Test
    @DisplayName("儲值金額為負數時必須回 400，不可變成扣款")
    void negativeRechargeIsRejected() throws Exception {
        mockMvc.perform(post("/api/users/" + attackerId + "/recharge")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":-500}"))
                .andExpect(status().isBadRequest());

        BigDecimal balance = userRepository.findById(attackerId).orElseThrow().getBalance();
        if (balance.compareTo(BigDecimal.ZERO) < 0) {
            throw new AssertionError("餘額被負數儲值扣成負的：" + balance);
        }
    }
}
