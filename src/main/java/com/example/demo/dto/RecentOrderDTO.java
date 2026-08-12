package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecentOrderDTO {
    private Long id;
    private Long storeId;
    private String storeName;
    private String productName;
    private BigDecimal price;
    private String logoBg;
    private String logoText;
    private String textColor;
}
