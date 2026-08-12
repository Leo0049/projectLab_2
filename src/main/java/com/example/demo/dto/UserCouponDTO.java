package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserCouponDTO {
    private Long couponId;
    private Long userCouponId;
    private Long productId;
    private String category;
    private String productName;
    private String brandName;
    private Long brandId;
    private Long categoryId;
    private BigDecimal discountAmount;
    private String finalImageUrl;
    private LocalDateTime obtainedAt;
    private LocalDateTime expiryDate;
}
