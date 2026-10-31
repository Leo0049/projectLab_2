package com.example.demo.service;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountsRevokedEvent;
import com.example.demo.common.JwtUtils;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 顧客改密碼（stage 21）：改密碼換掉雜湊、遞增權杖版本，並發布撤銷事件，讓該帳號既有的推播工作階段在交易提交後切斷；
 * 舊密碼不正確時不寫入、不發布。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceChangePasswordTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("改密碼換掉雜湊、遞增權杖版本，並發布顧客的撤銷事件")
    void changingThePasswordRevokesTheCustomersSessions() {
        User user = customer(7L, "old-hash", 3);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old-pass", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-pass")).thenReturn("new-hash");

        userService.changePassword(7L, "old-pass", "new-pass");

        assertEquals("new-hash", user.getPasswordHash());
        assertEquals(4, user.getTokenVersion());
        verify(userRepository).save(user);
        verify(eventPublisher).publishEvent(
                new AccountsRevokedEvent(List.of(new AccountRef("CUSTOMER", 7L))));
    }

    @Test
    @DisplayName("舊密碼不正確時拋出例外，不改密碼、不遞增版本、不發布事件")
    void aWrongOldPasswordChangesNothingAndPublishesNothing() {
        User user = customer(7L, "old-hash", 3);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-pass", "old-hash")).thenReturn(false);

        assertThrows(RuntimeException.class,
                () -> userService.changePassword(7L, "wrong-pass", "new-pass"));

        assertEquals("old-hash", user.getPasswordHash());
        assertEquals(3, user.getTokenVersion());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    private static User customer(long id, String passwordHash, int tokenVersion) {
        User user = new User();
        user.setId(id);
        user.setPasswordHash(passwordHash);
        user.setTokenVersion(tokenVersion);
        return user;
    }
}
