package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Table(name = "user_auth_providers",
        uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_uid"}))
@Data
public class UserAuthProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ✏️ 修改：關聯改為單數 User（原本是 Users）
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    // PHONE / GOOGLE / FACEBOOK
    @Column(nullable = false, length = 20)
    private String provider;

    // PHONE=手機號碼 / GOOGLE,FACEBOOK=Firebase UID
    @Column(name = "provider_uid", nullable = false)
    private String providerUid;
}
