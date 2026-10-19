package com.example.demo.service.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 待接單逾時自動拒單的純規則（N-32，2026-10-19）。 */
class AutoRejectPolicyTest {

    private static final LocalDateTime SUBMITTED = LocalDateTime.of(2026, 10, 19, 12, 0, 0);
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 19, 11, 0, 0);

    @Test
    @DisplayName("只有 SUBMITTED 屬於自動拒單範圍；OPEN（揪團仍在加點）與接單後的狀態一律不屬於")
    void onlySubmittedIsEligible() {
        assertTrue(AutoRejectPolicy.isEligibleStatus("SUBMITTED"));
        assertTrue(AutoRejectPolicy.isEligibleStatus(" submitted "));
        assertFalse(AutoRejectPolicy.isEligibleStatus("OPEN"));
        assertFalse(AutoRejectPolicy.isEligibleStatus("PREPARING"));
        assertFalse(AutoRejectPolicy.isEligibleStatus("READY"));
        assertFalse(AutoRejectPolicy.isEligibleStatus("COMPLETED"));
        assertFalse(AutoRejectPolicy.isEligibleStatus("REJECTED"));
        assertFalse(AutoRejectPolicy.isEligibleStatus("CANCELLED"));
        assertFalse(AutoRejectPolicy.isEligibleStatus(""));
        assertFalse(AutoRejectPolicy.isEligibleStatus(null));
    }

    @Test
    @DisplayName("計時起點是送單時間；沒有送單時間（舊資料）才退回建立時間")
    void anchorIsSubmittedAtWithCreatedAtAsFallback() {
        assertEquals(SUBMITTED, AutoRejectPolicy.anchorOf(SUBMITTED, CREATED));
        assertEquals(CREATED, AutoRejectPolicy.anchorOf(null, CREATED));
        assertNull(AutoRejectPolicy.anchorOf(null, null));
    }

    @Test
    @DisplayName("嚴格超過 10 分鐘才逾時：滿 10 分鐘仍可接單，多 1 秒就逾時")
    void expiresStrictlyAfterTenMinutes() {
        assertFalse(AutoRejectPolicy.isExpired("SUBMITTED", SUBMITTED, CREATED, SUBMITTED.plusMinutes(9).plusSeconds(59)));
        assertFalse(AutoRejectPolicy.isExpired("SUBMITTED", SUBMITTED, CREATED, SUBMITTED.plusMinutes(10)));
        assertTrue(AutoRejectPolicy.isExpired("SUBMITTED", SUBMITTED, CREATED, SUBMITTED.plusMinutes(10).plusSeconds(1)));
    }

    @Test
    @DisplayName("計時從送單時刻起算，不是建立時刻：揪團晚送單時不會一建立就逾時")
    void groupOrderSubmittedLateIsNotExpiredByItsCreationTime() {
        // 團長 11:00 建立、12:00 才送單：12:05 時只過了 5 分鐘，不應拒單
        assertFalse(AutoRejectPolicy.isExpired("SUBMITTED", SUBMITTED, CREATED, SUBMITTED.plusMinutes(5)));
    }

    @Test
    @DisplayName("沒有送單時間的舊資料，以建立時間計時")
    void rowWithoutSubmittedAtFallsBackToCreatedAt() {
        assertTrue(AutoRejectPolicy.isExpired("SUBMITTED", null, CREATED, CREATED.plusMinutes(11)));
        assertFalse(AutoRejectPolicy.isExpired("SUBMITTED", null, CREATED, CREATED.plusMinutes(9)));
    }

    @Test
    @DisplayName("OPEN 與接單後的訂單，無論過了多久都不會被自動拒單")
    void openAndAcceptedOrdersNeverExpire() {
        LocalDateTime muchLater = CREATED.plusHours(5);
        assertFalse(AutoRejectPolicy.isExpired("OPEN", null, CREATED, muchLater));
        assertFalse(AutoRejectPolicy.isExpired("PREPARING", SUBMITTED, CREATED, muchLater));
        assertFalse(AutoRejectPolicy.isExpired("READY", SUBMITTED, CREATED, muchLater));
    }

    @Test
    @DisplayName("沒有時間資料或沒有現在時刻，一律不逾時（fail-closed）")
    void missingTimesDoNotExpire() {
        assertFalse(AutoRejectPolicy.isExpired("SUBMITTED", null, null, SUBMITTED.plusHours(1)));
        assertFalse(AutoRejectPolicy.isExpired("SUBMITTED", SUBMITTED, CREATED, null));
    }
}
