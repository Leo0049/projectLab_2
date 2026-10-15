package com.example.demo.service;

import com.example.demo.common.JwtUtils;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

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
}
