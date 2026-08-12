package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "spec_master")
@Data
public class SpecMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String type; // SWEETNESS / ICE / SIZE

    @Column(nullable = false, length = 50)
    private String name; // 平台預設名（如：微糖、去冰、大杯）
}
