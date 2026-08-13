package com.example.demo.service;

import com.example.demo.entity.TransactionRecord;
import com.example.demo.entity.User;
import com.example.demo.repository.TransactionRecordRepository;
import com.example.demo.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 異動使用者餘額並寫入帳本。所有金流（儲值、託管、扣款、退款、補款）都經由此方法。
     *
     * ⚠️ 這裡必須用 findByIdForUpdate 鎖列，不可改回 findById：
     *    「讀出餘額 → 相加 → 寫回」在沒有列鎖時，併發請求會互相覆蓋，
     *    導致金額短少且帳本與餘額對不起來（詳見 UserRepository.findByIdForUpdate 註解）。
     */
    /**
     * 相容舊呼叫的入口：type 若還是「標題\n說明」的舊格式，這裡拆開後再寫入。
     * 新程式請直接用五個參數的版本，把種類與說明分開送。
     */
    @Transactional
    public User updateStoreCredit(Long userId, BigDecimal amount, String type, LocalDateTime createdAt) {
        var e = com.example.demo.service.wallet.TxDisplay.normalize(type, null, amount);
        return updateStoreCredit(userId, amount, e.type(), e.description(), createdAt);
    }

    @Transactional
    public User updateStoreCredit(Long userId, BigDecimal amount, String type, String description,
            LocalDateTime createdAt) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // ⚠️ 這一行不可省略，而且**必須帶 PESSIMISTIC_WRITE**。
        //
        // 呼叫端若在這之前已經讀過同一個 User（例如揪團結帳會先碰 item.getUser()），
        // 該 User 已在 persistence context 裡；此時上面那行雖然確實取得了列鎖，
        // Hibernate 仍會回傳快取中那個「上鎖之前」的實例，餘額是舊值——
        // 等於列鎖被一級快取架空，併發時每個交易都用同一個舊餘額計算，最後一個寫入獲勝。
        // 實測：團員同時送出結帳，帳本寫了 5 筆 −35，users.balance 卻只掉 35。
        //
        // 不能只用 entityManager.refresh(user)：MySQL 預設 REPEATABLE READ 之下，
        // 普通 SELECT 讀的是交易快照（快照在本交易第一次讀取時就固定了），refresh 回來還是舊值。
        // 只有「鎖定讀」會讀到最新已提交版本，所以必須指定 PESSIMISTIC_WRITE。
        entityManager.refresh(user, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);

        if (amount.compareTo(BigDecimal.ZERO) < 0 && user.getBalance().add(amount).compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("Insufficient store credit");
        }

        user.setBalance(user.getBalance().add(amount));
        user = userRepository.save(user);

        TransactionRecord record = new TransactionRecord();
        record.setUser(user);
        record.setAmount(amount);
        record.setType(type);
        record.setDescription(description);
        record.setCreatedAt(createdAt);
        transactionRecordRepository.save(record);

        return user;
    }
}
