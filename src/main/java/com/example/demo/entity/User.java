package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, name = "phone")
    private String phone;

    @Column(name = "password_hash")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "pic_url")
    private String picUrl;

    @Column(nullable = false)
    private String role = "CUSTOMER";

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // 邏輯刪除
    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 權杖版本（N13）。簽發權杖時帶上此值；改密碼、刪除帳號時遞增，舊權杖因此失效。
     * 不對外輸出（JSON 忽略），只供 {@code AccountStatus} 比對。
     */
    @JsonIgnore
    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;

    @PrePersist
    private void applyDefaults() {
        if (balance == null)
            balance = BigDecimal.ZERO;
        if (createdAt == null)
            createdAt = LocalDateTime.now();
        if (isDeleted == null)
            isDeleted = false;
    }

    /**
     * 密碼變更一律經過這裡：換掉雜湊，並遞增 tokenVersion，使所有既有權杖失效（N13）。
     * 只有註冊新帳號才直接用 setPasswordHash。
     */
    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
        revokeIssuedTokens();
    }

    /** 遞增 tokenVersion：之前簽發的權杖都會被拒絕，即使日後帳號被還原也一樣（N13）。 */
    public void revokeIssuedTokens() {
        this.tokenVersion++;
    }
}
