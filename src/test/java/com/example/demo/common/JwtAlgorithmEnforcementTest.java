package com.example.demo.common;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 演算法白名單的端到端檢查：jjwt 本身會接受同一把金鑰簽的 HS256，必須由 JwtUtils 在解析前擋下。
 * 需要 jjwt，因此在 CI 的單元測試中執行；純規則見 {@link JwtAlgorithmTest}。
 */
class JwtAlgorithmEnforcementTest {

    private static final String SECRET =
            "test-secret-that-is-long-enough-for-hs512-0123456789-abcdefghijklmnopqrstuvwxyz";

    @Test
    @DisplayName("本系統簽發的 HS512 權杖仍可解析")
    void hs512TokenStillWorks() {
        JwtUtils jwt = jwtUtils();

        assertEquals(7L, jwt.getUserIdFromToken(jwt.generateToken(7L, "CUSTOMER", "0912345678")));
    }

    @Test
    @DisplayName("同一把金鑰簽的 HS256 權杖被拒絕（jjwt 原本會接受）")
    void sameKeyHs256IsRejected() {
        JwtUtils jwt = jwtUtils();
        String hs256 = Jwts.builder()
                .claim("userId", 7L)
                .claim("role", "CUSTOMER")
                .setSubject("0912345678")
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();

        assertThrows(JwtException.class, () -> jwt.getUserIdFromToken(hs256));
    }

    @Test
    @DisplayName("alg 為 none 的未簽章權杖被拒絕")
    void unsignedTokenIsRejected() {
        JwtUtils jwt = jwtUtils();
        String unsigned = Jwts.builder()
                .claim("userId", 7L)
                .claim("role", "CUSTOMER")
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .compact();

        assertThrows(JwtException.class, () -> jwt.getUserIdFromToken(unsigned));
    }

    private static JwtUtils jwtUtils() {
        JwtUtils utils = new JwtUtils();
        ReflectionTestUtils.setField(utils, "secret", SECRET);
        ReflectionTestUtils.setField(utils, "expiration", 3_600_000L);
        ReflectionTestUtils.invokeMethod(utils, "initSigningKey");
        return utils;
    }
}
