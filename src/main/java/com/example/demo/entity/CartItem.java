package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cart_items")
@Data
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    @ToString.Exclude
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @ToString.Exclude
    private ProductTemplate product;

    @Column(name = "sugar_snapshot", length = 20)
    private String sugarSnapshot; // 甜度快照

    @Column(name = "ice_snapshot", length = 20)
    private String iceSnapshot; // 冰塊快照

    @Column(name = "size_snapshot", length = 10)
    private String sizeSnapshot; // 杯型 M/L

    @Column(name = "topping_names", length = 255)
    private String toppingNames; // 逗號分隔，如 "珍珠,椰果"

    @Column(name = "topping_extra", precision = 10, scale = 2)
    private BigDecimal toppingExtra = BigDecimal.ZERO; // 配料加總

    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice; // 飲品單價

    @Column(name = "final_price", precision = 10, scale = 2)
    private BigDecimal finalPrice; // 飲品 + 配料

    @Column(name = "qty", nullable = false)
    private Integer quantity = 1;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
