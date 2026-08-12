package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Schema(description = "容量規格定價（brandSpecId + price）")
public class SpecPriceEntry {

    @Schema(description = "品牌容量規格 ID（brand_spec_setting.id，type=SIZE）", example = "5")
    private Long brandSpecId;

    @Schema(description = "該規格的售價", example = "55")
    private BigDecimal price;
}
