package com.example.demo.service;

import com.example.demo.dto.PlaceOrderRequest;
import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 停用期間的付款守衛（stage 6，ADR-0012 決策 5）：個人結帳、單人下單、團員付款、補款與建立揪團，
 * 遇到停用的分店或品牌都必須回 400，而且在鎖定未付款品項或扣款之前就拒絕。
 *
 * <p>取消與退款刻意不檢查，不在這裡測。不載入 Spring context。
 */
@ExtendWith(MockitoExtension.class)
class StoreSuspensionPaymentGuardTest {

    /** {@code OrderService} 的個人結帳與單人下單訊息。 */
    private static final String ORDER_REFUSAL = "Store is not accepting new orders";
    /** {@code GroupOrderService} 的揪團、付款與補款訊息。 */
    private static final String GROUP_REFUSAL = "分店目前不接受新訂單";

    @Mock private RedisLockService redisLockService;
    @Mock private UserRepository userRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private GroupOrderRepository groupOrderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @InjectMocks private OrderService orderService;
    @InjectMocks private GroupOrderService groupOrderService;

    @Test
    @DisplayName("停用的分店不接受個人結帳")
    void individualCheckoutRefusesASuspendedStore() {
        when(redisLockService.acquireLock(anyString(), anyLong())).thenReturn("token");
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store(10L, true)));

        CustomException ex = assertThrows(CustomException.class, () -> checkout(10L));

        assertEquals("400", ex.getCode());
        assertEquals(ORDER_REFUSAL, ex.getMsg());
    }

    @Test
    @DisplayName("品牌停用時，底下分店也不接受個人結帳")
    void individualCheckoutRefusesAStoreUnderASuspendedBrand() {
        when(redisLockService.acquireLock(anyString(), anyLong())).thenReturn("token");
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store(10L, false)));
        when(storeRepository.isBrandSuspended(10L)).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class, () -> checkout(10L));

        assertEquals("400", ex.getCode());
        assertEquals(ORDER_REFUSAL, ex.getMsg());
    }

    @Test
    @DisplayName("停用的分店不接受單人下單")
    void soloOrderRefusesASuspendedStore() {
        User user = new User();
        user.setId(1L);
        PlaceOrderRequest req = new PlaceOrderRequest();
        req.setStoreId(10L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store(10L, true)));

        CustomException ex = assertThrows(CustomException.class, () -> orderService.placeOrder(1L, req));

        assertEquals("400", ex.getCode());
        assertEquals(ORDER_REFUSAL, ex.getMsg());
    }

    @Test
    @DisplayName("停用的分店不接受團員付款，未付款品項不會被鎖定、扣款或標記")
    void memberPaymentRefusesASuspendedStore() {
        when(groupOrderRepository.findByShareToken("TOKEN"))
                .thenReturn(Optional.of(group(store(7L, true), 3L, "OPEN")));

        CustomException ex = assertThrows(CustomException.class,
                () -> groupOrderService.getMemberUnpaidTotalAndMarkPaid("TOKEN", 4L, "WALLET", null));

        assertEquals("400", ex.getCode());
        assertEquals(GROUP_REFUSAL, ex.getMsg());
        verify(orderItemRepository, never()).findByGroupOrderAndUserAndStatusForUpdate(any(), any(), any());
    }

    @Test
    @DisplayName("停用的分店不接受補款，未付款品項不會被鎖定或標記")
    void repaymentRefusesASuspendedStore() {
        when(groupOrderRepository.findByShareToken("TOKEN"))
                .thenReturn(Optional.of(group(store(7L, true), 3L, "SUBMITTED")));

        CustomException ex = assertThrows(CustomException.class,
                () -> groupOrderService.repayToHost("TOKEN", 4L));

        assertEquals("400", ex.getCode());
        assertEquals(GROUP_REFUSAL, ex.getMsg());
        verify(orderItemRepository, never()).findByGroupOrderAndUserAndStatusForUpdate(any(), any(), any());
    }

    @Test
    @DisplayName("停用的分店不能開新的揪團")
    void groupCreationRefusesASuspendedStore() {
        User host = new User();
        host.setId(3L);
        when(redisLockService.acquireLock(anyString(), anyLong())).thenReturn("token");
        when(userRepository.findById(3L)).thenReturn(Optional.of(host));
        when(storeRepository.findById(7L)).thenReturn(Optional.of(store(7L, true)));

        CustomException ex = assertThrows(CustomException.class,
                () -> groupOrderService.createGroupOrderV2(3L, 7L));

        assertEquals("400", ex.getCode());
        assertEquals(GROUP_REFUSAL, ex.getMsg());
    }

    private void checkout(Long storeId) {
        orderService.createOrder(1L, storeId, BigDecimal.ONE, null,
                List.of(new OrderItem()), "WALLET", null, null);
    }

    private static Store store(Long id, boolean suspended) {
        Store s = new Store();
        s.setId(id);
        s.setStatus("active");
        s.setSuspended(suspended);
        return s;
    }

    private static GroupOrder group(Store store, Long initiatorId, String status) {
        User initiator = new User();
        initiator.setId(initiatorId);
        GroupOrder go = new GroupOrder();
        go.setId(50L);
        go.setShareToken("TOKEN");
        go.setInitiator(initiator);
        go.setStore(store);
        go.setStatus(status);
        return go;
    }
}
