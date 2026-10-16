package com.example.demo.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本地「只記一次」儲存（純邏輯，時間由測試自己給，結果可重現）。 */
class LocalClaimStoreTest {

    @Test
    @DisplayName("同一個 key 第一次記錄成功，第二次失敗")
    void secondClaimOfTheSameKeyFails() {
        LocalClaimStore store = new LocalClaimStore();

        assertTrue(store.claimIfAbsent("k", 1_000, 0));
        assertFalse(store.claimIfAbsent("k", 1_000, 10));
    }

    @Test
    @DisplayName("不同的 key 彼此獨立")
    void differentKeysAreIndependent() {
        LocalClaimStore store = new LocalClaimStore();

        assertTrue(store.claimIfAbsent("a", 1_000, 0));
        assertTrue(store.claimIfAbsent("b", 1_000, 0));
    }

    @Test
    @DisplayName("有效期過後同一個 key 可以再次記錄；有效期內一律拒絕")
    void claimExpiresAfterTheTtl() {
        LocalClaimStore store = new LocalClaimStore();

        assertTrue(store.claimIfAbsent("k", 1_000, 0));
        assertFalse(store.claimIfAbsent("k", 1_000, 999));
        assertTrue(store.claimIfAbsent("k", 1_000, 1_000));
    }

    @Test
    @DisplayName("過期的紀錄會被清除，不會無限成長")
    void expiredEntriesAreRemoved() {
        LocalClaimStore store = new LocalClaimStore();

        store.claimIfAbsent("old", 100, 0);
        store.claimIfAbsent("new", 100, 500);

        assertEquals(1, store.size());
    }
}
