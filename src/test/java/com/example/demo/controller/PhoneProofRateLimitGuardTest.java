package com.example.demo.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 以手機驗證 token 運作的端點都必須限流（純檔案檢查，不載入 Spring context）。
 *
 * <p>這些端點不需要登入：只要持有一張有效的手機驗證 token，就能重設、設定密碼，
 * 或綁定、建立帳號。因此與登入一樣以 IP＋手機計次。之後新增同類端點卻忘了限流，這支測試會紅。
 *
 * <p>這是文字掃描：取出每個路由的 handler 文字（從路由字串到下一個對映註解為止），
 * 確認其中有 {@code loginAttemptLimiter.check(}。
 * 測試工作目錄必須是專案根目錄（Maven 預設即是）。
 */
class PhoneProofRateLimitGuardTest {

    /**
     * 預設檢查 UserController 原始碼。可用系統屬性覆寫，讓還原驗證時能指向變異版本，
     * 確認這支測試真的會紅。
     */
    private static final Path CONTROLLER = Path.of(System.getProperty(
            "phoneProofGuard.controller", "src/main/java/com/example/demo/controller/UserController.java"));

    private static final List<String> PHONE_PROOF_ROUTES = List.of(
            "/api/auth/reset-password-firebase",
            "/api/auth/merge/set-password",
            "/api/auth/merge/complete",
            "/api/auth/merge/bind-social-phone",
            "/api/auth/social-signup");

    private static final List<String> MAPPINGS = List.of(
            "@PostMapping", "@GetMapping", "@PutMapping", "@DeleteMapping", "@PatchMapping", "@RequestMapping");

    @Test
    @DisplayName("以手機驗證 token 運作的端點都必須呼叫 loginAttemptLimiter.check（重設、設定密碼、綁定、建立帳號）")
    void phoneProofRoutesAreRateLimited() throws IOException {
        String src = Files.readString(CONTROLLER, StandardCharsets.UTF_8);
        List<String> unlimited = new ArrayList<>();
        for (String route : PHONE_PROOF_ROUTES) {
            if (!handlerText(src, route).contains("loginAttemptLimiter.check(")) {
                unlimited.add(route);
            }
        }
        assertTrue(unlimited.isEmpty(), "以下端點未限流：" + unlimited);
    }

    /** 路由 {@code route} 的 handler 文字：從路由字串出現處到下一個對映註解為止。 */
    static String handlerText(String src, String route) {
        int at = src.indexOf("\"" + route + "\"");
        assertTrue(at >= 0, "找不到路由 " + route + "，清單是否過時？");
        int end = src.length();
        for (String mapping : MAPPINGS) {
            int idx = src.indexOf(mapping, at + 1);
            if (idx >= 0 && idx < end) {
                end = idx;
            }
        }
        return src.substring(at, end);
    }
}
