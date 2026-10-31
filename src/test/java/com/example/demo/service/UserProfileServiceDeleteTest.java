package com.example.demo.service;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountsRevokedEvent;
import com.example.demo.entity.User;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 刪除帳號（stage 21）：邏輯刪除、遞增權杖版本，並發布顧客的撤銷事件，讓既有的推播工作階段在交易提交後切斷。
 * N7 只在 CONNECT 與 SUBSCRIBE 時檢查帳號，已建立的訂閱若不切斷會持續收到推播。
 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceDeleteTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private UserProfileService userProfileService;

    @BeforeEach
    void setUp() {
        userProfileService = new UserProfileService();
        ReflectionTestUtils.setField(userProfileService, "userRepository", userRepository);
        ReflectionTestUtils.setField(userProfileService, "eventPublisher", eventPublisher);
    }

    @Test
    @DisplayName("刪除帳號後標記為已刪除、遞增權杖版本，並發布顧客的撤銷事件")
    void deletingTheAccountRevokesTheCustomersSessions() {
        User user = new User();
        user.setId(7L);
        user.setTokenVersion(3);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        userProfileService.deleteMe(7L);

        assertTrue(Boolean.TRUE.equals(user.getIsDeleted()));
        assertEquals(4, user.getTokenVersion());
        verify(userRepository).save(user);
        verify(eventPublisher).publishEvent(
                new AccountsRevokedEvent(List.of(new AccountRef("CUSTOMER", 7L))));
    }

    @Test
    @DisplayName("找不到帳號時回 404，不寫入、不發布事件")
    void unknownAccountChangesNothingAndPublishesNothing() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> userProfileService.deleteMe(99L));

        assertEquals("404", ex.getCode());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }
}
