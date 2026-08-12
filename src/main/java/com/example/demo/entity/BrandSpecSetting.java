package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Table(name = "brand_spec_setting")
@Data
public class BrandSpecSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "master_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private SpecMaster master;

    @Column(name = "spec_type", length = 20)
    private String specType;

    @Column(name = "custom_name", length = 50)
    private String customName;

    @Column(name = "is_enabled")
    private Boolean isEnabled = false;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;
}
