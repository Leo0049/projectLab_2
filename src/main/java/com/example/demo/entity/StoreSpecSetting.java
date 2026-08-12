package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Table(name = "store_spec_settings")
@Data
public class StoreSpecSetting {

    @EmbeddedId
    private StoreSpecSettingId id = new StoreSpecSettingId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("storeId")
    @JoinColumn(name = "store_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("brandSpecId")
    @JoinColumn(name = "brand_spec_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private BrandSpecSetting brandSpec;

    @Column(name = "is_enabled")
    private Boolean isEnabled = true;
}
