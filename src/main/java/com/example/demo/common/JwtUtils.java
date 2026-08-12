package com.example.demo.common;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtils {

    /** HS512 依 RFC 7518 §3.2 要求金鑰 ≥ 512 bits，即 UTF-8 下至少 64 個字元 */
    private static final int MIN_SECRET_BYTES = 64;

    @Value("${jwt.secret}")
    private String secret;

    // ⚠️ 過去這裡寫死 @Value("604800000")（7天），導致 application.yml 的
    //    jwt.expiration 完全不生效。改為讀設定值，預設 24 小時。
    @Value("${jwt.expiration:86400000}")
    private long expiration;

    private SecretKey signingKey;

    /**
     * 啟動時就把金鑰建好並驗證長度。
     * 以往金鑰長度不足時不會在啟動時報錯，而是等到使用者「登入」才拋
     * WeakKeyException 並回 500，問題被延後到執行期才爆。
     */
    @PostConstruct
    void initSigningKey() {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 長度不足：目前 " + keyBytes.length + " bytes，HS512 需要至少 "
                            + MIN_SECRET_BYTES + " bytes（64 個字元）。"
                            + "請設定環境變數 JWT_SECRET 為更長的隨機字串。");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(Long userId, String role, String phoneNumber) {
        Date now = new Date();
        return Jwts.builder()
                .claim("userId", userId)
                .claim("role", role)
                .setSubject(phoneNumber)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expiration))
                .signWith(signingKey)
                .compact();
    }

    // 新增：解析 Token 拿回 userId
    public Long getUserIdFromToken(String token) {
        Object userIdObj = Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .get("userId");
        if (userIdObj instanceof Number) {
            return ((Number) userIdObj).longValue();
        }
        return null;
    }

    public String getRoleFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .get("role", String.class);
    }
}
