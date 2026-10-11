package com.example.demo.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 示範資料的 prod 防呆（純邏輯，不載入 Spring context）。
 *
 * <p>守住的是本輪 C-4：demo 帳號密碼固定為 demo1234，正式環境就算有人把
 * {@code DEMO_DATA_ENABLED} 設成 true 也不能植入。
 */
class DemoDataSeederProdGuardTest {

    @Test
    @DisplayName("prod profile 下一律不植入，即使開關被打開")
    void productionProfileNeverSeedsDemoAccounts() {
        assertFalse(DemoDataSeeder.shouldSeed(true, true));
        assertFalse(DemoDataSeeder.shouldSeed(true, false));
    }

    @Test
    @DisplayName("非 prod 時才看開關")
    void localProfileFollowsTheFlag() {
        assertTrue(DemoDataSeeder.shouldSeed(false, true));
        assertFalse(DemoDataSeeder.shouldSeed(false, false));
    }
}
