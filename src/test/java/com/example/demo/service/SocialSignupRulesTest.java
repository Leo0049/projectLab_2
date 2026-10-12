package com.example.demo.service;

import com.example.demo.service.SocialSignupRules.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SocialSignupRulesTest {

    @Test
    @DisplayName("已綁定且已有手機：直接登入，不論手機是否被他人使用")
    void linkedWithPhoneLogsIn() {
        assertEquals(Action.LOGIN_LINKED, SocialSignupRules.decide(true, true, false));
        assertEquals(Action.LOGIN_LINKED, SocialSignupRules.decide(true, true, true));
    }

    @Test
    @DisplayName("已綁定但沒有手機（舊帳號）：手機未被使用時補上並登入，不另建帳號")
    void linkedWithoutPhoneBindsPhone() {
        assertEquals(Action.BIND_PHONE_AND_LOGIN, SocialSignupRules.decide(true, false, false));
    }

    @Test
    @DisplayName("已綁定但沒有手機，而且手機已屬於他人：走整合流程，不另建帳號")
    void linkedWithoutPhoneButPhoneTakenIsConflict() {
        assertEquals(Action.MERGE_CONFLICT, SocialSignupRules.decide(true, false, true));
    }

    @Test
    @DisplayName("未綁定但手機已屬於他人：走整合流程（防止重複綁定同一手機）")
    void newSocialIdentityWithTakenPhoneIsConflict() {
        assertEquals(Action.MERGE_CONFLICT, SocialSignupRules.decide(false, false, true));
    }

    @Test
    @DisplayName("未綁定且手機未被使用：建立新帳號")
    void newSocialIdentityWithFreePhoneCreatesAccount() {
        assertEquals(Action.CREATE_NEW, SocialSignupRules.decide(false, false, false));
    }

    @Test
    @DisplayName("不變量：只要手機已被使用，就絕不會建立新帳號（窮舉所有組合）")
    void takenPhoneNeverCreatesAccount() {
        for (boolean linked : new boolean[] {true, false}) {
            for (boolean hasPhone : new boolean[] {true, false}) {
                boolean linkedUserOwnsPhone = linked && hasPhone;
                Action a = SocialSignupRules.decide(linked, hasPhone, true);
                assertNotEquals(Action.CREATE_NEW, a, "linked=" + linked + " hasPhone=" + hasPhone);
                if (!linkedUserOwnsPhone) {
                    assertNotEquals(Action.LOGIN_LINKED, a, "linked=" + linked + " hasPhone=" + hasPhone);
                }
            }
        }
    }
}
