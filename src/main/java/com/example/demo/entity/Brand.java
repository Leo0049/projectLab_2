package com.example.demo.entity;

import com.example.demo.common.BrandApproval;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "brands")
@Data
public class Brand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String account;

    @Column(name = "password_hash", nullable = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String passwordHash;

    @Column(nullable = false)
    private String role = "BRAND";

    // 主管版欄位名稱 logo_url
    @Column(name = "logo_url", length = 255)
    private String logoUrl;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 權杖版本（N13）。改密碼與管理員停用時遞增（停用由 AdminService 處理），舊權杖因此失效。
     */
    @JsonIgnore
    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;

    /**
     * 審核狀態（N-28）。自助註冊時由 BrandService 設為 PENDING；管理員核准後才能登入。
     * DDL 的 DEFAULT 'APPROVED' 讓既有品牌維持可登入，正式環境的 ALTER 也必須帶同樣的預設值（見 DATABASE.md）。
     */
    @Column(name = "approval_status", nullable = false,
            columnDefinition = "VARCHAR(16) NOT NULL DEFAULT 'APPROVED'")
    private String approvalStatus = BrandApproval.APPROVED;

    /** 管理員審核（核准或退件）的時間；尚未審核為 null。 */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /**
     * 管理員停用（stage 6）。停用的品牌不能登入、其門市不能下單、列表與詳情對外視同不存在，
     * 既有權杖與 STOMP 連線立即失效（見 {@link com.example.demo.common.AccountStatus}）。
     * DDL 的 DEFAULT false 讓既有品牌維持可用；正式環境的 ALTER 必須帶同樣的預設值（見 DATABASE.md）。
     */
    @Column(name = "suspended", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT false")
    private boolean suspended = false;

    /** 停用時間；未停用為 null。恢復時會清空。 */
    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;
}
