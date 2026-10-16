package com.example.demo.common;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 權杖中的帳號版本聲明（N13）。需要 jjwt 解析與簽章，因此在 CI 的單元測試中執行。
 */
class JwtTokenVersionTest {

    private static final String SECRET =
            "test-secret-that-is-long-enough-for-hs512-0123456789-abcdefghijklmnopqrstuvwxyz";

    @Test
    @DisplayName("權杖帶著簽發時的帳號版本，解析後一致（N13）")
    void tokenCarriesTheVersionItWasIssuedUnder() {
        JwtUtils jwt = jwtUtils();

        assertEquals(3, jwt.getTokenVersionFromToken(jwt.generateToken(1L, "CUSTOMER", "0912345678", 3)));
    }

    @Test
    @DisplayName("沒有版本參數的簽發等同版本 0")
    void threeArgumentTokenIsVersionZero() {
        JwtUtils jwt = jwtUtils();

        assertEquals(0, jwt.getTokenVersionFromToken(jwt.generateToken(1L, "CUSTOMER", "0912345678")));
    }

    @Test
    @DisplayName("部署前簽發、沒有版本聲明的舊權杖視為版本 0，既有登入不被強制登出（N13）")
    void legacyTokenWithoutTheClaimIsVersionZero() {
        JwtUtils jwt = jwtUtils();
        String legacy = Jwts.builder()
                .claim("userId", 1L)
                .claim("role", "CUSTOMER")
                .setSubject("0912345678")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertEquals(0, jwt.getTokenVersionFromToken(legacy));
    }

    private static JwtUtils jwtUtils() {
        JwtUtils utils = new JwtUtils();
        ReflectionTestUtils.setField(utils, "secret", SECRET);
        ReflectionTestUtils.setField(utils, "expiration", 3_600_000L);
        ReflectionTestUtils.invokeMethod(utils, "initSigningKey");
        return utils;
    }
}
