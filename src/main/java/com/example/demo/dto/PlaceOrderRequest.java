package com.example.demo.dto;

import lombok.Data;
import java.util.List;

@Data
public class PlaceOrderRequest {
    private Long storeId;
    private String note;
    private List<OrderItemRequest> items;
}
