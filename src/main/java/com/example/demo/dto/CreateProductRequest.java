package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "新增飲品請求")
public class CreateProductRequest {

    @Schema(description = "所屬分類 ID", example = "1")
    private Long categoryId;

    @Schema(description = "飲品名稱", example = "珍珠奶茶")
    private String name;

    @Schema(description = "基礎售價（無容量規格時使用；有 specPrices 時自動取最低價）", example = "55")
    private BigDecimal basePrice;

    @Schema(description = "容量規格定價列表（每個 SIZE 規格的 brandSpecId 與售價）")
    private List<SpecPriceEntry> specPrices;

    @Schema(description = "最多加料數（預設3）", example = "3")
    private Integer maxToppings;

    @Schema(description = "圖片 URL")
    private String logoUrl;

    @Schema(description = "飲品介紹")
    private String description;

    @Schema(description = "已選規格 ID 列表（ICE + SWEETNESS，不含 SIZE）")
    private List<Long> brandSpecIds;

    @Schema(description = "已選配料 ID 列表")
    private List<Long> brandToppingIds;
}
