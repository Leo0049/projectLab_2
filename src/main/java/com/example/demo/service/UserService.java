package com.example.demo.service;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountsRevokedEvent;
import com.example.demo.common.JwtUtils;
import com.example.demo.entity.User;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    /** 顧客在權杖聲明與 STOMP 身分中的角色字串（與 User.role 的預設值相同） */
    private static final String CUSTOMER_ROLE = "CUSTOMER";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final ApplicationEventPublisher eventPublisher;

    public Optional<User> findById(Long userId) {
        return userRepository.findById(userId);
    }

    // registerUser 已移除：全專案無呼叫者，且會直接接受未經 OTP 驗證的手機號碼。註冊一律走 AuthService。

    public Optional<User> findByUsername(String username) {
        return userRepository.findFirstByName(username);
    }

    public Optional<User> findByPhone(String phone) {
        return userRepository.findByPhone(phone);
    }

    @Transactional
    public User updateUserAvatar(Long userId, String avatarUrl) {
        Optional<User> optionalUser = userRepository.findById(userId);
        if (optionalUser.isPresent()) {
            User user = optionalUser.get();
            user.setPicUrl(avatarUrl);
            return userRepository.save(user);
        }
        throw new RuntimeException("User not found");
    }

    @Transactional
    public User updateUserProfile(Long userId, String username) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        userRepository.findFirstByName(username).ifPresent(u -> {
            if (!u.getId().equals(userId))
                throw new RuntimeException("Username already exists");
        });

        user.setName(username);
        return userRepository.save(user);
    }

    /**
     * 修改密碼，回傳新權杖。改密碼會遞增 tokenVersion，之前簽發的權杖全部失效（N13）；
     * 目前這個瀏覽器拿到新權杖即可維持登入。
     */
    @Transactional
    public String changePassword(Long userId, String oldPassword, String newPassword) {
        // 列鎖：同一帳號併發改密碼時，版本遞增不會互相覆蓋
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new RuntimeException("舊密碼不正確");
        }

        user.changePasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return jwtUtils.generateToken(user.getId(), user.getRole(), user.getPhone(), user.getTokenVersion());
    }

    /**
     * 登出所有裝置（stage 8，2026-10-22）：遞增 tokenVersion，所有已簽發的權杖（含目前這個瀏覽器）全部失效，
     * 之後需要重新登入（N13）。交易提交後，{@link AccountsRevokedEvent} 會切斷該帳號既有的 WebSocket 連線。
     */
    @Transactional
    public void logoutAllDevices(Long userId) {
        // 列鎖：與改密碼的版本遞增互斥，兩者併發時版本不會少算
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException("404", "找不到使用者"));
        user.revokeIssuedTokens();
        userRepository.save(user);
        eventPublisher.publishEvent(
                new AccountsRevokedEvent(List.of(new AccountRef(CUSTOMER_ROLE, userId))));
    }
}
