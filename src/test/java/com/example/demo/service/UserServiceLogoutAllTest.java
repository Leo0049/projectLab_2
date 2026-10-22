package com.example.demo.service;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountsRevokedEvent;
import com.example.demo.common.JwtUtils;
import com.example.demo.entity.User;
import com.example.demo.exception.CustomException;
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
 * 登出所有裝置（stage 8）的服務層規則：遞增權杖版本並發布切斷事件；
 * 找不到帳號時不產生任何副作用。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceLogoutAllTest {

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
    @DisplayName("登出所有裝置會遞增權杖版本並寫回資料庫")
    void bumpsTheTokenVersionAndSavesTheRow() {
        User user = new User();
        user.setId(7L);
        user.setTokenVersion(3);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

        userService.logoutAllDevices(7L);

        assertEquals(4, user.getTokenVersion());
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("登出所有裝置發布切斷事件，列出該顧客帳號（角色 CUSTOMER）")
    void publishesARevocationEventForTheCustomer() {
        User user = new User();
        user.setId(7L);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

        userService.logoutAllDevices(7L);

        verify(eventPublisher).publishEvent(
                new AccountsRevokedEvent(List.of(new AccountRef("CUSTOMER", 7L))));
    }

    @Test
    @DisplayName("找不到帳號時回 404，不寫入、不發布事件")
    void unknownUserIsRejectedWithoutSideEffects() {
        when(userRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> userService.logoutAllDevices(99L));

        assertEquals("404", ex.getCode());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }
}
