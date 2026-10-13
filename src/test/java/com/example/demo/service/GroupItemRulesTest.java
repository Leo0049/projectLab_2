package com.example.demo.service;

import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.TransactionRecordRepository;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 揪團品項與取消退款的規則（團長於 2026-10-13 定案，見 {@code GroupRefundPolicy}）。
 *
 * <p>守住三件事：
 * <ol>
 *   <li><b>品項必須屬於該揪團</b>：拿 A 團的 token 只能動 A 團的品項，B 團的團長或成員不可動 A 團的品項（N1）。</li>
 *   <li><b>送單前</b>：團員可自行取消；錢包付款退回錢包，現金不經錢包、不入帳。</li>
 *   <li><b>送單後</b>：團員不可自行取消；團長移除或整單取消時，已付款金額入團長帳號。</li>
 * </ol>
 *
 * <p>另有 N2、N3：店家接單後不可刪除揪團；已送單的揪團不可被改回開放中。
 */
@SpringBootTest
class GroupItemRulesTest {

    private static final BigDecimal DRINK = new BigDecimal("35.00");
    private static final BigDecimal SEED_BALANCE = new BigDecimal("1000.00");

    @Autowired private GroupOrderService groupOrderService;
    @Autowired private GroupOrderRepository groupOrderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private ProductTemplateRepository productTemplateRepository;
    @Autowired private TransactionRecordRepository transactionRecordRepository;

