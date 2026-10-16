package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.math.BigDecimal;

@Entity
@Table(name = "stores",
        // 附近查詢的矩形預篩（見 PublicService／StoreRepository.findNearbyStores）。
        // 欄位順序與查詢的 BETWEEN 條件一致；實際是否被 MySQL 採用需以 EXPLAIN 確認（尚未在 CI 量測）。
        indexes = @Index(name = "idx_stores_lat_lng", columnList = "latitude, longitude"))
@Data
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Region region; // 取代原本的 zone 字串

    @Column(name = "store_name", nullable = false, length = 255)
    private String storeName;

    @Column(unique = true)
    private String account;

    @Column(name = "password_hash")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String passwordHash;

    @Column(nullable = false, length = 20)
    private String role = "STORE";

    @Column
    private String address;

    @Column(precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(precision = 11, scale = 8)
    private BigDecimal longitude;

    @Column(name = "is_delivery_available")
    private Boolean isDeliveryAvailable = true;

    @Column(name = "max_delivery_km", precision = 5, scale = 2)
    private BigDecimal maxDeliveryKm = BigDecimal.ZERO;

    @Column(name = "min_delivery_amount", precision = 10, scale = 2)
    private BigDecimal minDeliveryAmount = BigDecimal.ZERO;

    @Column(name = "delivery_start_time", length = 5)
    private String deliveryStartTime; // HH:mm

    @Column(name = "delivery_end_time", length = 5)
    private String deliveryEndTime; // HH:mm

    @Column(columnDefinition = "json")
    private String openingHours;

    @Column(name = "is_accepting_orders")
    private Boolean isAcceptingOrders = true;

    @Column(precision = 2, scale = 1)
    private BigDecimal avgRating = BigDecimal.ZERO;

    @Column(name = "review_count")
    private Integer reviewCount = 0;

    @Column(length = 20)
    private String status = "active"; // active / closed

    @Column(name = "manager_name")
    private String managerName;

    @Column(name = "manager_phone", length = 20)
    private String managerPhone;

    @Column(name = "store_phone", length = 20)
    private String storePhone;

    @Column(name = "cover_url", length = 255)
    private String coverUrl;

    /**
     * 權杖版本（N13）。目前分店沒有改密碼或停用的路徑；日後新增時必須遞增此欄位，
     * 否則舊權杖不會失效（見 CLAUDE.md 的 N13 約定）。
     */
    @JsonIgnore
    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;
}
