package com.example.demo.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 權杖指紋（純邏輯）：必須是 SHA-256、穩定、不含原始權杖。 */
class TokenFingerprintTest {

    @Test
    @DisplayName("指紋是標準 SHA-256（以 \"abc\" 的已知答案驗證）")
    void matchesKnownSha256Vector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                TokenFingerprint.of("abc"));
    }

    @Test
    @DisplayName("同一張權杖得到相同指紋")
    void sameTokenSameFingerprint() {
        assertEquals(TokenFingerprint.of("a.b.c"), TokenFingerprint.of("a.b.c"));
    }

    @Test
    @DisplayName("不同權杖得到不同指紋")
    void differentTokensHaveDifferentFingerprints() {
        assertFalse(TokenFingerprint.of("a.b.c").equals(TokenFingerprint.of("a.b.d")));
    }

    @Test
    @DisplayName("指紋為 64 個小寫十六進位字元，且不含原始權杖")
    void fingerprintIsHexAndHidesTheToken() {
        String token = "eyJhbGciOiJSUzI1NiJ9.payload.signature";
        String fingerprint = TokenFingerprint.of(token);

        assertEquals(64, fingerprint.length());
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        assertFalse(fingerprint.contains(token));
    }

    @Test
    @DisplayName("空白或 null 的權杖拒絕")
    void blankTokenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TokenFingerprint.of(" "));
        assertThrows(IllegalArgumentException.class, () -> TokenFingerprint.of(null));
    }
}
