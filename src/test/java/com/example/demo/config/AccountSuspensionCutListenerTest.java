package com.example.demo.config;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountsSuspendedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 停用事件的切斷（stage 6）：只切斷事件列出的帳號。 */
class AccountSuspensionCutListenerTest {

    @Test
    @DisplayName("停用事件切斷列出的帳號所有工作階段，其他帳號不受影響")
    void cutsOnlyTheListedAccounts() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("brand-session", new AccountRef("BRAND", 5L));
        registry.register("store-session", new AccountRef("STORE", 31L));
        registry.register("other-store", new AccountRef("STORE", 32L));
        AccountSuspensionCutListener listener = new AccountSuspensionCutListener(registry);

        listener.onAccountsSuspended(new AccountsSuspendedEvent(
                List.of(new AccountRef("BRAND", 5L), new AccountRef("STORE", 31L))));

        assertTrue(registry.isCut("brand-session"));
        assertTrue(registry.isCut("store-session"));
        assertFalse(registry.isCut("other-store"));
    }
}
