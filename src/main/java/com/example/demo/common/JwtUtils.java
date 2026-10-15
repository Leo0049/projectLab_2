package com.example.demo.common;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;

@Component
public class JwtUtils {

    /** HS512 依 RFC 7518 §3.2 要求金鑰 ≥ 512 bits，即 UTF-8 下至少 64 個字元 */
    private static final int MIN_SECRET_BYTES = 64;

    @Value("${jwt.secret}")
    private String secret;

    /** application.yml 內建的開發預設金鑰前綴；prod profile 下出現即拒絕啟動 */
    static final String DEV_DEFAULT_SECRET_PREFIX = "LocalDevOnlySecretForJoinDrink";

    /** 權杖中記錄簽發時帳號版本的聲明名稱（N13） */
    static final String TOKEN_VERSION_CLAIM = "tokenVersion";

    @Autowired(required = false)
    private Environment environment;

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
        // ⚠️ 生產防呆：prod profile 下若金鑰仍是內建開發預設值或空白，
        // 直接讓啟動失敗——偽造 token 的代價遠比啟動失敗高。
        boolean productionProfile = environment != null
                && Arrays.asList(environment.getActiveProfiles()).contains("prod");
        assertProductionSecret(productionProfile, secret);

        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 長度不足：目前 " + keyBytes.length + " bytes，HS512 需要至少 "
                            + MIN_SECRET_BYTES + " bytes（64 個字元）。"
                            + "請設定環境變數 JWT_SECRET 為更長的隨機字串。");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * ⚠️ 生產防呆：prod profile 下若金鑰仍是內建開發預設值或空白，
     * 直接讓啟動失敗。package-private static 以利單元測試。
     */
    static void assertProductionSecret(boolean productionProfile, String secret) {
        if (!productionProfile) {
            return;
        }
        if (secret == null || secret.isBlank()
                || secret.startsWith(DEV_DEFAULT_SECRET_PREFIX)) {
            throw new IllegalStateException(
                    "偵測到 prod profile 使用空白或內建預設的 JWT_SECRET。"
                            + "正式環境必須以環境變數 JWT_SECRET 注入至少 64 字元的隨機金鑰"
                            + "（例：openssl rand -base64 72）。");
        }
    }

    /**
     * 簽發權杖，tokenVersion 為 0。只給沒有版本概念的呼叫端使用；登入與改密碼後的路徑一律用四參數版本，
     * 否則帳號版本不為 0 時，剛簽出的權杖會被 {@code AccountStatus} 拒絕（N13）。
     */
    public String generateToken(Long userId, String role, String phoneNumber) {
        return generateToken(userId, role, phoneNumber, 0);
    }

    /**
     * 簽發權杖。tokenVersion 必須是帳號目前的版本（N13）：改密碼、刪除帳號後會遞增，
     * 之前簽發的權杖就不再符合 {@code AccountStatus}。
     */
    public String generateToken(Long userId, String role, String phoneNumber, int tokenVersion) {
        Date now = new Date();
        return Jwts.builder()
                .claim("userId", userId)
                .claim("role", role)
                .claim(TOKEN_VERSION_CLAIM, tokenVersion)
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

    /**
     * 權杖的到期時間（exp）。WebSocket 的 CONNECT 與 SUBSCRIBE 用它判斷連線憑證是否仍有效（N7）。
     * 沒有 exp 聲明時回傳 null，呼叫端應視為無效。
     */
    public Date getExpirationFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getExpiration();
    }

    /**
     * 權杖簽發時帳號的 tokenVersion（N13）。沒有此聲明的舊權杖視為 0，
     * 因此部署後既有登入不會被強制登出；只有之後改過密碼的帳號，舊權杖才會失效。
     */
    public int getTokenVersionFromToken(String token) {
        Object version = Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .get(TOKEN_VERSION_CLAIM);
        return version instanceof Number ? ((Number) version).intValue() : 0;
    }
}
