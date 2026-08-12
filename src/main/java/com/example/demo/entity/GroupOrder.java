package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "orders")
@Data
public class GroupOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initiator_id", nullable = false)
    @ToString.Exclude
    private User initiator;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    @ToString.Exclude
    private Store store;

    @Column(nullable = false)
    private String type = "SOLO"; // SOLO / GROUP

    @Column(nullable = false)
    private String status = "OPEN"; // OPEN / SUBMITTED / PREPARING / READY / COMPLETED / CANCELLED / REJECTED

    @Column(name = "is_rejected", nullable = false)
    private Boolean isRejected = false;

    @Column(name = "order_no", unique = true, nullable = false)
    private String orderNo;

    @Column(name = "share_token", unique = true, length = 16)
    private String shareToken; // 揪團分享 token

    @Column(name = "total_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "address", length = 255)
    private String address = "";

    @Column(name = "note", length = 255)
    private String note = "";

    @Column(name = "escrow_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal escrowAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now(ZoneId.of("Asia/Taipei"));

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "preparing_at")
    private LocalDateTime preparingAt;

    @Column(name = "ready_at")
    private LocalDateTime readyAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_or_rejected_at")
    private LocalDateTime cancelledOrRejectedAt;

    @PrePersist
    private void prePersist() {
        if (type == null || type.isBlank())
            type = "SOLO";
        if (status == null || status.isBlank())
            status = "OPEN";
        if (isRejected == null)
            isRejected = false;
        if (totalAmount == null)
            totalAmount = BigDecimal.ZERO;
        if (escrowAmount == null)
            escrowAmount = BigDecimal.ZERO;
        if (address == null)
            address = "";
        if (note == null)
            note = "";
        if (createdAt == null)
            createdAt = LocalDateTime.now(ZoneId.of("Asia/Taipei"));
        if (orderNo == null || orderNo.isBlank())
            orderNo = com.example.demo.service.OrderService.generateOrderNo();
    }
}
