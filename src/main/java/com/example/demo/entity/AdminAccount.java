package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 管理員帳號（N-28）。與顧客、品牌、門市分開存放，角色固定為 ADMIN。
 *
 * <p>管理員沒有公開的註冊端點，只能由 {@link com.example.demo.common.AdminBootstrap} 在啟動時建立。
 * 權杖的版本規則與其他帳號相同（N13）。
 */
@Entity
@Table(name = "admins")
@Data
public class AdminAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String passwordHash;

    @Column(nullable = false, length = 20)
    private String role = "ADMIN";

    /** 權杖版本（N13）：權杖必須帶著相符的版本才會被接受；日後新增改密碼路徑時須遞增。 */
    @JsonIgnore
    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
