package com.example.demo.config;

import com.example.demo.common.AccountRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工作階段登錄表（stage 6，純邏輯）：切斷只影響指定的帳號；權杖到期後不可送達（stage 26）。 */
class StompSessionRegistryTest {

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    @DisplayName("切斷某帳號只影響該帳號的工作階段")
    void cutTouchesOnlyTheNamedAccount() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("s1", new AccountRef("STORE", 31L), EXPIRES_AT);
        registry.register("s2", new AccountRef("STORE", 32L), EXPIRES_AT);

        registry.cut(new AccountRef("STORE", 31L));

        assertTrue(registry.isCut("s1"));
        assertFalse(registry.isCut("s2"));
    }

    @Test
    @DisplayName("顧客與門市的 id 會撞號：切斷門市 7 不影響顧客 7（N13）")
    void sameIdAcrossRolesIsNotCut() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("customer", new AccountRef("CUSTOMER", 7L), EXPIRES_AT);
        registry.register("store", new AccountRef("STORE", 7L), EXPIRES_AT);

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
        registry.register("s1", new AccountRef("BRAND", 5L), EXPIRES_AT);
        registry.cutSession("s1");
        assertTrue(registry.isCut("s1"));

        registry.unregister("s1");
        assertFalse(registry.isCut("s1"));

        registry.register(null, new AccountRef("BRAND", 5L), EXPIRES_AT);
        registry.register("s2", null, EXPIRES_AT);
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

    @Test
    @DisplayName("權杖到期的那一刻起不可送達（與 SUBSCRIBE 同一條規則），到期前仍可送達（stage 26）")
    void expiredSessionIsNotDeliverable() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("s1", new AccountRef("CUSTOMER", 7L), EXPIRES_AT);

        assertTrue(registry.mayDeliver("s1", EXPIRES_AT.minusSeconds(1)));
        assertFalse(registry.mayDeliver("s1", EXPIRES_AT), "到期時間當下即視為到期");
        assertFalse(registry.mayDeliver("s1", EXPIRES_AT.plusSeconds(60)));
    }

    @Test
    @DisplayName("某工作階段到期只影響它自己，同一帳號的其他工作階段（仍有效）照常送達（stage 26）")
    void expiryAffectsOnlyTheExpiredSession() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("expired", new AccountRef("CUSTOMER", 7L), EXPIRES_AT);
        registry.register("laptop", new AccountRef("CUSTOMER", 7L), EXPIRES_AT.plusSeconds(3600));

        assertFalse(registry.mayDeliver("expired", EXPIRES_AT));
        assertTrue(registry.mayDeliver("laptop", EXPIRES_AT));
    }

    @Test
    @DisplayName("登錄時沒有到期時間：無法執行到期規則，保守地直接切斷（fail-closed，stage 26）")
    void registrationWithoutExpiryFailsClosed() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("s2", new AccountRef("STORE", 31L), null);

        assertTrue(registry.isCut("s2"));
        assertFalse(registry.mayDeliver("s2", EXPIRES_AT.minusSeconds(3600)));
    }

    @Test
    @DisplayName("沒有工作階段 id 的訊息，與尚未登錄的工作階段，維持原行為可送達（CONNECTED 訊框可能早於登錄送出）")
    void unregisteredAndUntaggedMessagesStillDeliver() {
        StompSessionRegistry registry = new StompSessionRegistry();

        assertTrue(registry.mayDeliver(null, EXPIRES_AT));
        assertTrue(registry.mayDeliver("never-registered", EXPIRES_AT));
    }
}
