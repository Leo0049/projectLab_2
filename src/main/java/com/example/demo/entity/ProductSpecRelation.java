package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.math.BigDecimal;

@Entity
@Table(name = "product_spec_relations")
@Data
public class ProductSpecRelation {

    @EmbeddedId
    private ProductSpecRelationId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("productId")
    @JoinColumn(name = "product_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ProductTemplate product;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("brandSpecId")
    @JoinColumn(name = "brand_spec_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private BrandSpecSetting brandSpec;

    @Column(name = "price", precision = 10, scale = 2)
    private BigDecimal price; // 僅 SIZE 類型填入，其他規格為 NULL
}
