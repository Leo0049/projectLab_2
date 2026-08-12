package com.example.demo.entity;

import jakarta.persistence.Embeddable;
import lombok.Data;
import java.io.Serializable;

@Embeddable
@Data
public class ProductToppingRuleId implements Serializable {
    private Long productId;
    private Long brandToppingId;
}
