package com.example.demo.service;

import com.example.demo.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * sms.mode 的 prod 防呆（純單元測試，不載入 Spring context）。
 *
 * <p>守住的是本輪 A-1：`sms.mode` 若被設為 mock，而 mock 模式下 MOCK_TOKEN 就等於
 * 「手機已驗證」——公開的重設密碼端點因此能接管任何帳號。修補前這裡兩支都會失敗。
 */
class AuthServiceSmsModeGuardTest {

    @Test
    @DisplayName("prod profile 下 sms.mode=mock 必須讓啟動失敗")
    void productionProfileRejectsMockSmsMode() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AuthService.assertSmsModeForProfile(true, "mock"));

        // 訊息要讓人知道怎麼修，而不是只有一行「啟動失敗」
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("SMS_MODE=firebase"));
    }

    @Test
    @DisplayName("prod profile 下未設定 sms.mode 也要失敗（不可回退成 mock）")
    void productionProfileRejectsBlankSmsMode() {
        assertThrows(IllegalStateException.class, () -> AuthService.assertSmsModeForProfile(true, null));
        assertThrows(IllegalStateException.class, () -> AuthService.assertSmsModeForProfile(true, "  "));
    }

    @Test
    @DisplayName("prod profile 搭配 firebase 才能啟動")
    void productionProfileAcceptsFirebaseMode() {
        assertDoesNotThrow(() -> AuthService.assertSmsModeForProfile(true, "firebase"));
        assertDoesNotThrow(() -> AuthService.assertSmsModeForProfile(true, "FIREBASE"));
    }

    @Test
    @DisplayName("非 prod（本機／CI）維持 mock 可用")
    void localProfileKeepsMockMode() {
        assertDoesNotThrow(() -> AuthService.assertSmsModeForProfile(false, "mock"));
    }

    @Test
    @DisplayName("執行期：prod profile 下即使設定被改成 mock，MOCK_TOKEN 也不得通過")
    void mockTokenIsRefusedAtRuntimeUnderProductionProfile() {
        AuthService authService = authServiceWith("mock", "prod");

        assertThrows(CustomException.class, () -> authService.verifyOnly("MOCK_TOKEN"));
    }

    @Test
    @DisplayName("執行期：非 prod 時本機 mock token 仍然可用")
    void mockTokenStillWorksOutsideProductionProfile() {
        AuthService authService = authServiceWith("mock", "local");

        assertDoesNotThrow(() -> authService.verifyOnly("MOCK_TOKEN"));
    }

    private AuthService authServiceWith(String smsMode, String... activeProfiles) {
        AuthService authService = new AuthService();
        ReflectionTestUtils.setField(authService, "smsMode", smsMode);
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(activeProfiles);
        ReflectionTestUtils.setField(authService, "environment", environment);
        return authService;
    }
}
