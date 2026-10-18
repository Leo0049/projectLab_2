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
     * 權杖版本（N13）。目前品牌沒有改密碼或停用的路徑；日後新增時必須遞增此欄位，
     * 否則舊權杖不會失效（見 CLAUDE.md 的 N13 約定）。
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
}
