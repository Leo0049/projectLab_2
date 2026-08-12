package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.repository.TransactionRecordRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 錢包併發對帳測試。
 *
 * 這支測試存在的理由：餘額原本是「讀出 → 相加 → 寫回」且沒有任何列鎖，
 * 實測 20 個併發各儲值 10 元，最終只入帳 70 元，且 transaction_records
 * 的總額（120）與 users.balance（70）對不起來。
 *
 * 修補方式是 UserRepository.findByIdForUpdate（SELECT ... FOR UPDATE）。
 * 若有人把它改回 findById，下面兩個測試會失敗。
 */
@SpringBootTest
class WalletConcurrencyTest {

    private static final int THREADS = 20;
    private static final BigDecimal STEP = new BigDecimal("10.00");

    @Autowired
    private TransactionRecordService transactionRecordService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRecordRepository transactionRecordRepository;

    private Long userId;

    @BeforeEach
    void createUser() {
        User u = new User();
        u.setName("併發測試用戶");
        u.setPhone("09" + String.format("%08d", (System.nanoTime() % 100000000L)));
        u.setRole("CUSTOMER");
        u.setBalance(BigDecimal.ZERO);
        userId = userRepository.save(u).getId();
    }

    @AfterEach
    void cleanUp() {
        transactionRecordRepository.deleteAll(
                transactionRecordRepository.findByUserIdOrderByCreatedAtDesc(userId));
        userRepository.deleteById(userId);
    }

    @Test
    @DisplayName("併發儲值：餘額必須等於帳本總額，且不可短少")
    void concurrentTopUpsAreNotLost() throws Exception {
        int ok = runConcurrently(THREADS, () ->
                transactionRecordService.updateStoreCredit(userId, STEP, "TOPUP", java.time.LocalDateTime.now()));

        assertEquals(THREADS, ok, "所有儲值請求都應成功");

        BigDecimal expected = STEP.multiply(BigDecimal.valueOf(THREADS));
        BigDecimal balance = userRepository.findById(userId).orElseThrow().getBalance();
        BigDecimal ledger = transactionRecordRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(r -> r.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, expected.compareTo(balance),
                "餘額短少：預期 " + expected + "，實際 " + balance + "（lost update）");
        assertEquals(0, balance.compareTo(ledger),
                "帳本與餘額不一致：餘額 " + balance + "、帳本 " + ledger + "（無法對帳）");
    }

    @Test
    @DisplayName("併發扣款：餘額不可被扣成負數")
    void concurrentDeductionsCannotOverdraw() throws Exception {
        // 先存入剛好 5 次扣款的額度，再用 20 個併發去扣
        BigDecimal seed = STEP.multiply(BigDecimal.valueOf(5));
        transactionRecordService.updateStoreCredit(userId, seed, "TOPUP", java.time.LocalDateTime.now());

        int ok = runConcurrently(THREADS, () ->
                transactionRecordService.updateStoreCredit(
                        userId, STEP.negate(), "FINAL_PAY", java.time.LocalDateTime.now()));

        BigDecimal balance = userRepository.findById(userId).orElseThrow().getBalance();

        assertEquals(5, ok, "只有 5 筆扣款應該成功，其餘應因餘額不足被拒");
        assertTrue(balance.compareTo(BigDecimal.ZERO) >= 0, "餘額被扣成負數：" + balance);
        assertEquals(0, BigDecimal.ZERO.compareTo(balance), "餘額應剛好扣完為 0，實際 " + balance);
    }

    /** 讓 n 個執行緒同時開跑，回傳成功次數 */
    private int runConcurrently(int n, Callable<?> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();          // 一起衝，放大競態機率
                    try {
                        action.call();
                        success.incrementAndGet();
                    } catch (Exception expected) {
                        // 餘額不足等業務例外屬預期結果，不計入成功
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return success.get();
    }
}
