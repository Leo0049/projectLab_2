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
    private String paymentType;     // CREDIT / CASH
    private List<String> toppingNames; // 配料名稱清單
}
