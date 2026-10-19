package com.example.demo.service;

import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.User;
import com.example.demo.entity.UserCoupon;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.UserCouponRepository;
import com.example.demo.service.wallet.TxType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 整單取消（含店家拒單與逾時自動拒單）的退款與優惠券還原（N-32，2026-10-19）。
 *
 * <p>退款金額只能來自 escrowAmount（結帳時實際從錢包扣走的金額）與 PAID 品項；
 * WAITING_SUBMIT 的款項已併入 escrowAmount，不可再逐品項退一次。
 */
@ExtendWith(MockitoExtension.class)
class GroupOrderServiceCancelRefundTest {

    @Mock
    private GroupOrderRepository groupOrderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private TransactionRecordService transactionRecordService;
    @Mock
    private UserCouponRepository userCouponRepository;
    @InjectMocks
    private GroupOrderService groupOrderService;

    @Test
    @DisplayName("個人訂單（錢包）取消：只退一次整單代墊金，品項不再逐筆退款（原本一杯 $35 會退 $70）")
    void soloWalletCancelRefundsTheEscrowOnceAndNotTheItemsAgain() {
        User user = newUser(7L);
        GroupOrder order = newOrder(20L, "SOLO", "SUBMITTED", user, new BigDecimal("35.00"));
        OrderItem item = newItem(user, "WAITING_SUBMIT", "35.00", null);
        when(groupOrderRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByGroupOrderIdForUpdate(20L)).thenReturn(List.of(item));

        assertTrue(groupOrderService.handleGroupOrderCancellation(20L));

        verify(transactionRecordService).updateStoreCredit(eq(7L), eq(new BigDecimal("35.00")),
                eq(TxType.REFUND), anyString(), any());
        verifyNoMoreInteractions(transactionRecordService);
        assertEquals("CANCELLED", item.getPaymentStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(order.getEscrowAmount()));
        assertEquals("CANCELLED", order.getStatus());
    }

    @Test
    @DisplayName("券已全額折抵（沒有扣款、代墊金為 0）時取消，不得憑品項金額退款")
    void fullyCouponedWalletOrderRefundsNothing() {
        User user = newUser(7L);
        GroupOrder order = newOrder(21L, "SOLO", "SUBMITTED", user, BigDecimal.ZERO);
        OrderItem item = newItem(user, "WAITING_SUBMIT", "35.00", null);
        when(groupOrderRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByGroupOrderIdForUpdate(21L)).thenReturn(List.of(item));

        assertTrue(groupOrderService.handleGroupOrderCancellation(21L));

        verifyNoInteractions(transactionRecordService);
    }

    @Test
    @DisplayName("個人訂單（placeOrder 路徑，品項已 PAID）取消：仍然只退一次，退回錢包")
    void soloPaidItemsStillRefundOnceToTheWallet() {
        User user = newUser(7L);
        GroupOrder order = newOrder(22L, "SOLO", "SUBMITTED", user, BigDecimal.ZERO);
        OrderItem item = newItem(user, "PAID", "35.00", null);
        when(groupOrderRepository.findByIdForUpdate(22L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByGroupOrderIdForUpdate(22L)).thenReturn(List.of(item));

        assertTrue(groupOrderService.handleGroupOrderCancellation(22L));

        verify(transactionRecordService).updateStoreCredit(eq(7L), eq(new BigDecimal("35.00")),
                eq(TxType.REFUND), anyString(), any());
        verifyNoMoreInteractions(transactionRecordService);
        assertEquals("REFUNDED", item.getPaymentStatus());
    }

    @Test
    @DisplayName("揪團取消：團員未付款（ESCROWED）與團長錢包品項的券都要還原，即使這些品項沒有退款")
    void groupCancelRestoresCouponsOfUnpaidItems() {
        User host = newUser(3L);
        User member = newUser(4L);
        GroupOrder order = newOrder(30L, "GROUP", "SUBMITTED", host, BigDecimal.ZERO);
        OrderItem memberItem = newItem(member, "ESCROWED", "40.00", 99L);
        OrderItem hostItem = newItem(host, "WAITING_SUBMIT", "35.00", 88L);
        UserCoupon memberCoupon = newCoupon(99L, member, "used");
        UserCoupon hostCoupon = newCoupon(88L, host, "used");
        when(groupOrderRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByGroupOrderIdForUpdate(30L)).thenReturn(List.of(memberItem, hostItem));
        when(userCouponRepository.findById(99L)).thenReturn(Optional.of(memberCoupon));
        when(userCouponRepository.findById(88L)).thenReturn(Optional.of(hostCoupon));

        assertTrue(groupOrderService.handleGroupOrderCancellation(30L));

        assertEquals("unused", memberCoupon.getStatus());
        assertEquals("unused", hostCoupon.getStatus());
        // 代墊金為 0：沒有任何錢包異動
        verifyNoInteractions(transactionRecordService);
    }

    @Test
    @DisplayName("店家已接單（PREPARING）的揪團不會被取消，也不產生退款")
    void preparingGroupIsNotCancelledAndRefundsNothing() {
        User host = newUser(3L);
        GroupOrder order = newOrder(31L, "GROUP", "PREPARING", host, new BigDecimal("35.00"));
        when(groupOrderRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(order));

        assertFalse(groupOrderService.handleGroupOrderCancellation(31L));

        verifyNoInteractions(transactionRecordService);
        verifyNoInteractions(orderItemRepository);
        assertEquals("PREPARING", order.getStatus());
    }

    private static User newUser(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static GroupOrder newOrder(long id, String type, String status, User initiator, BigDecimal escrow) {
        GroupOrder go = new GroupOrder();
        go.setId(id);
        go.setType(type);
        go.setStatus(status);
        go.setInitiator(initiator);
        go.setEscrowAmount(escrow);
        return go;
    }

    private static OrderItem newItem(User owner, String paymentStatus, String finalPrice, Long couponId) {
        OrderItem item = new OrderItem();
        item.setUser(owner);
        item.setPaymentStatus(paymentStatus);
        item.setFinalPrice(new BigDecimal(finalPrice));
        item.setCouponId(couponId);
        item.setProductNameSnapshot("飲品");
        return item;
    }

    private static UserCoupon newCoupon(long id, User owner, String status) {
        UserCoupon coupon = new UserCoupon();
        coupon.setId(id);
        coupon.setUser(owner);
        coupon.setStatus(status);
        return coupon;
    }
}
