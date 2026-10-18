package com.example.demo.common;

import com.example.demo.entity.AdminAccount;
import com.example.demo.repository.AdminAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 管理員的首次建立（N-28）：只在帳號不存在且設定有效時建立，設定錯誤直接拒絕啟動。 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    /** 常見的 BCrypt 範例雜湊（60 字元），只用來通過格式檢查，不會被拿來登入。 */
    private static final String VALID_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Mock
    private AdminAccountRepository admins;

    @InjectMocks
    private AdminBootstrap bootstrap;

    @Test
    @DisplayName("未設定帳號時不做任何事")
    void blankUsernameDoesNothing() {
        assertFalse(bootstrap.ensureAdmin("", VALID_HASH));
        assertFalse(bootstrap.ensureAdmin(null, null));
        verifyNoInteractions(admins);
    }

    @Test
    @DisplayName("帳號不存在時以 BCrypt 雜湊建立管理員")
    void createsAdminWhenMissing() {
        when(admins.existsByUsername("ops")).thenReturn(false);

        assertTrue(bootstrap.ensureAdmin("ops", VALID_HASH));

        ArgumentCaptor<AdminAccount> saved = ArgumentCaptor.forClass(AdminAccount.class);
        verify(admins).save(saved.capture());
        assertEquals("ops", saved.getValue().getUsername());
        assertEquals(VALID_HASH, saved.getValue().getPasswordHash());
        assertEquals("ADMIN", saved.getValue().getRole());
    }

    @Test
    @DisplayName("帳號已存在時不覆寫密碼，因此重複啟動是安全的")
    void doesNotOverwriteAnExistingAdmin() {
        when(admins.existsByUsername("ops")).thenReturn(true);

        assertFalse(bootstrap.ensureAdmin("ops", VALID_HASH));
        verify(admins, never()).save(any());
    }

    @Test
    @DisplayName("設定的是明文密碼而不是 BCrypt 雜湊時，啟動直接失敗")
    void rejectsAPlaintextPassword() {
        assertThrows(IllegalStateException.class, () -> bootstrap.ensureAdmin("ops", "plain-text-password"));
        verifyNoInteractions(admins);
    }

    @Test
    @DisplayName("帳號格式不合法（含空白、長度不足）時，啟動直接失敗")
    void rejectsAMalformedUsername() {
        assertThrows(IllegalStateException.class, () -> bootstrap.ensureAdmin("a b", VALID_HASH));
        assertThrows(IllegalStateException.class, () -> bootstrap.ensureAdmin("ab", VALID_HASH));
        verifyNoInteractions(admins);
    }
}
