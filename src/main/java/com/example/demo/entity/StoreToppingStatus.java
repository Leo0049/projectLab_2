package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Table(name = "store_topping_settings")
@Data
public class StoreToppingStatus {

    @EmbeddedId
    private StoreToppingStatusId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("storeId")
    @JoinColumn(name = "store_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("brandToppingId")
    @JoinColumn(name = "brand_topping_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private BrandToppingSetting brandTopping;

    @Column(name = "is_out_of_stock")
    private Boolean isOutOfStock = false;
}
