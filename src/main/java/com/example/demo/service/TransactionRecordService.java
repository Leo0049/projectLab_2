package com.example.demo.service;

import com.example.demo.entity.TransactionRecord;
import com.example.demo.entity.User;
import com.example.demo.repository.TransactionRecordRepository;
import com.example.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TransactionRecordService {
    private final UserRepository userRepository;
    private final TransactionRecordRepository transactionRecordRepository;

    /**
     * 異動使用者餘額並寫入帳本。所有金流（儲值、託管、扣款、退款、補款）都經由此方法。
     *
     * ⚠️ 這裡必須用 findByIdForUpdate 鎖列，不可改回 findById：
     *    「讀出餘額 → 相加 → 寫回」在沒有列鎖時，併發請求會互相覆蓋，
     *    導致金額短少且帳本與餘額對不起來（詳見 UserRepository.findByIdForUpdate 註解）。
     */
    @Transactional
    public User updateStoreCredit(Long userId, BigDecimal amount, String type, LocalDateTime createdAt) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (amount.compareTo(BigDecimal.ZERO) < 0 && user.getBalance().add(amount).compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("Insufficient store credit");
        }

        user.setBalance(user.getBalance().add(amount));
        user = userRepository.save(user);

        TransactionRecord record = new TransactionRecord();
        record.setUser(user);
        record.setAmount(amount);
        record.setType(type);
        record.setCreatedAt(createdAt);
        transactionRecordRepository.save(record);

        return user;
    }
}
