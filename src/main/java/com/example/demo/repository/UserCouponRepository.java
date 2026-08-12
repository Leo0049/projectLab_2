package com.example.demo.repository;

import com.example.demo.entity.UserCoupon;

import jakarta.transaction.Transactional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserCouponRepository extends JpaRepository<UserCoupon, Long> {

    List<UserCoupon> findByUserId(Long userId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"product", "product.category", "brand"})
    List<UserCoupon> findByUserIdAndStatus(Long userId, String status);

    long countByUserIdAndObtainedAtBetween(Long userId,
            LocalDateTime start,
            LocalDateTime end);

    boolean existsByUserIdAndObtainedDateAndCouponType(Long userId, java.time.LocalDate date, String couponType);

    boolean existsByUserIdAndCouponTypeAndObtainedAtBetween(Long userId, String couponType, LocalDateTime start, LocalDateTime end);

    List<UserCoupon> findByStatusAndExpiredAtBefore(String status, LocalDateTime now);

    @Query("""
            SELECT uc FROM UserCoupon uc
            WHERE uc.product.id = :couponId
            """)
    List<UserCoupon> findByCouponId(@Param("couponId") Long couponId);

    @Query(value = "SELECT * FROM user_coupons WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<UserCoupon> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("""
            UPDATE UserCoupon uc
            SET uc.status = 'used',
                uc.usedAt = :usedAt
            WHERE uc.id = :id
            AND uc.status = 'unused'
            """)
    int updateIsUsed(@Param("id") Long id, @Param("usedAt") LocalDateTime usedAt);

    @Query("""
        SELECT uc FROM UserCoupon uc
        WHERE uc.user.id   = :userId
          AND uc.status    = 'used'
          AND uc.usedAt   >= :from
          AND uc.usedAt   <= :to
          AND uc.id NOT IN :excludedIds
        """)
    List<UserCoupon> findCheckoutLevelCoupon(
            @Param("userId")      Long userId,
            @Param("from")        LocalDateTime from,
            @Param("to")          LocalDateTime to,
            @Param("excludedIds") java.util.Collection<Long> excludedIds);
}
