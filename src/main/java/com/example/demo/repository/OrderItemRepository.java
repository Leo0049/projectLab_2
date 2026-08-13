package com.example.demo.repository;

import com.example.demo.entity.OrderItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

        /**
         * 取出某位團員在這張揪團裡「尚未付款」的品項，並**鎖住這些列**。
         *
         * ⚠️ 不可改回先 findByGroupOrderId 再用 stream 過濾。
         * 「讀出未付款品項 → 扣款 → 標記 PAID」是 read-modify-write，
         * 沒有列鎖時同一批品項會被多個併發交易同時判定為未付款——
         * 實測團員同時送出 8 個結帳請求，5 個都成功扣款，
         * 帳本寫進 5 筆 −35 而餘額只掉 35，帳完全對不起來。
         *
         * 上鎖之後，後到的交易會等前一個提交，再讀就看到 PAID、拿到空清單，
         * 於是正確地回「已無未付款品項」。GroupCheckoutConcurrencyTest 守住這條。
         */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT i FROM OrderItem i WHERE i.groupOrder.id = :groupOrderId "
                        + "AND i.user.id = :userId AND UPPER(i.paymentStatus) IN :statuses")
        List<OrderItem> findByGroupOrderAndUserAndStatusForUpdate(@Param("groupOrderId") Long groupOrderId,
                        @Param("userId") Long userId, @Param("statuses") List<String> statuses);

        /** 取出整張揪團的品項並鎖住，供取消退款這種會逐項退錢的流程使用 */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT i FROM OrderItem i WHERE i.groupOrder.id = :groupOrderId")
        List<OrderItem> findByGroupOrderIdForUpdate(@Param("groupOrderId") Long groupOrderId);

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
