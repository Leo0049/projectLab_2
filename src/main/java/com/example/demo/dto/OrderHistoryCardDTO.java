package com.example.demo.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class OrderHistoryCardDTO {
    private Long orderId;
    private Long storeId;
    private String storeName;
    private String orderTime;
    private BigDecimal finalAmount;
    private String itemsSummary;
    private String status;
    private boolean isGroup;
    private String groupToken;
    private String storeImageUrl;
    private boolean ratingSubmitted;
}

