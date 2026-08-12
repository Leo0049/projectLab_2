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

    @Transactional
    public User updateStoreCredit(Long userId, BigDecimal amount, String type, LocalDateTime createdAt) {
        User user = userRepository.findById(userId)
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
