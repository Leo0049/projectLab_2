package com.example.demo.repository;

import com.example.demo.entity.OrderItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

        @EntityGraph(attributePaths = {"user", "product"})
        List<OrderItem> findByGroupOrderId(Long groupOrderId);

        @EntityGraph(attributePaths = {"user", "product"})
        List<OrderItem> findByGroupOrderIdIn(List<Long> groupOrderIds);

        List<OrderItem> findByGroupOrderIdAndUserId(Long groupOrderId, Long userId);

        List<OrderItem> findByGroupOrderIdInAndUserId(List<Long> groupOrderIds, Long userId);

        boolean existsByGroupOrderIdAndUserId(Long groupOrderId, Long userId);

        java.util.Optional<OrderItem> findByGroupOrderIdAndUserIdAndItemHash(Long groupOrderId, Long userId, String itemHash);

        @Query(value = "SELECT HOUR(go.created_at), COUNT(oi.id) FROM order_items oi " +
                        "JOIN orders go ON oi.group_order_id = go.id " +
                        "WHERE go.store_id = :storeId AND go.status IN ('SUBMITTED','READY','COMPLETED') " +
                        "AND go.created_at BETWEEN :start AND :end " +
                        "GROUP BY HOUR(go.created_at) ORDER BY HOUR(go.created_at)", nativeQuery = true)
        List<Object[]> findHourlySales(
                        @Param("storeId") Long storeId,
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end);

        @Query(value = "SELECT pt.name, COUNT(oi.id), SUM(oi.final_price) FROM order_items oi " +
                        "JOIN orders go ON oi.group_order_id = go.id " +
                        "JOIN stores s ON go.store_id = s.id " +
                        "JOIN product_templates pt ON oi.product_id = pt.id " +
                        "WHERE go.store_id = :storeId AND pt.brand_id = s.brand_id " +
                        "AND go.status IN ('SUBMITTED','READY','COMPLETED') " +
                        "AND go.created_at BETWEEN :start AND :end " +
                        "GROUP BY pt.name ORDER BY COUNT(oi.id) DESC", nativeQuery = true)
        List<Object[]> findTopProducts(
                        @Param("storeId") Long storeId,
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end);

        @Query(value = "SELECT pt.name, COUNT(oi.id), SUM(oi.final_price) FROM order_items oi " +
                        "JOIN orders go ON oi.group_order_id = go.id " +
                        "JOIN stores s ON go.store_id = s.id " +
                        "JOIN product_templates pt ON oi.product_id = pt.id " +
                        "WHERE go.store_id = :storeId AND pt.brand_id = s.brand_id " +
                        "AND go.status = 'COMPLETED' " +
                        "AND go.created_at BETWEEN :start AND :end " +
                        "GROUP BY pt.name ORDER BY COUNT(oi.id) DESC", nativeQuery = true)
        List<Object[]> findTopProductsByStoreAndPeriod(
                        @Param("storeId") Long storeId,
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end);

        @Query(value = "SELECT pc.name, SUM(oi.final_price), COUNT(oi.id), SUM(oi.discount_amount_snapshot) FROM order_items oi "
                        +
                        "JOIN orders go ON oi.group_order_id = go.id " +
                        "JOIN stores s ON go.store_id = s.id " +
                        "JOIN product_templates pt ON oi.product_id = pt.id " +
                        "JOIN product_categories pc ON pt.category_id = pc.id " +
                        "WHERE go.store_id = :storeId AND pt.brand_id = s.brand_id " +
                        "AND go.status IN ('SUBMITTED','READY','COMPLETED') " +
                        "AND go.created_at BETWEEN :start AND :end " +
                        "GROUP BY pc.name ORDER BY SUM(oi.final_price) DESC", nativeQuery = true)
        List<Object[]> findRevenueByCategory(
                        @Param("storeId") Long storeId,
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end);

        @Query(value = "SELECT pc.name, DATE(go.created_at), SUM(oi.final_price) FROM order_items oi " +
                        "JOIN orders go ON oi.group_order_id = go.id " +
                        "JOIN stores s ON go.store_id = s.id " +
                        "JOIN product_templates pt ON oi.product_id = pt.id " +
                        "JOIN product_categories pc ON pt.category_id = pc.id " +
                        "WHERE go.store_id = :storeId AND pt.brand_id = s.brand_id " +
                        "AND go.status IN ('SUBMITTED','READY','COMPLETED') " +
                        "AND go.created_at BETWEEN :start AND :end " +
                        "GROUP BY pc.name, DATE(go.created_at)", nativeQuery = true)
        List<Object[]> findDailyRevenueByCategory(
                        @Param("storeId") Long storeId,
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end);

        List<OrderItem> findByUserId(Long userId);
}
