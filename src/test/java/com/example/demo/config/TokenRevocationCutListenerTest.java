package com.example.demo.config;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountsRevokedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 登出所有裝置的切斷（stage 8）：只切斷被撤銷的顧客帳號，且綁在交易提交之後。 */
class TokenRevocationCutListenerTest {

    @Test
    @DisplayName("撤銷事件只切斷列出的顧客帳號，同 id 的門市與其他顧客不受影響")
    void cutsOnlyTheListedCustomer() {
        StompSessionRegistry registry = new StompSessionRegistry();
        registry.register("customer-7-phone", new AccountRef("CUSTOMER", 7L));
        registry.register("customer-7-laptop", new AccountRef("CUSTOMER", 7L));
        registry.register("store-7", new AccountRef("STORE", 7L));
        registry.register("customer-8", new AccountRef("CUSTOMER", 8L));
        TokenRevocationCutListener listener = new TokenRevocationCutListener(registry);

        listener.onAccountsRevoked(new AccountsRevokedEvent(List.of(new AccountRef("CUSTOMER", 7L))));

        assertTrue(registry.isCut("customer-7-phone"));
        assertTrue(registry.isCut("customer-7-laptop"));
        assertFalse(registry.isCut("store-7"));
        assertFalse(registry.isCut("customer-8"));
    }

    @Test
    @DisplayName("切斷綁在 AFTER_COMMIT 階段：撤銷交易提交之後才執行，回滾就不切斷")
    void cutIsBoundToTheAfterCommitPhase() throws NoSuchMethodException {
        // 直接呼叫 onAccountsRevoked 的測試不經過交易，看不出階段；這裡直接讀取註解。
        Method handler = TokenRevocationCutListener.class
                .getMethod("onAccountsRevoked", AccountsRevokedEvent.class);
        TransactionalEventListener binding = handler.getAnnotation(TransactionalEventListener.class);

        assertNotNull(binding, "切斷必須以 @TransactionalEventListener 接收事件");
        assertEquals(TransactionPhase.AFTER_COMMIT, binding.phase());
    }
}
