package com.example.demo.service;

import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderRatingRepository;
import com.example.demo.repository.StoreRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 門市評分彙總的併發測試。
 *
 * <p>{@code stores.avg_rating} 與 {@code stores.review_count} 是**反正規化**的欄位，
 * 每次有人評分就由 {@code refreshStoreAggregate} 重算後寫回。重算本身是
 * 「COUNT/AVG 之後 save」——read-modify-write，和錢包餘額同一個形狀。
 *
 * <p>MySQL 預設 REPEATABLE READ 之下，兩個併發交易各自的 COUNT 都只看得到
 * 自己那一筆新評分（看不到對方尚未提交的），於是兩邊都算出 N+1 並寫回，
 * 實際卻有 N+2 筆評分——門市後台與品牌口碑頁顯示的則數就會少算。
 *
 * <p>實際跑下去發現的比預期更糟：不是算錯，而是**大量死鎖**。
 * insert {@code order_ratings} 會因為外鍵在 {@code stores} 那一列加共享鎖，
 * 隨後 {@code refreshStoreAggregate} 的 update 要升級成排他鎖，兩個併發交易互相等待——
 * **實測 12 個併發評分只有 2 筆成功，其餘 10 筆全部失敗**。
 * 彙總數字之所以看起來「一致」，只是因為失敗的交易整個回滾了。
 *
 * <p>修法是先取門市那一列的排他鎖再寫評分（{@code StoreRepository.findByIdForUpdate}），
 * 讓後到的交易排隊而不是形成死結。這是顧客端金流之外的另一條
 * read-modify-write 路徑，先前沒有任何測試涵蓋。
 */
@SpringBootTest
class RatingConcurrencyTest {

    private static final int THREADS = 12;

    @Autowired
    private OrderRatingService orderRatingService;

    @Autowired
    private com.example.demo.repository.OrderItemRepository orderItemRepository;

    @Autowired
    private OrderRatingRepository orderRatingRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupOrderRepository groupOrderRepository;

    private Store store;
    private final List<User> users = new ArrayList<>();
    private final List<GroupOrder> orders = new ArrayList<>();
    private final List<com.example.demo.entity.OrderItem> items = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = storeRepository.findAll().stream().findFirst().orElse(null);
        if (store == null) return;
        store.setReviewCount(0);
        store.setAvgRating(BigDecimal.ZERO);
        storeRepository.save(store);

        for (int i = 0; i < THREADS; i++) {
            User u = new User();
            u.setName("評分併發測試員");
            u.setPhone("09" + String.format("%08d", (System.nanoTime() + i) % 100000000L));
            u.setRole("CUSTOMER");
            u.setBalance(BigDecimal.ZERO);
            users.add(userRepository.save(u));

            GroupOrder o = new GroupOrder();
            o.setInitiator(users.get(i));
            o.setStore(store);
            o.setType("SOLO");
            o.setStatus("COMPLETED");
            o.setTotalAmount(new BigDecimal("35"));
            GroupOrder saved = groupOrderRepository.save(o);
            orders.add(saved);

            // upsertRating 會檢查「你是不是這張單的參與者」，所以要有品項
            com.example.demo.entity.OrderItem item = new com.example.demo.entity.OrderItem();
            item.setGroupOrder(saved);
            item.setUser(users.get(i));
            item.setFinalPrice(new BigDecimal("35"));
            item.setPaymentStatus("PAID");
            item.setPaymentType("WALLET");
            items.add(orderItemRepository.save(item));
        }
    }

    @AfterEach
    void tearDown() {
        if (store == null) return;
        orderRatingRepository.deleteAll(orderRatingRepository.findByStoreIdOrderByCreatedAtDesc(store.getId()));
        items.forEach(i -> orderItemRepository.deleteById(i.getId()));
        orders.forEach(o -> groupOrderRepository.deleteById(o.getId()));
        users.forEach(u -> userRepository.deleteById(u.getId()));
        items.clear();
        orders.clear();
        users.clear();
    }

    @Test
    @DisplayName("併發評分後，門市的評分則數必須等於實際評分筆數")
    void concurrentRatingsKeepReviewCountConsistent() throws Exception {
        if (store == null) return;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger ok = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 分數刻意不同，順便驗平均值
                    orderRatingService.upsertRating(users.get(idx).getId(), orders.get(idx).getId(), (idx % 5) + 1);
                    ok.incrementAndGet();
                } catch (Exception ignored) {
                    // 併發下的失敗（例如死鎖重試）不算通過，由下面的斷言把關
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        long actual = orderRatingRepository.countByStoreId(store.getId());
        int stored = storeRepository.findById(store.getId()).orElseThrow().getReviewCount();

        // 先看有多少筆真的寫進去了：彙總「一致」但評分全被死鎖擋掉，不算通過
        assertEquals(THREADS, ok.get(),
                "有 " + (THREADS - ok.get()) + " 筆評分失敗（多半是死鎖），實際只寫入 " + actual + " 筆");
        assertEquals(actual, stored,
                "門市顯示的評分則數（" + stored + "）與實際評分筆數（" + actual + "）對不起來");
    }

    @Test
    @DisplayName("併發評分後，門市的平均分數必須等於重算結果")
    void concurrentRatingsKeepAverageConsistent() throws Exception {
        if (store == null) return;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    orderRatingService.upsertRating(users.get(idx).getId(), orders.get(idx).getId(), (idx % 5) + 1);
                } catch (Exception ignored) {
                    // 同上
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        Double avg = orderRatingRepository.findAverageRatingByStoreId(store.getId());
        BigDecimal expected = avg == null ? BigDecimal.ZERO
                : BigDecimal.valueOf(avg).setScale(1, java.math.RoundingMode.HALF_UP);
        BigDecimal stored = storeRepository.findById(store.getId()).orElseThrow().getAvgRating();

        assertEquals(0, expected.compareTo(stored),
                "門市顯示的平均分數（" + stored + "）與重算結果（" + expected + "）不一致");
    }
}
