package com.example.demo.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手機驗證權杖的「只能使用一次」規則（純檔案檢查，不載入 Spring context）。
 *
 * <p>記錄（claimOnce）只能出現在 verifyFirebasePhone：它是所有會動到帳號的手機驗證端點共用的入口。
 * verifyOnly 是前端註冊頁的預先檢查，必須不消耗權杖，否則預先檢查之後的真正請求會被擋下。
 *
 * <p>之後新增手機驗證端點時，只要它走 verifyFirebasePhone 就自動受限；若有人把記錄移走或加進 verifyOnly，這支測試會紅。
 * 測試工作目錄必須是專案根目錄（Maven 預設即是）。
 */
class PhoneTokenSingleUseGuardTest {

    /** 預設檢查 AuthService 原始碼；可用系統屬性覆寫，讓還原驗證時能指向變異版本。 */
    private static final Path SERVICE = Path.of(System.getProperty(
            "phoneTokenGuard.service", "src/main/java/com/example/demo/service/AuthService.java"));

    private static final String CLAIM = "firebaseTokenReplayGuard.claimOnce(";

    @Test
    @DisplayName("verifyFirebasePhone 必須記錄權杖（只能使用一次）")
    void phoneVerificationConsumesTheToken() throws IOException {
        String body = methodText(read(), "private String verifyFirebasePhone(", "private String resolveUid(");
        assertTrue(body.contains(CLAIM), "verifyFirebasePhone 未呼叫 claimOnce，手機驗證權杖可被重放");
    }

    @Test
    @DisplayName("預先檢查 verifyOnly 不得記錄權杖")
    void preCheckDoesNotConsumeTheToken() throws IOException {
        String body = methodText(read(), "public void verifyOnly(", "private void bindSocialProvider(");
        assertFalse(body.contains(CLAIM), "verifyOnly 不應消耗權杖，否則註冊頁的預先檢查會讓真正的請求失效");
    }

    private static String read() throws IOException {
        return Files.readString(SERVICE, StandardCharsets.UTF_8);
    }

    /** 方法文字：從 startMarker 出現處到 endMarker 為止（不含 endMarker）。 */
    static String methodText(String src, String startMarker, String endMarker) {
        int start = src.indexOf(startMarker);
        assertTrue(start >= 0, "找不到 " + startMarker + "，方法是否改名？");
        int end = src.indexOf(endMarker, start + startMarker.length());
        assertTrue(end > start, "找不到 " + endMarker + "，方法順序是否改變？");
        return src.substring(start, end);
    }
}
