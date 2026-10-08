package com.example.demo.service;

import com.example.demo.dto.SocialAuthRequest;
import com.example.demo.entity.User;
import com.example.demo.entity.UserAuthProvider;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.UserAuthProviderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.common.JwtUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceSecurityTest {
    @Mock private UserAuthProviderRepository userAuthProviderRepository;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtils jwtUtils;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService();
        ReflectionTestUtils.setField(authService, "userAuthProviderRepository", userAuthProviderRepository);
        ReflectionTestUtils.setField(authService, "userRepository", userRepository);
        ReflectionTestUtils.setField(authService, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(authService, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(authService, "smsMode", "mock");
    }

    @Test
    void mockFirebaseTokenIsRejectedOutsideMockMode() {
        ReflectionTestUtils.setField(authService, "smsMode", "firebase");

        assertThrows(CustomException.class, () -> authService.verifyOnly("MOCK_TOKEN"));
    }

    @Test
    void deletedAccountCannotLogInThroughSocialProvider() {
        String phone = "0912345678";
        User deletedUser = new User();
        deletedUser.setIsDeleted(true);
        UserAuthProvider provider = new UserAuthProvider();
        provider.setUser(deletedUser);
        when(userAuthProviderRepository.findByProviderAndProviderUid("GOOGLE", "MOCK_UID_GOOGLE_" + phone))
                .thenReturn(Optional.of(provider));

        SocialAuthRequest request = new SocialAuthRequest();
        request.setProvider("GOOGLE");
        request.setPhone(phone);
        request.setIdToken("MOCK_TOKEN");

        assertThrows(CustomException.class, () -> authService.socialLogin(request));
    }

    @Test
    void phoneProofCannotBeCombinedWithAnotherSocialAccountUid() {
        String phone = "0912345678";
        when(userRepository.findByPhone(phone)).thenReturn(Optional.of(new User()));

        assertThrows(CustomException.class, () -> authService.mergeBindSocialByPhone(
                "MOCK_TOKEN", phone, "MOCK_TOKEN", "ATTACKER_UID", "GOOGLE"));
    }

    @Test
    void socialBindRejectsMissingPhoneProof() {
        assertThrows(CustomException.class, () -> authService.mergeBindSocialByPhone(
                "", "0912345678", "MOCK_TOKEN", "MOCK_UID_GOOGLE_0912345678", "GOOGLE"));
    }

    @Test
    void mergeRejectsPhoneTokenForAnotherNumber() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        FirebaseToken phoneToken = mock(FirebaseToken.class);
        when(phoneToken.getClaims()).thenReturn(Map.of("phone_number", "+886912345678"));

        try (MockedStatic<FirebaseAuth> firebase = mockStatic(FirebaseAuth.class)) {
            firebase.when(FirebaseAuth::getInstance).thenReturn(firebaseAuth);
            when(firebaseAuth.verifyIdToken("phone-token")).thenReturn(phoneToken);

            assertThrows(CustomException.class, () -> authService.mergeSetPasswordAndBindSocial(
                    "phone-token", "0912345679", "newpass123", "social-token", "uid", "GOOGLE"));
            verify(passwordEncoder, never()).encode(anyString());
        }
    }

    @Test
    void verifiedPhoneAndMatchingSocialTokenCompleteTheMerge() throws Exception {
        String phone = "0912345678";
        User user = new User();
        user.setId(9L);
        user.setPhone(phone);
        user.setName("會員");
        when(userRepository.findByPhone(phone)).thenReturn(Optional.of(user));
        when(userAuthProviderRepository.findByProviderAndProviderUid("GOOGLE", "google-uid"))
                .thenReturn(Optional.empty());
        when(passwordEncoder.encode("newpass123")).thenReturn("hashed-password");
        when(jwtUtils.generateToken(9L, "CUSTOMER", phone)).thenReturn("session-token");

        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        FirebaseToken phoneToken = mock(FirebaseToken.class);
        when(phoneToken.getClaims()).thenReturn(Map.of("phone_number", "+886912345678"));
        FirebaseToken socialToken = mock(FirebaseToken.class);
        when(socialToken.getClaims()).thenReturn(Map.of(
                "firebase", Map.of("sign_in_provider", "google.com")));
        when(socialToken.getUid()).thenReturn("google-uid");

        try (MockedStatic<FirebaseAuth> firebase = mockStatic(FirebaseAuth.class)) {
            firebase.when(FirebaseAuth::getInstance).thenReturn(firebaseAuth);
            when(firebaseAuth.verifyIdToken("phone-token")).thenReturn(phoneToken);
            when(firebaseAuth.verifyIdToken("social-token")).thenReturn(socialToken);

            var result = authService.mergeSetPasswordAndBindSocial("phone-token", phone,
                    "newpass123", "social-token", "google-uid", "GOOGLE");

            Assertions.assertEquals("200", result.getCode());
            Assertions.assertEquals("hashed-password", user.getPasswordHash());
            Assertions.assertEquals("session-token", ((Map<?, ?>) result.getData()).get("token"));
            verify(userAuthProviderRepository).save(any());
        }
    }

    @Test
    void mismatchedFirebaseSocialProviderIsRejectedBeforeSettingPassword() throws Exception {
        String phone = "0912345678";
        User user = new User();
        user.setPhone(phone);
        when(userRepository.findByPhone(phone)).thenReturn(Optional.of(user));

        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        FirebaseToken phoneToken = mock(FirebaseToken.class);
        when(phoneToken.getClaims()).thenReturn(Map.of("phone_number", "+886912345678"));
        FirebaseToken socialToken = mock(FirebaseToken.class);
        when(socialToken.getClaims()).thenReturn(Map.of(
                "firebase", Map.of("sign_in_provider", "facebook.com")));

        try (MockedStatic<FirebaseAuth> firebase = mockStatic(FirebaseAuth.class)) {
            firebase.when(FirebaseAuth::getInstance).thenReturn(firebaseAuth);
            when(firebaseAuth.verifyIdToken("phone-token")).thenReturn(phoneToken);
            when(firebaseAuth.verifyIdToken("social-token")).thenReturn(socialToken);

            assertThrows(CustomException.class, () -> authService.mergeSetPasswordAndBindSocial(
                    "phone-token", phone, "newpass123", "social-token", "some-uid", "GOOGLE"));
            verify(passwordEncoder, never()).encode(anyString());
        }
    }
}
