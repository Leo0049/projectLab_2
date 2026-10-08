package com.example.demo.service;

import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.Brand;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.StoreProductStatusRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.entity.StoreProductStatus;
import com.example.demo.entity.StoreProductStatusId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupOrderServiceAuthorizationTest {
    @Mock private GroupOrderRepository groupOrderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductTemplateRepository productTemplateRepository;
    @Mock private StoreProductStatusRepository storeProductStatusRepository;
    @InjectMocks private GroupOrderService groupOrderService;

    @Test
    void onlyTheInitiatorCanRetrieveTheShareToken() {
        GroupOrder order = groupOrder(10L, 3L);
        when(groupOrderRepository.findById(10L)).thenReturn(Optional.of(order));

        assertThrows(CustomException.class, () -> groupOrderService.getShareInfo(4L, 10L));
        assertEquals("TOKEN123", groupOrderService.getShareInfo(3L, 10L).get("shareToken"));
    }

    @Test
    void nonMembersCannotReadNumericGroupDetailsOrSummaries() {
        GroupOrder order = groupOrder(10L, 3L);
        when(groupOrderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderItemRepository.existsByGroupOrderIdAndUserId(10L, 4L)).thenReturn(false);

        assertThrows(CustomException.class, () -> groupOrderService.getGroupDetail(4L, 10L));
        assertThrows(CustomException.class, () -> groupOrderService.getGroupSummary(4L, 10L));
        assertTrue(groupOrderService.getGroupOrderDTOByOrderId(10L, 4L).isEmpty());
    }

    @Test
    void ownerAndExistingMemberCanReadGroupDetailsAndSummary() {
        GroupOrder order = groupOrder(10L, 3L);
        order.setTotalAmount(new java.math.BigDecimal("25.00"));
        when(groupOrderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderItemRepository.existsByGroupOrderIdAndUserId(10L, 5L)).thenReturn(true);
        when(orderItemRepository.findByGroupOrderId(10L)).thenReturn(List.of());

        assertEquals("商店", groupOrderService.getGroupDetail(3L, 10L).get("storeName"));
        assertEquals("商店", groupOrderService.getGroupDetail(5L, 10L).get("storeName"));
        assertEquals(new java.math.BigDecimal("25.00"), groupOrderService.getGroupSummary(3L, 10L).get("totalAmount"));
        assertEquals(new java.math.BigDecimal("25.00"), groupOrderService.getGroupSummary(5L, 10L).get("totalAmount"));
    }

    @Test
    void legacyJoinRequiresTheMatchingShareToken() {
        when(groupOrderRepository.findById(10L)).thenReturn(Optional.of(groupOrder(10L, 3L)));

        assertThrows(CustomException.class,
                () -> groupOrderService.joinGroup(4L, 10L, Map.of("items", java.util.List.of())));
    }

    @Test
    void legacyJoinRejectsProductsFromAnotherBrand() {
        GroupOrder order = groupOrder(10L, 3L);
        User member = new User();
        member.setId(4L);
        when(groupOrderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(userRepository.findById(4L)).thenReturn(Optional.of(member));

        Brand otherBrand = new Brand();
        otherBrand.setId(2L);
        ProductTemplate product = new ProductTemplate();
        product.setId(20L);
        product.setBrand(otherBrand);
        when(productTemplateRepository.findById(20L)).thenReturn(Optional.of(product));
        Map<String, Object> item = Map.of("productId", 20L);
        Map<String, Object> request = Map.of("shareToken", "TOKEN123", "items", List.of(item));

        assertThrows(CustomException.class, () -> groupOrderService.joinGroup(4L, 10L, request));
    }

    @Test
    void legacyJoinRejectsProductsDisabledAtTheSelectedStore() {
        GroupOrder order = groupOrder(10L, 3L);
        User member = new User();
        member.setId(4L);
        when(groupOrderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(userRepository.findById(4L)).thenReturn(Optional.of(member));

        ProductTemplate product = new ProductTemplate();
        product.setId(20L);
        product.setBrand(order.getStore().getBrand());
        product.setIsEnabled(true);
        when(productTemplateRepository.findById(20L)).thenReturn(Optional.of(product));
        StoreProductStatus disabled = new StoreProductStatus();
        StoreProductStatusId disabledId = new StoreProductStatusId();
        disabledId.setStoreId(order.getStore().getId());
        disabledId.setProductId(20L);
        disabled.setId(disabledId);
        disabled.setIsEnabled(false);
        when(storeProductStatusRepository.findByStoreId(order.getStore().getId())).thenReturn(List.of(disabled));

        Map<String, Object> item = Map.of("productId", 20L);
        Map<String, Object> request = Map.of("shareToken", "TOKEN123", "items", List.of(item));
        assertThrows(CustomException.class, () -> groupOrderService.joinGroup(4L, 10L, request));
    }

    private GroupOrder groupOrder(Long id, Long initiatorId) {
        User initiator = new User();
        initiator.setId(initiatorId);
        GroupOrder order = new GroupOrder();
        order.setId(id);
        order.setInitiator(initiator);
        order.setShareToken("TOKEN123");
        order.setStatus("OPEN");
        Brand brand = new Brand();
        brand.setId(1L);
        Store store = new Store();
        store.setId(11L);
        store.setBrand(brand);
        store.setStoreName("商店");
        store.setStatus("active");
        order.setStore(store);
        return order;
    }
}
