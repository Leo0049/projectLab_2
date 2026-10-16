package com.example.demo.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 權杖的 SHA-256 指紋（純邏輯，不依賴 Spring）。
 *
 * <p>儲存「已使用過的權杖」時只保存指紋，不保存權杖本身：權杖是可重放的憑證，
 * 不應該被寫進 Redis 或記錄檔。
 */
public final class TokenFingerprint {

    private TokenFingerprint() {
    }

    /** 回傳 64 個小寫十六進位字元。空白權杖拋出 IllegalArgumentException。 */
    public static String of(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("權杖不可為空");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支援 SHA-256", e);
        }
    }
}
