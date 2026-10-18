package com.example.demo.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 品牌審核狀態的純規則（N-28，不載入 Spring context）。 */
class BrandApprovalTest {

    @Test
    @DisplayName("只有完全等於 APPROVED 的狀態才算已核准（空值、未知與大小寫不同的狀態一律拒絕）")
    void onlyTheExactApprovedStatusCounts() {
        assertTrue(BrandApproval.isApproved("APPROVED"));
        assertFalse(BrandApproval.isApproved("PENDING"));
        assertFalse(BrandApproval.isApproved("REJECTED"));
        assertFalse(BrandApproval.isApproved("approved"));
        assertFalse(BrandApproval.isApproved(""));
        assertFalse(BrandApproval.isApproved(null));
    }

    @Test
    @DisplayName("只有待審的品牌可以被核准或退件")
    void onlyPendingCanBeDecided() {
        assertTrue(BrandApproval.canDecide("PENDING"));
        assertFalse(BrandApproval.canDecide("APPROVED"));
        assertFalse(BrandApproval.canDecide("REJECTED"));
        assertFalse(BrandApproval.canDecide(null));
    }

    @Test
    @DisplayName("登入被擋下時的說明文字依狀態不同")
    void loginBlockedMessageNamesTheReason() {
        assertEquals("帳號待審核，審核通過後即可登入", BrandApproval.loginBlockedMessage("PENDING"));
        assertEquals("帳號未通過審核", BrandApproval.loginBlockedMessage("REJECTED"));
        assertEquals("帳號目前無法使用", BrandApproval.loginBlockedMessage(null));
    }
}
