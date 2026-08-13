package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.*;
import com.example.demo.service.wallet.TxDisplay;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    @Autowired private ImageStorageService imageStorageService;

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
    /** 一次最多回幾筆。錢包帳本會隨使用時間無限成長，和訂單列表同一個道理 */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * 交易紀錄（分頁）。{@code page} 沿用既有前端的 1-based。
     *
     * <p>⚠️ 原本是 {@code findByUserIdOrderByCreatedAtDesc(userId)} 把整份帳本撈進記憶體、
     * 再用 {@code subList} 切頁——查十筆卻讀了全部。這正是 {@code /api/stores/orders}
     * 已經修過的那個問題（實測 6,666 筆 / 2.1 MB），錢包帳本當時漏掉了。
     *
     * <p>回傳的 type 一律經 {@link TxDisplay#normalize} 正規化，
     * 舊資料（type 塞了「標題\n說明」）也會被拆回 type／description，
     * 前端不必再自己 split 或用中文字串比對猜種類。
     */
    public Map<String, Object> getTransactions(Long userId, Integer months, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int zeroBased = Math.max(page - 1, 0);
        Pageable pageable = PageRequest.of(zeroBased, safeSize);

        Page<TransactionRecord> result = (months != null)
                ? transactionRecordRepository.findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                        userId, LocalDateTime.now().minusMonths(months), pageable)
                : transactionRecordRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);

        List<Map<String, Object>> items = new ArrayList<>();
        for (TransactionRecord t : result.getContent()) {
            TxDisplay.Entry e = TxDisplay.normalize(t.getType(), t.getDescription(), t.getAmount());
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("type", e.type());
            m.put("description", e.description());
            m.put("label", e.label());
            m.put("amount", t.getAmount());
            m.put("createdAt", t.getCreatedAt());
            items.add(m);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("transactions", items);
        out.put("page", page);
        out.put("size", safeSize);
        out.put("total", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        out.put("hasNext", result.hasNext());
        return out;
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

        
        // 用 public_id 綁定使用者，同一人重複上傳會覆寫舊檔（本機與 Cloudinary 行為一致）
        String imageUrl = imageStorageService.upload(file, "avatars", "user_" + userId);

        com.example.demo.entity.User user = userRepository.findById(userId)
                .orElseThrow(() -> new com.example.demo.exception.CustomException("404", "找不到用戶"));
        user.setPicUrl(imageUrl);
        userRepository.save(user);

        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("avatarUrl", imageUrl);
        return result;
    }

}