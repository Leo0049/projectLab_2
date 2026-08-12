package com.example.demo.repository;

import com.example.demo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// ✏️ 修改：類別名稱改為單數 UserRepository（原本是 UsersRepository）
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findFirstByName(String name);

    Optional<User> findByPhone(String phone);

    Optional<User> findByRole(String role);

    boolean existsByPhone(String phone);
}
