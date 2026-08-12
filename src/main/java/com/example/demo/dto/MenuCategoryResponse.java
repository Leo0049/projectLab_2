package com.example.demo.dto;

import com.example.demo.entity.MenuCategory;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MenuCategoryResponse {

    private Long id;
    private Long brandId;
    private String brandName;
    private String name;
    private Integer sortOrder;
    private BigDecimal northOffset = BigDecimal.ZERO;
    private BigDecimal centralOffset = BigDecimal.ZERO;
    private BigDecimal southOffset = BigDecimal.ZERO;

    public static MenuCategoryResponse from(MenuCategory category) {
        MenuCategoryResponse dto = new MenuCategoryResponse();
        dto.setId(category.getId());
        dto.setBrandId(category.getBrand().getId());
        dto.setBrandName(category.getBrand().getName());
        dto.setName(category.getName());
        dto.setSortOrder(category.getSortOrder());
        return dto;
    }
}
