package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
// findByUserIdOrderByCreatedAtDesc：交易紀錄／對帳查詢（含排序）
@Table(name = "transaction_records", indexes = {
        @Index(name = "idx_tx_user_created", columnList = "user_id, created_at")
})
@Data
public class TransactionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column
    private String type; // TOPUP / ESCROW / REFUND / FINAL_PAY / REPAYMENT

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
