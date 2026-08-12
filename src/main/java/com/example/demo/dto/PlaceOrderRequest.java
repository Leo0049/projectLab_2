package com.example.demo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PlaceOrderRequest {

    @NotNull(message = "請指定門市")
    private Long storeId;

    @Size(max = 255, message = "備註不可超過 255 字")
    private String note;

    // @Valid 讓巢狀的 OrderItemRequest 也一起被檢查
    @Valid
    @NotEmpty(message = "訂單至少要有一項商品")
    private List<OrderItemRequest> items;
}
