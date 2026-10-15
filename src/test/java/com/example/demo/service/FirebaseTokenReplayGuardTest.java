package com.example.demo.service;

import com.example.demo.common.ClaimStore;
import com.example.demo.common.TokenFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手機驗證權杖的「只能使用一次」（不載入 Spring context；遠端以假實作代替 Redis）。 */
class FirebaseTokenReplayGuardTest {

    private static FirebaseTokenReplayGuard guardWithoutRedis() {
        return new FirebaseTokenReplayGuard(new ClaimStore(null, () -> 0L, e -> { }));
    }

    @Test
    @DisplayName("同一張權杖第一次通過，之後在保留期間內一律拒絕")
    void sameTokenIsAcceptedOnlyOnce() {
        FirebaseTokenReplayGuard guard = guardWithoutRedis();

        assertTrue(guard.claimOnce("token-a"));
        assertFalse(guard.claimOnce("token-a"));
    }

    @Test
    @DisplayName("不同的權杖彼此獨立")
    void differentTokensAreIndependent() {
        FirebaseTokenReplayGuard guard = guardWithoutRedis();

        assertTrue(guard.claimOnce("token-a"));
        assertTrue(guard.claimOnce("token-b"));
    }

    @Test
    @DisplayName("寫入遠端的鍵是前綴加指紋，不含原始權杖；有效期為保留時間")
    void remoteKeyIsFingerprintNotToken() {
        String token = "eyJhbGciOiJSUzI1NiJ9.payload.signature";
        List<String> keys = new ArrayList<>();
        List<Duration> ttls = new ArrayList<>();
        FirebaseTokenReplayGuard guard = new FirebaseTokenReplayGuard(new ClaimStore((key, ttl) -> {
            keys.add(key);
            ttls.add(ttl);
            return true;
        }, () -> 0L, e -> { }));

        assertTrue(guard.claimOnce(token));

        assertEquals(List.of("fbtoken:" + TokenFingerprint.of(token)), keys);
        assertFalse(keys.get(0).contains(token));
        assertEquals(List.of(FirebaseTokenReplayGuard.RETENTION), ttls);
    }

    @Test
    @DisplayName("保留時間必須長於 Firebase ID token 的 1 小時有效期，否則過期前就可能被重放")
    void retentionOutlivesTheIdTokenLifetime() {
        assertTrue(FirebaseTokenReplayGuard.RETENTION.compareTo(Duration.ofHours(1)) > 0);
    }

    @Test
    @DisplayName("空白的權杖拒絕（呼叫端已先經 Firebase 驗證，這裡是最後一道防線）")
    void blankTokenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> guardWithoutRedis().claimOnce(" "));
    }
}
