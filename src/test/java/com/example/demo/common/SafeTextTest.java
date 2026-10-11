package com.example.demo.common;

import com.example.demo.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SafeTextTest {

    @Test
    @DisplayName("一般暱稱（中英數、常見符號）原樣保留，只去除前後空白")
    void acceptsOrdinaryNames() {
        assertEquals("王小明", SafeText.normalizeDisplayName("  王小明  "));
        assertEquals("O'Brien", SafeText.normalizeDisplayName("O'Brien"));
        assertEquals("Amy & Bob", SafeText.normalizeDisplayName("Amy & Bob"));
    }

    @Test
    @DisplayName("可用於標籤或屬性突破的字元一律拒絕（實測攻擊向量：<img onerror> 與 x\" onerror=）")
    void rejectsMarkupCharacters() {
        assertThrows(CustomException.class, () -> SafeText.normalizeDisplayName("<img src=x onerror=alert(1)>"));
        assertThrows(CustomException.class, () -> SafeText.normalizeDisplayName("x\" onerror=\"fetch()"));
        assertThrows(CustomException.class, () -> SafeText.normalizeDisplayName("a`b"));
        assertThrows(CustomException.class, () -> SafeText.normalizeDisplayName("bad\u0007name"));
    }

    @Test
    @DisplayName("空白與超過 30 字的名稱一律拒絕，剛好 30 字可通過")
    void rejectsBlankAndTooLong() {
        assertThrows(CustomException.class, () -> SafeText.normalizeDisplayName("   "));
        assertThrows(CustomException.class, () -> SafeText.normalizeDisplayName("字".repeat(31)));
        assertEquals("字".repeat(30), SafeText.normalizeDisplayName("字".repeat(30)));
    }

    @Test
    @DisplayName("null 維持 null，由呼叫端決定是否更新")
    void nullStaysNull() {
        assertNull(SafeText.normalizeDisplayName(null));
    }

    @Test
    @DisplayName("第三方名稱：不合規字元被清掉、超長截斷，不會讓登入失敗")
    void sanitizeStripsInsteadOfFailing() {
        assertEquals("Amyscript", SafeText.sanitizeDisplayName("Amy<script>", "新用戶"));
        assertEquals("字".repeat(30), SafeText.sanitizeDisplayName("字".repeat(40), "新用戶"));
    }

    @Test
    @DisplayName("第三方名稱清完後為空、或為 null 時使用 fallback")
    void sanitizeFallsBack() {
        assertEquals("新用戶", SafeText.sanitizeDisplayName("<<>>", "新用戶"));
        assertEquals("新用戶", SafeText.sanitizeDisplayName(null, "新用戶"));
        assertEquals("新用戶", SafeText.sanitizeDisplayName("   ", "新用戶"));
    }
}
