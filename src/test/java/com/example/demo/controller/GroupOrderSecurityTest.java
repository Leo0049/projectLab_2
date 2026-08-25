package com.example.demo.controller;

import com.example.demo.common.JwtUtils;
import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.entity.UserCoupon;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserCouponRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.PricingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 揪團流程安全回歸測試（對應 H-1／H-2／H-3）。
 *
 * <p>守住的都是「身分或金額取自用戶端」這一型、且實際可重現的漏洞：
 *
 * <ul>
 *   <li><b>H-1</b>：addItem/updateItem 曾直接採信 body 的 finalPrice——
 *       帶 finalPrice:1 就能用 $1 買走 $35 的飲料；userId 也取自 body，
 *       可把品項掛到他人身上。</li>
 *   <li><b>H-2</b>：checkout/member-checkout 對 couponId 只 findById 就打折，
 *       可列舉盜用他人未用券。修復後必須原子消耗（WHERE owner+status）。</li>
 *   <li><b>H-3</b>：PUT /status 取不到登入身分會退回 query 參數 hostId——
 *       帶上真團長的 id 就能改別人的揪團狀態。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupOrderSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private GroupOrderRepository groupOrderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private ProductTemplateRepository productTemplateRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private PricingService pricingService;

    private User attacker;
    private User victim;
    private String attackerToken;
    private String victimToken;
    private Store store;
    private ProductTemplate product;
    private final List<Long> groupIdsToClean = new java.util.ArrayList<>();
    private final List<Long> couponIdsToClean = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        attacker = newCustomer("揪團攻擊者");
        victim = newCustomer("揪團受害者");
        attackerToken = jwtUtils.generateToken(attacker.getId(), "CUSTOMER", attacker.getPhone());
        victimToken = jwtUtils.generateToken(victim.getId(), "CUSTOMER", victim.getPhone());
        // 示範資料未植入時（理論上不發生），跳過而非誤報
        store = storeRepository.findAll().stream().findFirst().orElse(null);
        assumeTrue(store != null && store.getBrand() != null, "無門市示範資料");
        Long brandId = store.getBrand().getId();
        product = productTemplateRepository.findAll().stream()
                .filter(p -> p.getBrand() != null && brandId.equals(p.getBrand().getId()))
                .findFirst()
                .orElse(null);
        assumeTrue(product != null, "該品牌無商品示範資料");
    }

    @AfterEach
    void tearDown() {
        for (Long gid : groupIdsToClean) {
            orderItemRepository.deleteAll(orderItemRepository.findByGroupOrderId(gid));
            groupOrderRepository.deleteById(gid);
        }
        groupIdsToClean.clear();
        userCouponRepository.deleteAllById(couponIdsToClean);
        couponIdsToClean.clear();
        userRepository.deleteById(attacker.getId());
        userRepository.deleteById(victim.getId());
    }

    // ── H-1：金額與身分一律由伺服器決定 ─────────────────────────

    @Test
    @DisplayName("H-1：addItem 送偽造價格與他人 userId，金額仍由伺服器重算、歸屬仍為本人")
    void addItemIgnoresClientPriceAndUserId() throws Exception {
        GroupOrder go = newGroup(attacker);

        String body = """
                {
                  "productId": %d,
                  "qty": 2,
                  "userId": %d,
                  "unitPrice": "1",
                  "finalPrice": "1",
                  "toppingIds": [],
                  "toppingNames": [],
                  "toppingPrices": []
                }
                """.formatted(product.getId(), victim.getId());

        MvcResult result = mockMvc.perform(
                post("/api/group-orders/" + go.getShareToken() + "/items")
                        .header("Authorization", bearer(attackerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        long itemId = Long.parseLong(response.replaceAll(".*\"id\":(\\d+).*", "$1"));

        OrderItem item = orderItemRepository.findById(itemId).orElseThrow();
        // body 的 userId=victim 不採信：品項必須屬於登入者本人
        assertEquals(attacker.getId(), item.getUser().getId(),
                "品項歸屬應取自 JWT 而非 body userId");
        // body 的 finalPrice=1 不採信：金額必須等於伺服器定價 × qty
        BigDecimal expectedUnit = pricingService.itemPrice(go.getStore(), product, List.of());
        assertEquals(expectedUnit.multiply(BigDecimal.valueOf(2)).stripTrailingZeros(),
                item.getFinalPrice().stripTrailingZeros(),
                "finalPrice 必須由 PricingService 重算，不得採信 client 送的 1 元");
    }

    @Test
    @DisplayName("H-1：updateItem 無法把品項改成任意價格")
    void updateItemCannotSetArbitraryPrice() throws Exception {
        GroupOrder go = newGroup(attacker);
        OrderItem item = newItem(go, attacker, "35.00");

        String body = """
                { "finalPrice": "0.01", "unitPriceSnapshot": "0.01", "qty": 1 }
                """;
        mockMvc.perform(put("/api/group-orders/" + go.getShareToken() + "/items/" + item.getId())
                        .header("Authorization", bearer(attackerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        OrderItem reloaded = orderItemRepository.findById(item.getId()).orElseThrow();
        BigDecimal expectedUnit = pricingService.itemPrice(go.getStore(), product, List.of());
        assertNotEquals(new BigDecimal("0.01"), reloaded.getFinalPrice(),
                "client 送的 0.01 元不可被採信");
        assertEquals(expectedUnit.stripTrailingZeros(), reloaded.getFinalPrice().stripTrailingZeros(),
                "金額應由伺服器重算");
    }

    // ── H-2：整單結帳不得盜用他人優惠券 ─────────────────────────

    @Test
    @DisplayName("H-2：checkout 帶他人的 couponId 必須被拒，券保持 unused")
    void checkoutCannotUseAnotherUsersCoupon() throws Exception {
        GroupOrder go = newGroup(attacker);
        newItem(go, attacker, "35.00");

        UserCoupon victimsCoupon = newCoupon(victim);
        try {
            String body = """
                    { "paymentMethod": "CASH", "couponId": %d }
                    """.formatted(victimsCoupon.getId());

            mockMvc.perform(post("/api/group-orders/" + go.getShareToken() + "/checkout")
                            .header("Authorization", bearer(attackerToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().is4xxClientError());

            UserCoupon after = userCouponRepository.findById(victimsCoupon.getId()).orElseThrow();
            assertEquals("unused", after.getStatus(), "他人的券不可被消耗");

            GroupOrder afterGo = groupOrderRepository.findById(go.getId()).orElseThrow();
            assertEquals("OPEN", afterGo.getStatus(), "盜券失敗時訂單不應進入 SUBMITTED");
        } finally {
            couponIdsToClean.add(victimsCoupon.getId());
        }

        // 對照組：券還給本人後即可正常使用
        UserCoupon own = userCouponRepository.findById(victimsCoupon.getId()).orElseThrow();
        own.setUser(attacker);
        userCouponRepository.saveAndFlush(own);

        mockMvc.perform(post("/api/group-orders/" + go.getShareToken() + "/checkout")
                        .header("Authorization", bearer(attackerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "paymentMethod": "CASH", "couponId": %d }
                                """.formatted(own.getId())))
                .andExpect(status().isOk());

        UserCoupon used = userCouponRepository.findById(own.getId()).orElseThrow();
        assertEquals("used", used.getStatus());
    }

    // ── H-3：狀態變更只認 JWT，hostId 參數不再有效 ───────────────

    @Test
    @DisplayName("H-3：非團長帶上真團長的 hostId 也無法變更他人揪團狀態")
    void forgedHostIdCannotChangeOthersGroupStatus() throws Exception {
        GroupOrder victimsGroup = newGroup(victim);

        mockMvc.perform(put("/api/group-orders/" + victimsGroup.getShareToken() + "/status")
                        .header("Authorization", bearer(attackerToken))
                        .param("status", "LOCKED")
                        .param("hostId", String.valueOf(victim.getId()))) // ⚠️ 舊版漏洞參數
                .andExpect(status().isForbidden());

        GroupOrder reloaded = groupOrderRepository.findById(victimsGroup.getId()).orElseThrow();
        assertEquals("OPEN", reloaded.getStatus(), "偽造 hostId 不可改變他人揪團狀態");
    }

    @Test
    @DisplayName("H-3：團長本人可正常截單（LOCKED）與重開（OPEN）；亂打狀態被白名單擋下")
    void hostCanStillLockAndReopen() throws Exception {
        GroupOrder go = newGroup(attacker);
        String statusUrl = "/api/group-orders/" + go.getShareToken() + "/status";

        mockMvc.perform(put(statusUrl)
                        .header("Authorization", bearer(victimToken)) // 非團長
                        .param("status", "LOCKED"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put(statusUrl)
                        .header("Authorization", bearer(attackerToken))
                        .param("status", "LOCKED")) // 團長、不需 hostId 參數
                .andExpect(status().isOk());
        assertEquals("LOCKED", groupOrderRepository.findById(go.getId()).orElseThrow().getStatus());

        mockMvc.perform(put(statusUrl)
                        .header("Authorization", bearer(attackerToken))
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(statusUrl)
                        .header("Authorization", bearer(attackerToken))
                        .param("status", "OPEN"))
                .andExpect(status().isOk());
        assertEquals("OPEN", groupOrderRepository.findById(go.getId()).orElseThrow().getStatus());
    }

    // ── helpers ─────────────────────────────────────────────────

    private User newCustomer(String name) {
        User u = new User();
        u.setName(name);
        u.setPhone("09" + String.format("%08d", System.nanoTime() % 100000000L));
        u.setRole("CUSTOMER");
        u.setBalance(BigDecimal.ZERO);
        return userRepository.save(u);
    }

    /** 直接建 OPEN 狀態的揪團（含唯一 shareToken），並登記清理 */
    private GroupOrder newGroup(User initiator) {
        GroupOrder go = new GroupOrder();
        go.setInitiator(initiator);
        go.setStore(store);
        go.setType("GROUP");
        go.setStatus("OPEN");
        go.setTotalAmount(BigDecimal.ZERO);
        go.setAddress("");
        go.setNote("");
        go.setShareToken(UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        GroupOrder saved = groupOrderRepository.save(go);
        groupIdsToClean.add(saved.getId());
        return saved;
    }

    /** 在揪團內建一個 UNPAID 品項 */
    private OrderItem newItem(GroupOrder go, User owner, String unitPrice) {
        OrderItem item = new OrderItem();
        item.setGroupOrder(go);
        item.setUser(owner);
        item.setProduct(product);
        item.setProductNameSnapshot(product.getName());
        item.setUnitPriceSnapshot(new BigDecimal(unitPrice));
        item.setFinalPrice(new BigDecimal(unitPrice));
        item.setQty(1);
        item.setSugarSnapshot("");
        item.setIceSnapshot("");
        item.setSizeSnapshot("");
        item.setPaymentStatus("UNPAID");
        item.setPaymentType("WALLET");
        return orderItemRepository.saveAndFlush(item);
    }

    /** 建一張屬於指定使用者的品牌券（unused、未過期） */
    private UserCoupon newCoupon(User owner) {
        UserCoupon uc = new UserCoupon();
        uc.setUser(owner);
        uc.setBrand(store.getBrand());
        uc.setProduct(product);
        uc.setCouponType("SEC_TEST");
        uc.setDiscountAmount(new BigDecimal("5.00"));
        uc.setStatus("unused");
        uc.setObtainedAt(LocalDateTime.now());
        uc.setExpiredAt(LocalDateTime.now().plusDays(1));
        return userCouponRepository.saveAndFlush(uc);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
