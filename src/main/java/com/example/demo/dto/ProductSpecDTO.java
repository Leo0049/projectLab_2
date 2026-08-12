package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductSpecDTO {
    private Long brandSpecId;
    private String specName;
    private String type; // SIZE, SWEETNESS, ICE
    private BigDecimal extraPrice;
}
