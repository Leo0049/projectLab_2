package com.example.demo.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateCategoryRequest {
    private List<String> names; // 批量分類名稱
    private BigDecimal northOffset = BigDecimal.ZERO;   // 北部加價
    private BigDecimal centralOffset = BigDecimal.ZERO; // 中部加價
    private BigDecimal southOffset = BigDecimal.ZERO;   // 南部加價
}