    private Store store;
    private ProductTemplate product;
    private final List<Long> groupIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    private Long hostId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        store = storeRepository.findAll().stream().findFirst().orElse(null);
        product = productTemplateRepository.findAll().stream().findFirst().orElse(null);
        assumeTrue(store != null && product != null, "需要示範資料（門市與商品）才能測，略過");
        hostId = newCustomer("規則測試團長").getId();
        memberId = newCustomer("規則測試團員").getId();
    }

    @AfterEach
    void cleanUp() {
        for (Long gid : groupIds) {
            orderItemRepository.deleteAll(orderItemRepository.findByGroupOrderId(gid));
            groupOrderRepository.deleteById(gid);
        }
        for (Long uid : userIds) {
            transactionRecordRepository.deleteAll(transactionRecordRepository.findByUserIdOrderByCreatedAtDesc(uid));
            userRepository.deleteById(uid);
        }
        groupIds.clear();
        userIds.clear();
    }

    // ── N1：品項必須屬於該揪團 ───────────────────────────────

    @Test
    @DisplayName("N1：B 團的團長或成員，拿 B 團 token 不可刪改 A 團的品項")
    void itemsOfAnotherGroupCannotBeTouchedThroughThisGroupsToken() {
        GroupOrder groupA = newGroup(hostId, "OPEN", BigDecimal.ZERO);
        OrderItem itemOfA = newItem(groupA, memberId, "UNPAID", "WALLET");

        Long otherHostId = newCustomer("另一團團長").getId();
        GroupOrder groupB = newGroup(otherHostId, "OPEN", BigDecimal.ZERO);
        String tokenB = groupB.getShareToken();

        // B 團團長：以前只看「我是 B 團團長」就能刪 A 團的品項
        CustomException hostAttempt = assertThrows(CustomException.class,
                () -> groupOrderService.removeItem(tokenB, itemOfA.getId(), otherHostId));
        assertEquals("404", hostAttempt.getCode());

        // A 團的成員，改用 B 團的 token 改數量
        CustomException editAttempt = assertThrows(CustomException.class,
                () -> groupOrderService.updateItem(tokenB, itemOfA.getId(), Map.<String, Object>of("qty", 2), memberId));
        assertEquals("404", editAttempt.getCode());

        assertTrue(orderItemRepository.findById(itemOfA.getId()).isPresent(), "A 團品項不應被刪除");
        assertEquals(1, orderItemRepository.findById(itemOfA.getId()).orElseThrow().getQty(),
                "A 團品項數量不應被改動");
    }

    // ── 送單前：團員自行取消 ─────────────────────────────────

    @Test
    @DisplayName("送單前：錢包付款的品項，團員自行取消後退回錢包")
    void memberCancelBeforeSubmitRefundsWalletPayment() {
        GroupOrder go = newGroup(hostId, "OPEN", BigDecimal.ZERO);
        OrderItem paid = newItem(go, memberId, "PAID", "WALLET");
        BigDecimal memberBefore = balanceOf(memberId);
        BigDecimal hostBefore = balanceOf(hostId);

        groupOrderService.cancelMemberItems(go.getId(), memberId);

        assertEquals(0, DRINK.compareTo(balanceOf(memberId).subtract(memberBefore)), "團員應收回 35 元");
        assertEquals(0, hostBefore.compareTo(balanceOf(hostId)), "團長不應有任何變動");
        assertTrue(orderItemRepository.findById(paid.getId()).isEmpty(), "品項應被移除");
    }

    @Test
    @DisplayName("送單前：現金付款的品項取消時不入錢包帳本（避免憑空生出餘額）")
    void memberCancelBeforeSubmitWithCashCreatesNoWalletCredit() {
        GroupOrder go = newGroup(hostId, "OPEN", BigDecimal.ZERO);
        OrderItem paidInCash = newItem(go, memberId, "PAID", "CASH");
        BigDecimal memberBefore = balanceOf(memberId);
        long ledgerBefore = ledgerCountOf(memberId);

        groupOrderService.cancelMemberItems(go.getId(), memberId);

        assertEquals(0, memberBefore.compareTo(balanceOf(memberId)), "現金付款不應增加錢包餘額");
        assertEquals(ledgerBefore, ledgerCountOf(memberId), "現金付款不應寫入錢包帳本");
        assertTrue(orderItemRepository.findById(paidInCash.getId()).isEmpty(), "品項仍應被移除");
    }

    // ── 送單後：團員不可自行取消，團長操作入團長帳號 ───────────

    @Test
    @DisplayName("送單後：團員不可自行取消並退款，品項與餘額都不動")
    void memberCannotSelfCancelAfterSubmit() {
        GroupOrder go = newGroup(hostId, "SUBMITTED", BigDecimal.ZERO);
        OrderItem paid = newItem(go, memberId, "PAID", "WALLET");
        BigDecimal memberBefore = balanceOf(memberId);

        CustomException ex = assertThrows(CustomException.class,
                () -> groupOrderService.cancelMemberItems(go.getId(), memberId));
        assertEquals("409", ex.getCode());

        assertEquals(0, memberBefore.compareTo(balanceOf(memberId)));
        assertTrue(orderItemRepository.findById(paid.getId()).isPresent(), "品項不應被移除");
    }

    @Test
    @DisplayName("送單後：團員不可用移除品項的端點自行退款")
    void memberCannotRemoveOwnItemAfterSubmit() {
        GroupOrder go = newGroup(hostId, "SUBMITTED", BigDecimal.ZERO);
        OrderItem paid = newItem(go, memberId, "PAID", "WALLET");

        CustomException ex = assertThrows(CustomException.class,
                () -> groupOrderService.removeItem(go.getShareToken(), paid.getId(), memberId));
        assertEquals("409", ex.getCode());
        assertTrue(orderItemRepository.findById(paid.getId()).isPresent());
    }

    @Test
    @DisplayName("送單後：團長移除團員已付款的品項，退款入團長帳號而非團員")
    void hostRemovalAfterSubmitRefundsPaidAmountToHost() {
        GroupOrder go = newGroup(hostId, "SUBMITTED", BigDecimal.ZERO);
        OrderItem paid = newItem(go, memberId, "PAID", "WALLET");
        BigDecimal memberBefore = balanceOf(memberId);
        BigDecimal hostBefore = balanceOf(hostId);

        groupOrderService.removeItem(go.getShareToken(), paid.getId(), hostId);

        assertEquals(0, DRINK.compareTo(balanceOf(hostId).subtract(hostBefore)), "團長應收到 35 元");
        assertEquals(0, memberBefore.compareTo(balanceOf(memberId)), "團員不應被退款");
        assertTrue(orderItemRepository.findById(paid.getId()).isEmpty());
    }

    @Test
    @DisplayName("送單後：團長移除團員代墊的品項，退團長代墊金且以 escrow 為上限")
    void hostRemovalAfterSubmitRefundsEscrowedItemUpToEscrow() {
        GroupOrder go = newGroup(hostId, "SUBMITTED", DRINK);
        OrderItem escrowed = newItem(go, memberId, "ESCROWED", "WALLET");
        BigDecimal hostBefore = balanceOf(hostId);

        groupOrderService.removeItem(go.getShareToken(), escrowed.getId(), hostId);

        assertEquals(0, DRINK.compareTo(balanceOf(hostId).subtract(hostBefore)), "團長應收回代墊的 35 元");
        assertEquals(0, BigDecimal.ZERO.compareTo(
                groupOrderRepository.findById(go.getId()).orElseThrow().getEscrowAmount()),
                "代墊金應扣到 0");
    }

    @Test
    @DisplayName("整單取消（送單後）：團員已付款的金額也入團長帳號，團長拿回整團店家款項")
    void wholeGroupCancelAfterSubmitRoutesPaidAmountToHost() {
        GroupOrder go = newGroup(hostId, "SUBMITTED", DRINK);
        go.setSubmittedAt(LocalDateTime.now());
        groupOrderRepository.save(go);
        newItem(go, memberId, "PAID", "WALLET");
        BigDecimal memberBefore = balanceOf(memberId);
        BigDecimal hostBefore = balanceOf(hostId);

        groupOrderService.handleGroupOrderCancellation(go.getId());

        // 團長應收到：團員已付 35 + 團長代墊 35 = 70（店家款項全數回到團長）
        assertEquals(0, new BigDecimal("70.00").compareTo(balanceOf(hostId).subtract(hostBefore)));
        assertEquals(0, memberBefore.compareTo(balanceOf(memberId)), "團員不應被直接退款");
    }

    // ── N2／N3：店家接單後不可刪除；送單後不可改回開放 ─────────

    @Test
    @DisplayName("N2：店家已接單（PREPARING）後，團長不可刪除揪團，也不產生退款")
    void hostCannotDeleteGroupOnceStoreAccepted() {
        GroupOrder go = newGroup(hostId, "PREPARING", DRINK);
        newItem(go, memberId, "PAID", "WALLET");
        BigDecimal memberBefore = balanceOf(memberId);
        BigDecimal hostBefore = balanceOf(hostId);

        CustomException ex = assertThrows(CustomException.class,
                () -> groupOrderService.deleteGroupOrder(go.getShareToken(), hostId));
        assertEquals("409", ex.getCode());

        assertTrue(groupOrderRepository.findById(go.getId()).isPresent(), "揪團不應被刪除");
        assertEquals(0, memberBefore.compareTo(balanceOf(memberId)));
        assertEquals(0, hostBefore.compareTo(balanceOf(hostId)));
    }

    @Test
    @DisplayName("N3：已送單的揪團不可被改回 OPEN 或 LOCKED（避免同一批品項再結帳一次）")
    void submittedGroupCannotBeReopenedOrLocked() {
        GroupOrder go = newGroup(hostId, "SUBMITTED", DRINK);

        CustomException reopen = assertThrows(CustomException.class,
                () -> groupOrderService.setStatus(go.getShareToken(), "OPEN", hostId));
        assertEquals("409", reopen.getCode());
        CustomException lock = assertThrows(CustomException.class,
                () -> groupOrderService.setStatus(go.getShareToken(), "LOCKED", hostId));
        assertEquals("409", lock.getCode());

        assertEquals("SUBMITTED", groupOrderRepository.findById(go.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("N3：開放中與截單之間可以互相切換（正常流程不受影響）")
    void openAndLockedCanBeToggledBeforeSubmit() {
        GroupOrder go = newGroup(hostId, "OPEN", BigDecimal.ZERO);

        groupOrderService.setStatus(go.getShareToken(), "LOCKED", hostId);
        assertEquals("LOCKED", groupOrderRepository.findById(go.getId()).orElseThrow().getStatus());

        groupOrderService.setStatus(go.getShareToken(), "OPEN", hostId);
        assertEquals("OPEN", groupOrderRepository.findById(go.getId()).orElseThrow().getStatus());
    }

    // ── fixtures ────────────────────────────────────────────

    private User newCustomer(String name) {
        User u = new User();
        u.setName(name);
        u.setPhone("09" + String.format("%08d", System.nanoTime() % 100000000L));
        u.setRole("CUSTOMER");
        u.setBalance(SEED_BALANCE);
        User saved = userRepository.save(u);
        userIds.add(saved.getId());
        return saved;
    }

    private GroupOrder newGroup(Long initiatorId, String status, BigDecimal escrow) {
        GroupOrder go = new GroupOrder();
        go.setInitiator(userRepository.findById(initiatorId).orElseThrow());
        go.setStore(store);
        go.setType("GROUP");
        go.setStatus(status);
        go.setEscrowAmount(escrow);
        go.setTotalAmount(DRINK);
        go.setShareToken("GR" + String.format("%014d", System.nanoTime() % 100000000000000L));
        GroupOrder saved = groupOrderRepository.save(go);
        groupIds.add(saved.getId());
        return saved;
    }

    private OrderItem newItem(GroupOrder go, Long ownerId, String paymentStatus, String paymentType) {
        OrderItem item = new OrderItem();
        item.setGroupOrder(go);
        item.setUser(userRepository.findById(ownerId).orElseThrow());
        item.setProduct(product);
        item.setProductNameSnapshot(product.getName());
        item.setUnitPriceSnapshot(DRINK);
        item.setFinalPrice(DRINK);
        item.setQty(1);
        item.setPaymentStatus(paymentStatus);
        item.setPaymentType(paymentType);
        return orderItemRepository.save(item);
    }

    private BigDecimal balanceOf(Long userId) {
        return userRepository.findById(userId).orElseThrow().getBalance();
    }

    private long ledgerCountOf(Long userId) {
        return transactionRecordRepository.findByUserIdOrderByCreatedAtDesc(userId).size();
    }
}
