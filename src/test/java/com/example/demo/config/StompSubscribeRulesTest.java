package com.example.demo.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StompSubscribeRulesTest {

    /** 訂單 10 的參與者是顧客 1 與 2 */
    private final StompSubscribeRules.OrderParticipantCheck participants =
            (orderId, userId) -> orderId == 10L && (userId == 1L || userId == 2L);

    @Test
    @DisplayName("顧客可訂閱自己參與的訂單推播")
    void customerParticipantMayReadOrder() {
        assertNull(StompSubscribeRules.denyReason("CUSTOMER", 1L, "/topic/order/10", participants));
    }

    @Test
    @DisplayName("顧客不能訂閱不是自己參與的訂單推播")
    void customerOutsiderMayNotReadOrder() {
        assertNotNull(StompSubscribeRules.denyReason("CUSTOMER", 3L, "/topic/order/10", participants));
    }

    @Test
    @DisplayName("門市與顧客 id 撞號時，門市不能讀取訂單推播（角色必須是 CUSTOMER）")
    void storeWithSameNumericIdCannotReadOrder() {
        assertNotNull(StompSubscribeRules.denyReason("STORE", 1L, "/topic/order/10", participants));
    }

    @Test
    @DisplayName("門市只能訂閱自己的頻道")
    void storeMaySubscribeOwnChannelOnly() {
        assertNull(StompSubscribeRules.denyReason("STORE", 7L, "/topic/store/7", participants));
        assertNotNull(StompSubscribeRules.denyReason("STORE", 7L, "/topic/store/8", participants));
    }

    @Test
    @DisplayName("顧客或品牌即使 id 相同也不能訂閱門市頻道")
    void nonStoreRolesCannotReadStoreChannel() {
        assertNotNull(StompSubscribeRules.denyReason("CUSTOMER", 7L, "/topic/store/7", participants));
        assertNotNull(StompSubscribeRules.denyReason("BRAND", 7L, "/topic/store/7", participants));
    }

    @Test
    @DisplayName("揪團推播限顧客訂閱；門市與品牌不能訂閱")
    void groupChannelIsCustomerOnly() {
        assertNull(StompSubscribeRules.denyReason("CUSTOMER", 1L, "/topic/group/AbC123", participants));
        assertNotNull(StompSubscribeRules.denyReason("STORE", 1L, "/topic/group/AbC123", participants));
        assertNotNull(StompSubscribeRules.denyReason("BRAND", 1L, "/topic/group/AbC123", participants));
    }

    @Test
    @DisplayName("未知目的地一律拒絕（預設拒絕）")
    void unknownDestinationIsDenied() {
        assertNotNull(StompSubscribeRules.denyReason("CUSTOMER", 1L, "/topic/cart/1", participants));
        assertNotNull(StompSubscribeRules.denyReason("STORE", 7L, "/topic/anything", participants));
    }

    @Test
    @DisplayName("未認證（userId 為 null）與空白目的地一律拒絕")
    void unauthenticatedOrBlankIsDenied() {
        assertNotNull(StompSubscribeRules.denyReason("CUSTOMER", null, "/topic/order/10", participants));
        assertNotNull(StompSubscribeRules.denyReason("CUSTOMER", 1L, "", participants));
        assertNotNull(StompSubscribeRules.denyReason("CUSTOMER", 1L, null, participants));
    }

    @Test
    @DisplayName("超過 Long 範圍的 id 會被拒絕，而不是拋出例外")
    void hugeNumericIdIsDeniedNotThrown() {
        assertNotNull(StompSubscribeRules.denyReason("CUSTOMER", 1L, "/topic/order/99999999999999999999999", participants));
        assertNotNull(StompSubscribeRules.denyReason("STORE", 7L, "/topic/store/99999999999999999999999", participants));
    }
}
