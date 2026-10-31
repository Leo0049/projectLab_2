package com.example.demo.service;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountsRevokedEvent;
import com.example.demo.common.JwtUtils;
import com.example.demo.entity.User;
import com.example.demo.repository.UserAuthProviderRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重設密碼與帳號整合設定密碼（stage 21）：三條路徑改完密碼後都發布顧客的撤銷事件，
 * 讓既有的推播工作階段在交易提交後切斷。以 MOCK_TOKEN 走本機模擬的手機驗證（sms.mode=mock），不呼叫 Firebase。
 */
@ExtendWith(MockitoExtension.class)
class AuthServicePasswordRevocationTest {

    private static final String PHONE = "0912345678";
    private static final String SOCIAL_UID = "MOCK_UID_GOOGLE_" + PHONE;
    private static final AccountsRevokedEvent CUSTOMER_7 =
            new AccountsRevokedEvent(List.of(new AccountRef("CUSTOMER", 7L)));

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAuthProviderRepository userAuthProviderRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService();
        ReflectionTestUtils.setField(authService, "userRepository", userRepository);
        ReflectionTestUtils.setField(authService, "userAuthProviderRepository", userAuthProviderRepository);
        ReflectionTestUtils.setField(authService, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(authService, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(authService, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(authService, "smsMode", "mock");
    }

    @Test
    @DisplayName("重設密碼後發布顧客的撤銷事件")
    void resettingThePasswordRevokesTheCustomersSessions() throws Exception {
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(customer()));
        when(passwordEncoder.encode("newpass123")).thenReturn("new-hash");

        authService.resetPasswordWithFirebase(AuthService.MOCK_TOKEN, PHONE, "newpass123");

        verify(eventPublisher).publishEvent(CUSTOMER_7);
    }

    @Test
    @DisplayName("三方帳號設定密碼後發布顧客的撤銷事件")
    void settingThePasswordDuringMergeRevokesTheCustomersSessions() throws Exception {
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(customer()));
        when(passwordEncoder.encode("newpass123")).thenReturn("new-hash");

        authService.mergeSetPassword(AuthService.MOCK_TOKEN, PHONE, "newpass123");

        verify(eventPublisher).publishEvent(CUSTOMER_7);
    }

    @Test
    @DisplayName("帳號整合（設定密碼並綁定三方）後發布顧客的撤銷事件")
    void mergingWithASocialIdentityRevokesTheCustomersSessions() throws Exception {
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(customer()));
        when(userAuthProviderRepository.findByProviderAndProviderUid("GOOGLE", SOCIAL_UID))
                .thenReturn(Optional.empty());
        when(passwordEncoder.encode("newpass123")).thenReturn("new-hash");

        authService.mergeSetPasswordAndBindSocial(AuthService.MOCK_TOKEN, PHONE, "newpass123",
                AuthService.MOCK_TOKEN, SOCIAL_UID, "GOOGLE");

        verify(eventPublisher).publishEvent(CUSTOMER_7);
    }

    private static User customer() {
        User user = new User();
        user.setId(7L);
        user.setPhone(PHONE);
        user.setPasswordHash("old-hash");
        user.setTokenVersion(3);
        return user;
    }
}
