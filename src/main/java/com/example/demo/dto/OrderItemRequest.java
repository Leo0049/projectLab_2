package com.example.demo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class OrderItemRequest {

    @NotNull(message = "商品不可為空")
    private Long productId;
    private String sugarSnapshot;   // 甜度
    private String iceSnapshot;     // 冰塊
    private String sizeSnapshot;    // 尺寸 (M/L)
    private String paymentType;     // WALLET / CASH；此舊 API 不支援信用卡線上付款
    private List<String> toppingNames; // 配料名稱清單
}
