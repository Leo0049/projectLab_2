package com.example.demo.dto;

import com.example.demo.entity.Store;
import lombok.Data;

@Data
public class StoreDTO {
    private Long id;
    private Long brandId;
    private String name;
    private String address;
    private Boolean isOpen;

    // UI specific fields for mockup
    private String logoText;
    private String logoBg;
    private Double rating;
    private String categories;
    private String discount;
    private String image;
    private String status;

    // Formatted distance (e.g., "1.5 km", "800 m")
    private String distance;

    private Double latitude;
    private Double longitude;

    private String imageUrl;

    public StoreDTO(Store store, String formattedDistance) {
        this.id = store.getId();
        this.brandId = store.getBrand().getId();
        this.name = store.getStoreName();
        this.address = store.getAddress();
        this.imageUrl = store.getCoverUrl();
        this.isOpen = store.getIsAcceptingOrders();
        this.distance = formattedDistance;
        this.latitude = store.getLatitude() != null ? store.getLatitude().doubleValue() : null;
        this.longitude = store.getLongitude() != null ? store.getLongitude().doubleValue() : null;

        // Populate mock UI data for demonstration
        // if (store.getStoreName().contains("Happy Tea")) {
        // this.logoText = "HT";
        // this.logoBg = "bg-[#002b49]";
        // this.image =
        // "https://images.unsplash.com/photo-1556679343-c7306c1976bc?auto=format&fit=crop&w=500&q=80";
        // this.categories = "手搖飲, 紅茶";
        // this.discount = "實價優惠券10折";
        // this.rating = 4.8;
        // } else {
        // this.logoText = "BW";
        // this.logoBg = "bg-[#1a4a38]";
        // this.image =
        // "https://images.unsplash.com/photo-1527661591475-527312dd65f5?auto=format&fit=crop&w=500&q=80";
        // this.categories = "手搖飲, 烏龍茶";
        // this.discount = "滿百打9折";
        // this.rating = 4.7;
        // }

        this.status = this.isOpen != null && this.isOpen ? "營業中" : "已休息";

        // If entity has image, use it
        if (store.getCoverUrl() != null && !store.getCoverUrl().isEmpty()) {
            this.image = store.getCoverUrl();
            this.imageUrl = store.getCoverUrl();
        }
    }
}
