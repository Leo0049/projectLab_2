package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupOrderDTO {
    private Long id;
    private String orderNo;
    private String shareToken;
    private String status;
    private String type;
    private BigDecimal totalAmount;
    private BigDecimal escrowAmount;
    private Long brandId;
    
    private Long initiatorId;
    private String initiatorName;
    
    private Long storeId;
    private String storeName;
    private String storeLogoUrl;
    
    private String address;
    private String note; // 總體備註 (結帳時填寫)
    
    private LocalDateTime createdAt;
    
    @Builder.Default
    private List<OrderItemDTO> items = new ArrayList<>();
}
