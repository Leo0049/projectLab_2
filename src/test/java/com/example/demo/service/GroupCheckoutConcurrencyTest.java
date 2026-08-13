package com.example.demo.service;

import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Store;
import com.example.demo.entity.UserCoupon;
import com.example.demo.entity.User;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.TransactionRecordRepository;
import com.example.demo.repository.UserCouponRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 揪團「團員為自己的餐點結帳」的併發正確性。
 *
 * <p>這支測試存在的理由：實測用同一個團員同時送出 8 個結帳請求（等同雙擊或重送），
 * 結果是 <b>5 個請求都回 200「已付款」、帳本寫進 5 筆 −35（合計 −175），
 * 但 users.balance 只掉了 35</b>。帳本與餘額差 5 倍，對含金流的系統代表無法對帳。
 *
 * <p>成因有兩層，缺一不可：
 * <ol>
 *   <li><b>品項沒有鎖</b>：「讀出 UNPAID 品項 → 扣款 → 標記 PAID」是
 *       read-modify-write，同一批品項會被多個交易同時判定為未付款。</li>
 *   <li><b>列鎖被一級快取架空</b>：呼叫端在扣款前先讀過 {@code item.getUser()}，
 *       User 已進入 persistence context；之後 {@code findByIdForUpdate} 雖然
 *       取得了列鎖，Hibernate 卻回傳快取裡那個「上鎖之前」的實例，
 *       於是每個交易都拿同一個舊餘額去加減，最後一個寫入獲勝。</li>
 * </ol>
 *
 * <p>只修第二層會更糟：帳本與餘額會一致，但使用者要為同一杯飲料被扣 5 次。
 */
@SpringBootTest
class GroupCheckoutConcurrencyTest {

    private static final int THREADS = 8;
    private static final BigDecimal DRINK_PRICE = new BigDecimal("35.00");
    private static final BigDecimal SEED_BALANCE = new BigDecimal("1000.00");

    @Autowired private GroupOrderService groupOrderService;
    @Autowired private GroupOrderRepository groupOrderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private ProductTemplateRepository productTemplateRepository;
    @Autowired private TransactionRecordRepository transactionRecordRepository;
    @Autowired private UserCouponRepository userCouponRepository;

    private Long hostId;
    private Long memberId;
    private Long groupOrderId;
    private String shareToken;

    @BeforeEach
    void setUp() {
        Store store = storeRepository.findAll().stream().findFirst().orElse(null);
        ProductTemplate product = productTemplateRepository.findAll().stream().findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(store != null && product != null,
                "需要示範資料（門市與商品）才能測，略過");

        hostId = newCustomer("併發測試團長", SEED_BALANCE).getId();
        User member = newCustomer("併發測試團員", SEED_BALANCE);
        memberId = member.getId();

        GroupOrder go = new GroupOrder();
        go.setInitiator(userRepository.findById(hostId).orElseThrow());
        go.setStore(store);
        go.setType("GROUP");
        go.setStatus("OPEN");
        go.setShareToken("CT" + String.format("%014d", System.nanoTime() % 100000000000000L));
        go.setTotalAmount(DRINK_PRICE);
        go = groupOrderRepository.save(go);
        groupOrderId = go.getId();
        shareToken = go.getShareToken();

        // 團員在團裡放一杯 $35，未付款
        OrderItem item = new OrderItem();
        item.setGroupOrder(go);
        item.setUser(member);
        item.setProduct(product);
        item.setProductNameSnapshot(product.getName());
        item.setUnitPriceSnapshot(DRINK_PRICE);
        item.setFinalPrice(DRINK_PRICE);
        item.setQty(1);
        item.setPaymentStatus("UNPAID");
        item.setPaymentType("WALLET");
        orderItemRepository.save(item);
    }

    @AfterEach
    void cleanUp() {
        orderItemRepository.deleteAll(orderItemRepository.findByGroupOrderId(groupOrderId));
        groupOrderRepository.deleteById(groupOrderId);
        for (Long id : List.of(memberId, hostId)) {
            transactionRecordRepository.deleteAll(
                    transactionRecordRepository.findByUserIdOrderByCreatedAtDesc(id));
            userRepository.deleteById(id);
        }
    }

