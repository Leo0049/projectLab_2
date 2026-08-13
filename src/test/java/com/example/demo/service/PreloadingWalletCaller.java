package com.example.demo.service;

import com.example.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 測試用：重現「呼叫端在扣款前已經讀過同一個 User」的真實情境。
 *
 * <p>正式程式裡到處都是這個形狀——例如 {@code getMemberUnpaidTotalAndMarkPaid}
 * 會先碰 {@code item.getUser()}、{@code handleGroupOrderCancellation} 會先讀出訂單關係人。
 * 只要 User 已經在 persistence context 裡，{@code findByIdForUpdate} 就算確實取得列鎖，
 * Hibernate 仍會回傳快取中那個「上鎖之前」的實例。
 *
 * <p>{@code updateStoreCredit} 的傳播行為是 REQUIRED，會加入這裡開啟的交易，
 * 因此共用同一個 persistence context——這正是缺陷成立的條件。
 */
@Service
@RequiredArgsConstructor
public class PreloadingWalletCaller {

    private final UserRepository userRepository;
    private final TransactionRecordService transactionRecordService;

    @Transactional
    public void preloadThenCharge(Long userId, BigDecimal amount) {
        userRepository.findById(userId);   // 讓 User 先進入一級快取
        transactionRecordService.updateStoreCredit(userId, amount, "FINAL_PAY", LocalDateTime.now());
    }
}
