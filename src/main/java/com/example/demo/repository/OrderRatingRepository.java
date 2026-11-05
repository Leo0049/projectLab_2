package com.example.demo.repository;

import com.example.demo.entity.OrderRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRatingRepository extends JpaRepository<OrderRating, Long> {

    Optional<OrderRating> findByOrderIdAndUserId(Long orderId, Long userId);

    List<OrderRating> findByUserIdAndOrderIdIn(Long userId, Collection<Long> orderIds);

    long countByStoreId(Long storeId);

    List<OrderRating> findByStoreIdOrderByCreatedAtDesc(Long storeId);

    @Query("SELECT COALESCE(AVG(r.rating), 0) FROM OrderRating r WHERE r.store.id = :storeId")
    Double findAverageRatingByStoreId(@Param("storeId") Long storeId);

    @Query("SELECT r.rating, COUNT(r) FROM OrderRating r WHERE r.store.id IN :storeIds GROUP BY r.rating")
    List<Object[]> countByRatingInStores(@Param("storeIds") List<Long> storeIds);

    @Query("SELECT r.rating, COUNT(r) FROM OrderRating r WHERE r.store.id = :storeId GROUP BY r.rating")
    List<Object[]> countByRatingForStore(@Param("storeId") Long storeId);
}