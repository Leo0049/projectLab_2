package com.example.demo.common;

import com.example.demo.exception.CustomException;

/**
 * 會被「其他人」看見的自由文字（顧客暱稱）的寫入端規則。
 *
 * <p>輸出端一律以 escapeHtml 轉義（見 CLAUDE.md「使用者可控字串」）；這裡是第二道防線：
 * 連 {@code < > " `} 與控制字元都不讓進資料庫，即使某一頁漏掉轉義，也不會把標籤寫進別人的畫面。
 *
 * <ul>
 *   <li>{@link #normalizeDisplayName}：使用者自己送來的值（註冊、改名），不合規就 400。</li>
 *   <li>{@link #sanitizeDisplayName}：第三方登入（Google／Facebook）帶來的名稱，清掉不允許的字元、
 *       截斷；空白就用 fallback——登入流程不能因為對方的顯示名稱而失敗。</li>
 * </ul>
 */
public final class SafeText {

    public static final int MAX_DISPLAY_NAME = 30;

    private SafeText() {
    }

    static boolean isForbidden(int cp) {
        return cp == '<' || cp == '>' || cp == '"' || cp == '`' || Character.isISOControl(cp);
    }

    public static String normalizeDisplayName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            throw new CustomException("400", "名稱不可為空白");
        }
        if (s.codePointCount(0, s.length()) > MAX_DISPLAY_NAME) {
            throw new CustomException("400", "名稱不可超過 " + MAX_DISPLAY_NAME + " 字");
        }
        if (s.codePoints().anyMatch(SafeText::isForbidden)) {
            throw new CustomException("400", "名稱含有不允許的字元（< > \" ` 或控制字元）");
        }
        return s;
    }

    public static String sanitizeDisplayName(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder();
        raw.codePoints().filter(cp -> !isForbidden(cp)).forEach(sb::appendCodePoint);
        String s = sb.toString().trim();
        if (s.isEmpty()) {
            return fallback;
        }
        if (s.codePointCount(0, s.length()) > MAX_DISPLAY_NAME) {
            s = s.substring(0, s.offsetByCodePoints(0, MAX_DISPLAY_NAME));
        }
        return s;
    }
}
