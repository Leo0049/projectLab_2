package com.example.demo.config;

import com.example.demo.common.AccountRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工作階段登錄表（stage 6，純邏輯）：切斷只影響指定的帳號。 */
class StompSessionRegistryTest {

    @Test
    @DisplayName("切斷某帳號只影響該帳號的工作階段")
    void cutTouchesOnlyTheNamedAccount() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("s1", new AccountRef("STORE", 31L));
        registry.register("s2", new AccountRef("STORE", 32L));

        registry.cut(new AccountRef("STORE", 31L));

        assertTrue(registry.isCut("s1"));
        assertFalse(registry.isCut("s2"));
    }

    @Test
    @DisplayName("顧客與門市的 id 會撞號：切斷門市 7 不影響顧客 7（N13）")
    void sameIdAcrossRolesIsNotCut() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("customer", new AccountRef("CUSTOMER", 7L));
        registry.register("store", new AccountRef("STORE", 7L));

        registry.cut(new AccountRef("STORE", 7L));

        assertFalse(registry.isCut("customer"));
        assertTrue(registry.isCut("store"));
    }

    @Test
    @DisplayName("角色比對不分大小寫（AccountRef 會統一為大寫）")
    void roleComparisonIgnoresCase() {
        assertEquals(new AccountRef("STORE", 7L), new AccountRef("store", 7L));
    }

    @Test
    @DisplayName("工作階段結束後移除，切斷旗標一併清除；null 一律忽略")
    void unregisterClearsTheSessionAndItsCutFlag() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("s1", new AccountRef("BRAND", 5L));
        registry.cutSession("s1");
        assertTrue(registry.isCut("s1"));

        registry.unregister("s1");
        assertFalse(registry.isCut("s1"));

        registry.register(null, new AccountRef("BRAND", 5L));
        registry.register("s2", null);
        registry.unregister(null);
        registry.cutSession(null);
        assertFalse(registry.isCut(null));
        assertFalse(registry.isCut("s2"));
    }

    @Test
    @DisplayName("切斷單一工作階段只影響該工作階段")
    void cutSessionAffectsOnlyThatSession() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.cutSession("s1");
        assertTrue(registry.isCut("s1"));
        assertFalse(registry.isCut("s2"));
    }
}