    private User newCustomer(String name, BigDecimal balance) {
        User u = new User();
        u.setName(name);
        u.setPhone("09" + String.format("%08d", System.nanoTime() % 100000000L));
        u.setRole("CUSTOMER");
        u.setBalance(balance);
        return userRepository.save(u);
    }

    @Test
    @DisplayName("團員重複送出結帳：同一杯只能扣一次，且帳本與餘額必須相符")
    void concurrentMemberCheckoutChargesOnce() throws Exception {
        int ok = runConcurrently(THREADS, () ->
                groupOrderService.getMemberUnpaidTotalAndMarkPaid(shareToken, memberId, "WALLET", null));

        BigDecimal balance = userRepository.findById(memberId).orElseThrow().getBalance();
        BigDecimal charged = SEED_BALANCE.subtract(balance);
        BigDecimal ledger = transactionRecordRepository.findByUserIdOrderByCreatedAtDesc(memberId)
                .stream().map(r -> r.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(1, ok,
                "同一批未付款品項只能被結帳一次，實際成功 " + ok + " 次（重複扣款）");
        assertEquals(0, DRINK_PRICE.compareTo(charged),
                "應該只扣一次 " + DRINK_PRICE + "，實際扣了 " + charged);
        assertEquals(0, charged.negate().compareTo(ledger),
                "帳本與餘額不一致：餘額變動 " + charged.negate() + "、帳本合計 " + ledger + "（無法對帳）");
    }

    @Test
    @DisplayName("結帳後品項狀態必須是 PAID，且不再有未付款品項")
    void itemsAreMarkedPaidExactlyOnce() throws Exception {
        runConcurrently(THREADS, () ->
                groupOrderService.getMemberUnpaidTotalAndMarkPaid(shareToken, memberId, "WALLET", null));

        List<OrderItem> items = orderItemRepository.findByGroupOrderId(groupOrderId);
        assertEquals(1, items.size(), "不應該憑空多出品項");
        OrderItem item = items.get(0);
        assertNotNull(item.getPaymentStatus());
        assertEquals("PAID", item.getPaymentStatus().toUpperCase(), "品項未被標記為已付款");
    }

    @Test
    @DisplayName("團長重複送出結帳：只能成立一張訂單，團長也只能被扣一次")
    void concurrentHostCheckoutChargesOnce() throws Exception {
        int ok = runConcurrently(THREADS, () ->
                groupOrderService.checkout(shareToken, hostId, null, "WALLET", "", ""));

        BigDecimal balance = userRepository.findById(hostId).orElseThrow().getBalance();
        BigDecimal charged = SEED_BALANCE.subtract(balance);
        BigDecimal ledger = transactionRecordRepository.findByUserIdOrderByCreatedAtDesc(hostId)
                .stream().map(r -> r.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(1, ok, "同一張揪團只能結帳一次，實際成功 " + ok + " 次（重複扣款）");
        assertEquals(0, DRINK_PRICE.compareTo(charged),
                "團長應該只被扣一次 " + DRINK_PRICE + "，實際扣了 " + charged);
        assertEquals(0, charged.negate().compareTo(ledger),
                "帳本與餘額不一致：餘額變動 " + charged.negate() + "、帳本 " + ledger);
        assertEquals("SUBMITTED",
                groupOrderRepository.findById(groupOrderId).orElseThrow().getStatus(),
                "結帳後狀態應為 SUBMITTED");
    }

    @Test
    @DisplayName("重複取消揪團：退款只能發生一次")
    void concurrentCancellationRefundsOnce() throws Exception {
        // 情境：團員已付款、團長已結帳（escrow 有金額），此時重複觸發取消
        OrderItem item = orderItemRepository.findByGroupOrderId(groupOrderId).get(0);
        item.setPaymentStatus("PAID");
        orderItemRepository.save(item);

        GroupOrder go = groupOrderRepository.findById(groupOrderId).orElseThrow();
        go.setStatus("SUBMITTED");
        go.setSubmittedAt(LocalDateTime.now());
        go.setEscrowAmount(DRINK_PRICE);
        groupOrderRepository.save(go);

        BigDecimal memberBefore = userRepository.findById(memberId).orElseThrow().getBalance();
        BigDecimal hostBefore = userRepository.findById(hostId).orElseThrow().getBalance();

        runConcurrently(THREADS, () -> groupOrderService.handleGroupOrderCancellation(groupOrderId));

        BigDecimal memberRefund = userRepository.findById(memberId).orElseThrow()
                .getBalance().subtract(memberBefore);
        BigDecimal hostRefund = userRepository.findById(hostId).orElseThrow()
                .getBalance().subtract(hostBefore);

        assertEquals(0, DRINK_PRICE.compareTo(memberRefund),
                "團員的品項退款應只發生一次 " + DRINK_PRICE + "，實際退了 " + memberRefund);
        assertEquals(0, DRINK_PRICE.compareTo(hostRefund),
                "團長的 escrow 退款應只發生一次 " + DRINK_PRICE + "，實際退了 " + hostRefund);
        assertEquals("CANCELLED",
                groupOrderRepository.findById(groupOrderId).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("重複補款給團長：團員只能被扣一次，團長也只能收到一次")
    void concurrentRepayChargesOnce() throws Exception {
        // 補款只在訂單送出後可用
        GroupOrder go = groupOrderRepository.findById(groupOrderId).orElseThrow();
        go.setStatus("SUBMITTED");
        groupOrderRepository.save(go);

        BigDecimal memberBefore = userRepository.findById(memberId).orElseThrow().getBalance();
        BigDecimal hostBefore = userRepository.findById(hostId).orElseThrow().getBalance();

        runConcurrently(THREADS, () -> {
            groupOrderService.repayToHost(shareToken, memberId);
            return null;
        });

        BigDecimal memberDelta = memberBefore.subtract(
                userRepository.findById(memberId).orElseThrow().getBalance());
        BigDecimal hostDelta = userRepository.findById(hostId).orElseThrow()
                .getBalance().subtract(hostBefore);

        assertEquals(0, DRINK_PRICE.compareTo(memberDelta),
                "團員應只被扣一次 " + DRINK_PRICE + "，實際扣了 " + memberDelta);
        assertEquals(0, DRINK_PRICE.compareTo(hostDelta),
                "團長應只收到一次 " + DRINK_PRICE + "，實際收到 " + hostDelta);
    }

    @Test
    @DisplayName("同一張優惠券併發套用到兩個品項：只能被使用一次")
    void couponCannotBeUsedTwice() throws Exception {
        Store store = storeRepository.findAll().stream().findFirst().orElseThrow();
        ProductTemplate product = productTemplateRepository.findAll().stream().findFirst().orElseThrow();
        User member = userRepository.findById(memberId).orElseThrow();

        // 團員再放一杯，湊成兩個可套券的品項
        OrderItem second = new OrderItem();
        second.setGroupOrder(groupOrderRepository.findById(groupOrderId).orElseThrow());
        second.setUser(member);
        second.setProduct(product);
        second.setProductNameSnapshot(product.getName());
        second.setUnitPriceSnapshot(DRINK_PRICE);
        second.setFinalPrice(DRINK_PRICE);
        second.setQty(1);
        second.setPaymentStatus("UNPAID");
        second.setPaymentType("WALLET");
        orderItemRepository.save(second);

        UserCoupon coupon = new UserCoupon();
        coupon.setUser(member);
        coupon.setBrand(store.getBrand());
        coupon.setCouponType("ADMIN_GIFT");
        coupon.setDiscountAmount(new BigDecimal("5.00"));
        coupon.setStatus("unused");
        Long couponId = userCouponRepository.save(coupon).getId();

        List<OrderItem> items = orderItemRepository.findByGroupOrderId(groupOrderId);
        assertEquals(2, items.size());

        // 兩個品項同時套用同一張券
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (OrderItem it : items) {
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    groupOrderService.applyCouponToItem(shareToken, it.getId(), memberId, couponId);
                } catch (Exception expected) {
                    // 第二個應該被擋下
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        long discounted = orderItemRepository.findByGroupOrderId(groupOrderId).stream()
                .filter(i -> i.getCouponId() != null && couponId.equals(i.getCouponId()))
                .count();

        userCouponRepository.deleteById(couponId);

        assertEquals(1, discounted,
                "同一張券被套用在 " + discounted + " 個品項上（一張券只能折一次）");
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
                    startGate.await();
                    try {
                        action.call();
                        success.incrementAndGet();
                    } catch (Exception expected) {
                        // 「已經沒有未付款品項」是預期中的結果，不計入成功
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        return success.get();
    }
}
