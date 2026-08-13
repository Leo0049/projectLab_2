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

    /**
     * 交易種類，取值固定為 {@link com.example.demo.service.wallet.TxType} 的常數。
     *
     * <p>⚠️ 這個欄位一度被當成「顯示字串」在用，實際存的是
     * {@code "消費扣款\n個人訂單 #12 結帳扣款"} 這種兩行文字，
     * 前端再自己 split、用 {@code includes('補款')} 之類的字串比對推回種類。
     * 後果是連「按種類統計」都得寫 {@code SUBSTRING_INDEX(type,'\n',1)}。
     * 種類與說明現已分成兩欄，type 只放 token，人看的字放 description。
     */
    @Column
    private String type;

    /** 人看的說明（例如「個人訂單 #12 結帳扣款」）。舊資料為 null，讀取時由 type 拆出來 */
    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
