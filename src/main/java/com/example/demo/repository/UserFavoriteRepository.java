package com.example.demo.repository;

import com.example.demo.entity.UserFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserFavoriteRepository extends JpaRepository<UserFavorite, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"store", "store.brand"})
    List<UserFavorite> findByUserId(Long userId);
    
    @org.springframework.data.jpa.repository.Query("SELECT uf.store.id FROM UserFavorite uf WHERE uf.user.id = :userId")
    java.util.Set<Long> findStoreIdsByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
    
    Optional<UserFavorite> findByUserIdAndStoreId(Long userId, Long storeId);
    boolean existsByUserIdAndStoreId(Long userId, Long storeId);
}
