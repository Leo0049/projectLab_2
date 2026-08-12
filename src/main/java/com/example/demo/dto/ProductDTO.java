package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProductDTO {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price; // matches p.price in frontend
    private String imageUrl; // matches p.imageUrl in frontend
    private String category; // matches p.category in frontend
    private Long categoryId;
    private boolean canBeIced; // 有任何含「冰」的規格選項
    private boolean canBeHot;  // 有任何含「熱」的規格選項
}
