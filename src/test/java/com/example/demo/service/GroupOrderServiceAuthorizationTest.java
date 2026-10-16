package com.example.demo.service;

import com.example.demo.dto.GroupOrderDTO;
import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.Brand;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderItemToppingRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupOrderServiceAuthorizationTest {
    @Mock private GroupOrderRepository groupOrderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderItemToppingRepository orderItemToppingRepository;
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
        // joinGroup 以列鎖讀取（與加點、結帳互斥）：stub 必須對準同一個方法，否則查無揪團會假性以 404 通過
        when(groupOrderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(groupOrder(10L, 3L)));

        CustomException ex = assertThrows(CustomException.class,
                () -> groupOrderService.joinGroup(4L, 10L, Map.of("items", java.util.List.of())));
        assertEquals("403", ex.getCode());
        assertEquals("邀請憑證無效", ex.getMsg());
    }

    @Test
    void legacyJoinRejectsProductsFromAnotherBrand() {
        GroupOrder order = groupOrder(10L, 3L);
        User member = new User();
        member.setId(4L);
        when(groupOrderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(order));
        when(userRepository.findById(4L)).thenReturn(Optional.of(member));

        Brand otherBrand = new Brand();
        otherBrand.setId(2L);
        ProductTemplate product = new ProductTemplate();
        product.setId(20L);
        product.setBrand(otherBrand);
        when(productTemplateRepository.findById(20L)).thenReturn(Optional.of(product));
        Map<String, Object> item = Map.of("productId", 20L);
        Map<String, Object> request = Map.of("shareToken", "TOKEN123", "items", List.of(item));

        CustomException ex = assertThrows(CustomException.class, () -> groupOrderService.joinGroup(4L, 10L, request));
        assertEquals("400", ex.getCode());
        assertEquals("商品不屬於該分店品牌", ex.getMsg());
    }

    @Test
    void legacyJoinRejectsProductsDisabledAtTheSelectedStore() {
        GroupOrder order = groupOrder(10L, 3L);
        User member = new User();
        member.setId(4L);
        when(groupOrderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(order));
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
        CustomException ex = assertThrows(CustomException.class, () -> groupOrderService.joinGroup(4L, 10L, request));
        assertEquals("400", ex.getCode());
        assertEquals("此商品目前未在該分店供應", ex.getMsg());
    }

    // N12（2026-10-13 團長定案）：團內資料僅供成員檢視，如需檢視需入團。
    // 成員 = 團長，或已在團內有品項的團員（與 requireGroupParticipant 同一定義）。

    @Test
    void anonymousShareLinkViewerSeesOnlyTheStoreAndStatus() {
        when(groupOrderRepository.findByShareToken("TOKEN123")).thenReturn(Optional.of(groupOrderWithTeamData()));

        assertOutsiderView(groupOrderService.getGroupOrderDTOByToken("TOKEN123", null));
    }

    @Test
    void loggedInNonMemberSeesOnlyTheStoreAndStatusEvenWithTheShareToken() {
        when(groupOrderRepository.findByShareToken("TOKEN123")).thenReturn(Optional.of(groupOrderWithTeamData()));
        when(orderItemRepository.existsByGroupOrderIdAndUserId(10L, 4L)).thenReturn(false);

        assertOutsiderView(groupOrderService.getGroupOrderDTOByToken("TOKEN123", 4L));
    }

    @Test
    void existingMemberSeesTheFullGroupViewThroughTheShareToken() {
        when(groupOrderRepository.findByShareToken("TOKEN123")).thenReturn(Optional.of(groupOrderWithTeamData()));
        when(orderItemRepository.existsByGroupOrderIdAndUserId(10L, 5L)).thenReturn(true);

        GroupOrderDTO dto = groupOrderService.getGroupOrderDTOByToken("TOKEN123", 5L);

        assertEquals("TOKEN123", dto.getShareToken());
        assertEquals("外送地址", dto.getAddress());
        assertEquals("備註", dto.getNote());
        assertEquals(new java.math.BigDecimal("25.00"), dto.getTotalAmount());
    }

    @Test
    void initiatorSeesTheFullGroupViewEvenBeforeOrderingAnything() {
        when(groupOrderRepository.findByShareToken("TOKEN123")).thenReturn(Optional.of(groupOrderWithTeamData()));

        GroupOrderDTO dto = groupOrderService.getGroupOrderDTOByToken("TOKEN123", 3L);

        assertEquals("TOKEN123", dto.getShareToken());
        assertEquals("外送地址", dto.getAddress());
    }

    private void assertOutsiderView(GroupOrderDTO dto) {
        assertEquals("商店", dto.getStoreName());
        assertEquals("OPEN", dto.getStatus());
        assertTrue(dto.getItems().isEmpty());
        assertNull(dto.getShareToken());
        assertNull(dto.getOrderNo());
        assertNull(dto.getInitiatorId());
        assertNull(dto.getInitiatorName());
        assertNull(dto.getTotalAmount());
        assertNull(dto.getEscrowAmount());
        assertEquals("", dto.getAddress());
        assertEquals("", dto.getNote());
    }

    private GroupOrder groupOrderWithTeamData() {
        GroupOrder order = groupOrder(10L, 3L);
        order.setOrderNo("ORD0001");
        order.setAddress("外送地址");
        order.setNote("備註");
        order.setTotalAmount(new java.math.BigDecimal("25.00"));
        order.setEscrowAmount(new java.math.BigDecimal("10.00"));
        return order;
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
