package com.example.demo.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** prod profile 下使用開發預設 JWT_SECRET 必須拒絕啟動 */
class JwtUtilsProdGuardTest {

    @Test
    void devProfileAcceptsDefaultSecret() {
        assertDoesNotThrow(() ->
                JwtUtils.assertProductionSecret(false, JwtUtils.DEV_DEFAULT_SECRET_PREFIX + "xxx"));
    }

    @Test
    void prodProfileRejectsDefaultSecret() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JwtUtils.assertProductionSecret(true,
                        JwtUtils.DEV_DEFAULT_SECRET_PREFIX + "DoNotUseInProductionMustBeAtLeast64Chars!"));
        assertTrue(ex.getMessage().contains("JWT_SECRET"),
                "錯誤訊息必須指出要設定 JWT_SECRET");
    }

    @Test
    void prodProfileRejectsBlankSecret() {
        assertThrows(IllegalStateException.class,
                () -> JwtUtils.assertProductionSecret(true, "  "));
    }

    @Test
    void prodProfileAcceptsRandomSecret() {
        assertDoesNotThrow(() -> JwtUtils.assertProductionSecret(true, "a".repeat(64)));
    }
}
