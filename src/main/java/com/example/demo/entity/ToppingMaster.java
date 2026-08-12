package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Entity
@Table(name = "topping_master")
@Data
public class ToppingMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name; // 平台預設配料名（如：珍珠、椰果）

    @Column(name = "default_price", precision = 10, scale = 2)
    private BigDecimal defaultPrice = BigDecimal.ZERO;
}
