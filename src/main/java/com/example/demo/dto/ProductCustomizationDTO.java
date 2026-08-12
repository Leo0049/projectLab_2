package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProductCustomizationDTO {
    private List<ProductSpecDTO> specs;
    private List<ToppingDTO> toppings;
    private java.math.BigDecimal basePrice;
    private Integer maxToppings;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ToppingDTO {
        private Long id;
        private String name;
        private java.math.BigDecimal price;
    }
}
