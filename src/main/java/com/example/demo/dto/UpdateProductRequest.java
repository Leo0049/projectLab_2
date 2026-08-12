package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "更新飲品請求（欄位皆選填）")
public class UpdateProductRequest {

    @Schema(description = "所屬分類 ID")
    private Long categoryId;

    @Schema(description = "飲品名稱")
    private String name;

    @Schema(description = "基礎售價（無容量規格時使用）")
    private BigDecimal basePrice;

    @Schema(description = "容量規格定價列表（每個 SIZE 規格的 brandSpecId 與售價）")
    private List<SpecPriceEntry> specPrices;

    @Schema(description = "最多加料數")
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
