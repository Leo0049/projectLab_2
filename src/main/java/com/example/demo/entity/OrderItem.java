package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.math.BigDecimal;

@Entity
// existsByGroupOrderIdAndUserId 是 WebSocket 訂閱授權的判斷依據（每次 SUBSCRIBE 都會查），
// findByGroupOrderIdAndUserId 則用於揪團的個人品項；兩者都吃這個複合索引。
@Table(name = "order_items", indexes = {
        @Index(name = "idx_order_items_order_user", columnList = "group_order_id, user_id")
})
@Data
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_order_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private GroupOrder groupOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ProductTemplate product;

    @Column(name = "payment_status")
    private String paymentStatus = "WAITING_SUBMIT";

    @Column(name = "payment_type")
    private String paymentType;

    @Column(name = "final_price", precision = 10, scale = 2)
    private BigDecimal finalPrice;

    @Column(name = "product_name_snapshot")
    private String productNameSnapshot;

    @Column(name = "unit_price_snapshot", precision = 10, scale = 2)
    private BigDecimal unitPriceSnapshot;

    @Column(name = "sugar_snapshot")
    private String sugarSnapshot;

    @Column(name = "ice_snapshot")
    private String iceSnapshot;

    @Column(name = "size_snapshot")
    private String sizeSnapshot;

    @Column(name = "item_hash", length = 32)
    private String itemHash;


    @Column(name = "coupon_id")
    private Long couponId;

    @Column(name = "discount_amount_snapshot", precision = 10, scale = 2)
    private BigDecimal discountAmountSnapshot = BigDecimal.ZERO;

    @Column(name = "qty", nullable = false)
    private int qty = 1;

    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<OrderItemTopping> toppings;
}
