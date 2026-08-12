package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.math.BigDecimal;

@Entity
@Table(name = "brand_topping_setting")
@Data
public class BrandToppingSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_topping_id", nullable = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ToppingMaster masterTopping; // null = 品牌自訂配料

    @Column(name = "custom_name", length = 50)
    private String customName; // null 則顯示平台名

    @Column(name = "brand_price", precision = 10, scale = 2)
    private BigDecimal brandPrice; // null 則顯示平台價

    @Column(name = "is_enabled")
    private Boolean isEnabled = false; // 預設停用，品牌手動啟用
}
