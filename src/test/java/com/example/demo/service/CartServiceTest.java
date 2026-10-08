package com.example.demo.service;

import com.example.demo.entity.CartItem;
import com.example.demo.repository.CartItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {
    @Mock private CartItemRepository cartItemRepository;
    @InjectMocks private CartService cartService;

    @Test
    void cartSummaryMultipliesTheUnitPriceByQuantity() {
        CartItem item = new CartItem();
        item.setFinalPrice(new BigDecimal("17.00"));
        item.setQuantity(3);
        when(cartItemRepository.findByUserId(9L)).thenReturn(List.of(item));

        Map<String, Object> summary = cartService.getCartSummary(9L);

        assertEquals(new BigDecimal("51.00"), summary.get("totalAmount"));
    }
}
