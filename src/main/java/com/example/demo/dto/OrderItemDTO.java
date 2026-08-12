package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemDTO {
    @Builder.Default
    private List<Long> idList = new ArrayList<>(); // 所有合併在一起的 OrderItem ID
    private int qty; // 數量
    @Builder.Default
    private List<String> userNames = new ArrayList<>(); // 購買此規格的使用者名稱列表
    
    // Support per-user visibility in cart overview
    private Long userId;
    private String userName;

    private Long productId;
    private String productName;
    private String sugar;
    private String ice;
    private String size;
    private BigDecimal unitPrice;
    private BigDecimal finalPrice; // 單一品項的最終價格 (單價+配料-個人折扣)
    private BigDecimal totalGroupPrice; // 總金額 (finalPrice * qty)
    private BigDecimal totalGroupDiscount; // 總折扣金額
    
    @Builder.Default
    private List<OrderItemToppingDTO> toppings = new ArrayList<>();
    
    
    private String paymentStatus; // WAITING_SUBMIT / PAID / etc.
    
    private Long couponId;
    private BigDecimal discountAmount;
    private String imageUrl;
}
