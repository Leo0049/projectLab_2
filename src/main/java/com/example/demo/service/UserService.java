package com.example.demo.service;

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

    public Optional<User> findById(Long userId) {
        return userRepository.findById(userId);
    }

    @Transactional
    public Long registerUser(String role, String username, String passwordHash, String phone) {
        if (userRepository.findFirstByName(username).isPresent()) {
            throw new RuntimeException("Username already exists");
        }
        if (userRepository.findByPhone(phone).isPresent()) {
            throw new RuntimeException("Phone already exists");
        }

        User user = new User();
        user.setRole(role);
        user.setName(username);
        user.setPasswordHash(passwordHash);
        user.setPhone(phone);
        user = userRepository.save(user);
        return user.getId();
    }

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

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new RuntimeException("舊密碼不正確");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}
