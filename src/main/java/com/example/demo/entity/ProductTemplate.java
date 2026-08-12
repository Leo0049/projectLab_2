package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.math.BigDecimal;

@Entity
@Table(name = "product_templates")
@Data
public class ProductTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private MenuCategory category;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 255)
    private String description;

    /** 基礎售價：無容量規格定價時的預設價格，或所有規格中的最低價 */
    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "max_toppings")
    private Integer maxToppings = 3;

    @Column(name = "logo_url", length = 255)
    private String logoUrl;

    @Column(name = "coupon_image_url", length = 255)
    private String couponImageUrl;

    @Column(name = "is_enabled")
    private Boolean isEnabled = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
