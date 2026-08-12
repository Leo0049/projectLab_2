package com.example.demo.repository;

import com.example.demo.entity.UserAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// ✏️ 修改：類別名稱改為單數 UserAuthProviderRepository（原本是 UsersAuthProviderRepository）
@Repository
public interface UserAuthProviderRepository extends JpaRepository<UserAuthProvider, Long> {

    Optional<UserAuthProvider> findByProviderAndProviderUid(String provider, String providerUid);

    boolean existsByProviderAndProviderUid(String provider, String providerUid);

    // 方便 Debug：抓最新幾筆三方綁定紀錄
    java.util.List<UserAuthProvider> findTop10ByOrderByIdDesc();
}
