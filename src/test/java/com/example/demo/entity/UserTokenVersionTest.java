package com.example.demo.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 帳號的權杖版本（N13；純實體方法，不載入 Spring context）。
 *
 * <p>改密碼與刪除帳號都必須遞增版本，既有權杖才會失效。這裡只測實體本身的規則；
 * 每一條改密碼路徑是否真的呼叫它，由 CI 的整合測試與 code review 負責。
 */
class UserTokenVersionTest {

    @Test
    @DisplayName("新帳號的權杖版本從 0 開始")
    void newAccountStartsAtVersionZero() {
        assertEquals(0, new User().getTokenVersion());
    }

    @Test
    @DisplayName("改密碼會換掉雜湊並遞增版本，之前的權杖因此失效（N13）")
    void changingThePasswordBumpsTheVersion() {
        User user = new User();
        user.setPasswordHash("old-hash");

        user.changePasswordHash("new-hash");

        assertEquals("new-hash", user.getPasswordHash());
        assertEquals(1, user.getTokenVersion());
    }

    @Test
    @DisplayName("每次改密碼都再遞增一次，版本不會回到舊值（N13）")
    void everyPasswordChangeGetsAFreshVersion() {
        User user = new User();
        user.changePasswordHash("h1");
        user.changePasswordHash("h2");

        assertEquals(2, user.getTokenVersion());
    }

    @Test
    @DisplayName("撤銷權杖只遞增版本，不動密碼（N13）")
    void revokingTokensLeavesThePasswordAlone() {
        User user = new User();
        user.setPasswordHash("keep-me");

        user.revokeIssuedTokens();

        assertEquals("keep-me", user.getPasswordHash());
        assertEquals(1, user.getTokenVersion());
    }
}
