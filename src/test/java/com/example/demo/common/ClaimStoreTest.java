package com.example.demo.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 「只記一次」儲存（純邏輯：遠端與時鐘都由測試注入）。 */
class ClaimStoreTest {

    private static final Duration TTL = Duration.ofSeconds(1);

    @Test
    @DisplayName("沒有遠端時，同一個 key 只會被記錄一次")
    void localOnlyClaimsOnce() {
        ClaimStore store = new ClaimStore(null, () -> 0L, e -> { });

        assertTrue(store.claimIfAbsent("k", TTL));
        assertFalse(store.claimIfAbsent("k", TTL));
    }

    @Test
    @DisplayName("遠端說已記過時拒絕，即使本實例沒有紀錄（多實例共享）")
    void remoteRejectionWinsEvenWhenLocalIsEmpty() {
        ClaimStore store = new ClaimStore((key, ttl) -> false, () -> 0L, e -> { });

        assertFalse(store.claimIfAbsent("k", TTL));
    }

    @Test
    @DisplayName("遠端第一次寫入成功時接受，並以相同的 key 與有效期呼叫遠端")
    void remoteReceivesTheKeyAndTtl() {
        List<String> keys = new ArrayList<>();
        List<Duration> ttls = new ArrayList<>();
        ClaimStore store = new ClaimStore((key, ttl) -> {
            keys.add(key);
            ttls.add(ttl);
            return true;
        }, () -> 0L, e -> { });

        assertTrue(store.claimIfAbsent("k", TTL));
        assertEquals(List.of("k"), keys);
        assertEquals(List.of(TTL), ttls);
    }

    @Test
    @DisplayName("遠端拋例外（不可用）時退回本地記錄並通知一次；同一 key 第二次仍被擋下，且不再呼叫遠端")
    void remoteFailureFallsBackToLocal() {
        List<RuntimeException> failures = new ArrayList<>();
        ClaimStore store = new ClaimStore((key, ttl) -> {
            throw new IllegalStateException("redis down");
        }, () -> 0L, failures::add);

        assertTrue(store.claimIfAbsent("k", TTL));
        assertFalse(store.claimIfAbsent("k", TTL));
        assertEquals(1, failures.size());
    }

    @Test
    @DisplayName("遠端回傳 null 視為已記過（失敗時關閉）")
    void nullFromRemoteIsRejected() {
        ClaimStore store = new ClaimStore((key, ttl) -> null, () -> 0L, e -> { });

        assertFalse(store.claimIfAbsent("k", TTL));
    }

    @Test
    @DisplayName("Redis 故障期間記下的權杖，恢復後仍擋得住同一實例的重放")
    void localRecordSurvivesRemoteRecovery() {
        AtomicBoolean remoteDown = new AtomicBoolean(true);
        ClaimStore store = new ClaimStore((key, ttl) -> {
            if (remoteDown.get()) {
                throw new IllegalStateException("redis down");
            }
            return true;
        }, () -> 0L, e -> { });

        assertTrue(store.claimIfAbsent("k", TTL));   // 故障期間：本地記錄
        remoteDown.set(false);                        // 遠端恢復，但遠端沒有這筆紀錄
        assertFalse(store.claimIfAbsent("k", TTL));
    }

    @Test
    @DisplayName("有效期過後同一個 key 可以再次記錄；有效期內一律拒絕")
    void claimExpiresAfterTheTtl() {
        AtomicLong now = new AtomicLong(0);
        ClaimStore store = new ClaimStore((key, ttl) -> true, now::get, e -> { });

        assertTrue(store.claimIfAbsent("k", TTL));
        now.set(999);
        assertFalse(store.claimIfAbsent("k", TTL));
        now.set(1_000);
        assertTrue(store.claimIfAbsent("k", TTL));
    }
}
