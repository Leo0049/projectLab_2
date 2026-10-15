package com.example.demo.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * JWT 演算法白名單（純邏輯，不依賴 Spring 或 jjwt）。
 *
 * <p>本系統只簽發 HS512。jjwt 解析時會接受同一把金鑰簽的其他 HMAC 演算法（例如 HS256），
 * 因此必須在解析前先檢查 header。header 只接受與本系統簽發格式完全相同的形狀：
 * 只有 {@code alg}（必為 HS512）與可選的 {@code typ}，不含跳脫字元，也不會有重複欄位。
 */
final class JwtAlgorithm {

    private static final Pattern ALLOWED_HEADER = Pattern.compile(
            "\\{\\s*\"alg\"\\s*:\\s*\"HS512\"\\s*(,\\s*\"typ\"\\s*:\\s*\"JWT\"\\s*)?\\}");

    private JwtAlgorithm() {
    }

    /** 權杖是否為 JWS compact 格式、且 header 只宣告 HS512。簽章與到期由 jjwt 另行驗證。 */
    static boolean isAllowed(String token) {
        if (token == null) {
            return false;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return false;
        }
        String header;
        try {
            header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return ALLOWED_HEADER.matcher(header).matches();
    }
}
