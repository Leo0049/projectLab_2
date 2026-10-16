package com.example.demo.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 記錄檔用的手機遮罩（N17，純函式，不載入 Spring context）。
 */
class PhoneMaskTest {

    @Test
    @DisplayName("一般的台灣手機只保留末三碼")
    void keepsOnlyTheLastThreeDigits() {
        assertEquals("*******678", PhoneMask.mask("0912345678"));
    }

    @Test
    @DisplayName("空白、連字號與括號不影響遮罩結果")
    void formattingDoesNotChangeTheMask() {
        assertEquals("*******678", PhoneMask.mask("0912-345-678"));
        assertEquals("*******678", PhoneMask.mask("(0912) 345 678"));
    }

    @Test
    @DisplayName("國碼前綴也被遮住，只露末三碼")
    void countryCodeIsMaskedToo() {
        assertEquals("*********678", PhoneMask.mask("+886 912 345 678"));
    }

    @Test
    @DisplayName("原始號碼不會以連續字串出現在遮罩結果中")
    void rawNumberNeverAppearsInFull() {
        String phone = "0912345678";
        assertFalse(PhoneMask.mask(phone).contains(phone));
    }

    @Test
    @DisplayName("太短的輸入整段遮掉，不露出任何數字")
    void shortInputIsFullyHidden() {
        assertEquals("***", PhoneMask.mask("12"));
        assertEquals("***", PhoneMask.mask("123"));
        assertEquals("***", PhoneMask.mask(""));
    }

    @Test
    @DisplayName("沒有手機時標示為無，而不是寫出 null")
    void missingPhoneIsMarkedAsAbsent() {
        assertEquals("(無)", PhoneMask.mask(null));
    }

    @Test
    @DisplayName("輸入含換行或控制字元時，輸出只剩星號與數字（防止偽造 log 行）")
    void outputNeverCarriesControlCharacters() {
        assertEquals("*******678", PhoneMask.mask("0912345678\r\n[INFO] forged"));
    }
}
