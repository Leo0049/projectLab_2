package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_coupons")
@Data
public class UserCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ProductTemplate product;

    @Column(name = "coupon_type", nullable = false, length = 20)
    private String couponType; // WHEEL_GAME / ADMIN_GIFT

    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountAmount = new BigDecimal("5.00");

    @Column(name = "status", nullable = false, length = 10)
    private String status = "unused"; // unused / used / expired

    @Column(name = "obtained_at", nullable = false)
    private LocalDateTime obtainedAt = LocalDateTime.now();

    // ⚠️ 以下兩欄是 MySQL GENERATED COLUMN，由 DB 依 obtained_at 自動算出，程式不寫入。
    //    這兩欄原本是在資料庫端手動建立的，Hibernate 不知情時會建成普通可空欄位，
    //    因為 insertable=false 而永遠是 NULL，導致優惠券不會過期、轉盤 DB 防重失效。
    //    這裡補上 columnDefinition，讓 ddl-auto 在新資料庫也能正確建出 generated column。
    //    註：ddl-auto=update 不會把「已存在的普通欄位」改成 generated，需重建資料表才會生效。
    @Column(name = "obtained_date", insertable = false, updatable = false,
            columnDefinition = "DATE GENERATED ALWAYS AS (CAST(obtained_at AS DATE)) STORED")
    private LocalDate obtainedDate;

    @Column(name = "expired_at", insertable = false, updatable = false,
            columnDefinition = "DATETIME GENERATED ALWAYS AS (obtained_at + INTERVAL 7 DAY) STORED")
    private LocalDateTime expiredAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "order_item_id")
    private Long orderItemId;
}
