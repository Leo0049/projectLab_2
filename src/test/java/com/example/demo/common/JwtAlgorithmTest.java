package com.example.demo.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 演算法白名單（純邏輯，不需要 Spring 或 jjwt）。
 * 只接受 HS512；其他 alg、重複的 alg、以跳脫字元寫出的欄位與格式錯誤的權杖一律拒絕。
 */
class JwtAlgorithmTest {

    private static final String PAYLOAD = "e30";   // {}
    private static final String SIGNATURE = "c2ln"; // 內容不重要：白名單只看 header

    @Test
    @DisplayName("header 只宣告 HS512 的權杖通過白名單")
    void hs512IsAllowed() {
        assertTrue(JwtAlgorithm.isAllowed(token("{\"alg\":\"HS512\"}")));
    }

    @Test
    @DisplayName("header 含 typ: JWT 的 HS512 權杖也通過")
    void hs512WithTypIsAllowed() {
        assertTrue(JwtAlgorithm.isAllowed(token("{\"alg\":\"HS512\",\"typ\":\"JWT\"}")));
    }

    @Test
    @DisplayName("HS256 即使金鑰相同也拒絕（jjwt 會接受，白名單要擋下）")
    void hs256IsRejected() {
        assertFalse(JwtAlgorithm.isAllowed(token("{\"alg\":\"HS256\"}")));
    }

    @Test
    @DisplayName("none 與非對稱演算法一律拒絕")
    void noneAndAsymmetricAreRejected() {
        assertFalse(JwtAlgorithm.isAllowed(token("{\"alg\":\"none\"}")));
        assertFalse(JwtAlgorithm.isAllowed(token("{\"alg\":\"RS256\"}")));
    }

    @Test
    @DisplayName("重複的 alg 欄位拒絕，避免 header 解析器與白名單看到不同的值")
    void duplicateAlgIsRejected() {
        assertFalse(JwtAlgorithm.isAllowed(token("{\"alg\":\"HS512\",\"alg\":\"HS256\"}")));
    }

    @Test
    @DisplayName("以 Unicode 跳脫寫出的 alg 欄位拒絕")
    void escapedAlgKeyIsRejected() {
        assertFalse(JwtAlgorithm.isAllowed(token("{\"alg\":\"HS512\",\"\\u0061lg\":\"HS256\"}")));
    }

    @Test
    @DisplayName("格式錯誤的權杖一律拒絕")
    void malformedTokensAreRejected() {
        assertFalse(JwtAlgorithm.isAllowed(null));
        assertFalse(JwtAlgorithm.isAllowed(""));
        assertFalse(JwtAlgorithm.isAllowed("only.two"));
        assertFalse(JwtAlgorithm.isAllowed("a.b.c.d"));
        assertFalse(JwtAlgorithm.isAllowed("!!!.e30.c2ln"));
    }

    private static String token(String headerJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + PAYLOAD + "." + SIGNATURE;
    }
}
