package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.*;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserProfileService {

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private TransactionRecordRepository transactionRecordRepository;
    @Autowired private com.cloudinary.Cloudinary cloudinary;

    // ─── 取得會員資料 ─────────────────────────────────────
    public Map<String, Object> getMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("404", "找不到使用者"));
        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("name", user.getName());
        result.put("phone", user.getPhone());
        result.put("picUrl", user.getPicUrl());
        result.put("balance", user.getBalance());
        result.put("createdAt", user.getCreatedAt());
        return result;
    }

    // ─── 修改會員資料 ─────────────────────────────────────
    public void updateMe(Long userId, String name, String picUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("404", "找不到使用者"));
        if (name != null) user.setName(name);
        if (picUrl != null) user.setPicUrl(picUrl);
        userRepository.save(user);
    }

    // ─── 邏輯刪除會員 ─────────────────────────────────────
    @Transactional
    public void deleteMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("404", "找不到使用者"));
        user.setIsDeleted(true);
        user.setDeletedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    // ─── 錢包餘額 ─────────────────────────────────────────
    public Map<String, Object> getWallet(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("404", "找不到使用者"));
        Map<String, Object> result = new HashMap<>();
        result.put("balance", user.getBalance());
        return result;
    }

    // ─── 交易紀錄 ─────────────────────────────────────────
    public Map<String, Object> getTransactions(Long userId, Integer months, int page, int size) {
        List<TransactionRecord> list;
        if (months != null) {
            LocalDateTime from = LocalDateTime.now().minusMonths(months);
            list = transactionRecordRepository.findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(userId, from);
        } else {
            list = transactionRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);
        }
        int start = (page - 1) * size;
        int end = Math.min(start + size, list.size());
        List<TransactionRecord> paged = (start < list.size()) ? list.subList(start, end) : List.of();

        List<Map<String, Object>> items = new ArrayList<>();
        for (TransactionRecord t : paged) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("type", t.getType());
            m.put("amount", t.getAmount());
            m.put("createdAt", t.getCreatedAt());
            items.add(m);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("transactions", items);
        result.put("page", page);
        result.put("total", list.size());
        return result;
    }

    // ─── 儲值 ─────────────────────────────────────────────
    @Transactional
    public Map<String, Object> topUp(Long userId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new CustomException("400", "儲值金額必須大於 0");
        // ⚠️ 必須鎖列，否則併發儲值會互相覆蓋（見 UserRepository.findByIdForUpdate）
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException("404", "找不到使用者"));
        user.setBalance(user.getBalance().add(amount));
        userRepository.save(user);

        // 記錄交易
        TransactionRecord tx = new TransactionRecord();
        tx.setUser(user);
        tx.setAmount(amount);
        tx.setType("TOPUP");
        transactionRecordRepository.save(tx);

        Map<String, Object> result = new HashMap<>();
        result.put("newBalance", user.getBalance());
        return result;
    }

    // ─── 上傳頭像 ─────────────────────────────────────────────
    public Map<String, Object> uploadAvatar(Long userId, org.springframework.web.multipart.MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) throw new com.example.demo.exception.CustomException("400", "請選擇圖片");

        
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> uploadResult = cloudinary.uploader().upload(
            file.getBytes(),
            com.cloudinary.utils.ObjectUtils.asMap(
                "folder", "avatars",
                "public_id", "user_" + userId,
                "overwrite", true
            )
        );
        String imageUrl = (String) uploadResult.get("secure_url");

        com.example.demo.entity.User user = userRepository.findById(userId)
                .orElseThrow(() -> new com.example.demo.exception.CustomException("404", "找不到用戶"));
        user.setPicUrl(imageUrl);
        userRepository.save(user);

        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("avatarUrl", imageUrl);
        return result;
    }

}