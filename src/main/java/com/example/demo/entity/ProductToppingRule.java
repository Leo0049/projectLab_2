package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Table(name = "product_topping_rule")
@Data
public class ProductToppingRule {

    @EmbeddedId
    private ProductToppingRuleId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("productId")
    @JoinColumn(name = "product_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ProductTemplate product;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("brandToppingId")
    @JoinColumn(name = "brand_topping_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private BrandToppingSetting brandTopping;
}
