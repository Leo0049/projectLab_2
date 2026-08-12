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

    private Long attackerId;
    private Long victimId;
    private String attackerToken;

    @BeforeEach
    void setUp() {
        attackerId = newCustomer("攻擊者").getId();
        User victim = newCustomer("受害者");
        victimId = victim.getId();
        attackerToken = jwtUtils.generateToken(attackerId, "CUSTOMER", "0900000001");
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteById(attackerId);
        userRepository.deleteById(victimId);
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
