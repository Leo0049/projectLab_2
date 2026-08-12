package com.example.demo.repository;

import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.OrderItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GroupOrderRepository extends JpaRepository<GroupOrder, Long> {

       List<GroupOrder> findByInitiatorIdOrderByCreatedAtDesc(Long initiatorId);

       @Query(value = """
                     SELECT DISTINCT g FROM GroupOrder g
                     JOIN FETCH g.store s
                     JOIN FETCH g.initiator i
                     LEFT JOIN OrderItem oi ON oi.groupOrder = g
                     WHERE g.initiator.id = :userId OR oi.user.id = :userId
                     ORDER BY g.createdAt DESC
                     """,
              countQuery = """
                     SELECT COUNT(DISTINCT g) FROM GroupOrder g
                     LEFT JOIN OrderItem oi ON oi.groupOrder = g
                     WHERE g.initiator.id = :userId OR oi.user.id = :userId
                     """)
       Page<GroupOrder> findVisibleOrdersForUser(@Param("userId") Long userId, Pageable pageable);

       @Query(value = """
                     SELECT DISTINCT g FROM GroupOrder g
                     JOIN FETCH g.store s
                     LEFT JOIN OrderItem oi ON oi.groupOrder = g
                     WHERE (g.initiator.id = :userId OR oi.user.id = :userId)
                     AND g.status IN :statuses
                     ORDER BY g.createdAt DESC
                     """,
              countQuery = """
                     SELECT COUNT(DISTINCT g) FROM GroupOrder g
                     LEFT JOIN OrderItem oi ON oi.groupOrder = g
                     WHERE (g.initiator.id = :userId OR oi.user.id = :userId)
                     AND g.status IN :statuses
                     """)
       Page<GroupOrder> findVisibleOrdersForUserByStatuses(@Param("userId") Long userId, @Param("statuses") List<String> statuses, Pageable pageable);



       @EntityGraph(attributePaths = {"initiator", "store", "store.brand"})
       Optional<GroupOrder> findByShareToken(String shareToken);

        // 查詢特定類別的訂單 (如 SOLO)
       Optional<GroupOrder> findByInitiatorIdAndStoreIdAndTypeAndStatusIn(Long initiatorId, Long storeId,
                     String type, List<String> statuses);

       // 查詢揪團主揪的訂單 (不分 Type)
       Optional<GroupOrder> findByInitiatorIdAndStoreIdAndStatusIn(Long initiatorId, Long storeId,
                     List<String> statuses);

       // 此查詢用於找出使用者發起或參與的所有活躍訂單，加入 JOIN FETCH 以防 N+1
       @Query("SELECT DISTINCT go FROM GroupOrder go " +
                     "JOIN FETCH go.store s " +
                     "JOIN FETCH s.brand b " +
                     "JOIN FETCH go.initiator i " +
                     "LEFT JOIN OrderItem oi ON go.id = oi.groupOrder.id " +
                     "WHERE (go.initiator.id = :userId OR oi.user.id = :userId) " +
                     "AND go.status IN :statuses")
       List<GroupOrder> findActiveByUser(@Param("userId") Long userId, @Param("statuses") List<String> statuses);

       // 請確認 Entity 中是否有 realOrderId 欄位或對應 order_no
       Optional<GroupOrder> findByOrderNo(String orderNo);

       List<GroupOrder> findByStoreIdAndStatus(Long storeId, String status);

       @Query(value = "SELECT DATE(o.created_at) as date, COUNT(o.id) as count, SUM(o.total_amount) as revenue " +
                     "FROM orders o WHERE o.store_id = :storeId AND o.status IN ('SUBMITTED','MAKING','READY','COMPLETED') "
                     +
                     "AND o.created_at BETWEEN :start AND :end " +
                     "GROUP BY DATE(o.created_at) ORDER BY date DESC", nativeQuery = true)
       List<Object[]> findStoreDailyStats(
                     @Param("storeId") Long storeId,
                     @Param("start") LocalDateTime start,
                     @Param("end") LocalDateTime end);

       @Query("""
                     SELECT g FROM GroupOrder g
                     JOIN g.store s
                     WHERE s.brand.id = :brandId
                     AND g.status = :status
                     AND g.createdAt >= :from
                     AND g.createdAt < :to
                     """)
       List<GroupOrder> findByBrandIdAndStatusAndPeriod(
                     @Param("brandId") Long brandId,
                     @Param("status") String status,
                     @Param("from") LocalDateTime from,
                     @Param("to") LocalDateTime to);

       @Query(value = """
                     SELECT s.id, s.store_name,
                            COUNT(g.id) AS order_count,
                            COALESCE(SUM(g.total_amount), 0) AS revenue
                     FROM orders g
                     JOIN stores s ON g.store_id = s.id
                     WHERE s.brand_id = :brandId
                     AND g.status IN ('SUBMITTED','MAKING','READY','COMPLETED')
                     AND g.created_at >= :from
                     AND g.created_at < :to
                     GROUP BY s.id, s.store_name
                     ORDER BY revenue DESC
                     LIMIT 5
                     """, nativeQuery = true)
       List<Object[]> findStoreRevenueRanking(
                     @Param("brandId") Long brandId,
                     @Param("from") LocalDateTime from,
                     @Param("to") LocalDateTime to);

       @Query(value = """
                     SELECT COUNT(g.id), COALESCE(SUM(g.total_amount), 0)
                     FROM orders g
                     WHERE g.store_id = :storeId
                     AND g.status IN ('SUBMITTED','MAKING','READY','COMPLETED')
                     AND g.created_at >= :from
                     AND g.created_at < :to
                     """, nativeQuery = true)
       List<Object[]> findStoreTodaySummary(
                     @Param("storeId") Long storeId,
                     @Param("from") LocalDateTime from,
                     @Param("to") LocalDateTime to);

       @Query("""
                     SELECT g FROM GroupOrder g
                     WHERE g.store.id = :storeId
                     AND (:status IS NULL OR g.status = :status)
                     ORDER BY g.createdAt DESC
                     """)
       List<GroupOrder> findByStoreIdAndOptionalStatus(
                     @Param("storeId") Long storeId,
                     @Param("status") String status);

       /**
        * 分頁版本，供 GET /api/stores/orders 使用。
        *
        * ⚠️ 不分頁的版本會一次撈出該門市的全部歷史訂單。壓測實測：
        * 6,666 筆訂單的單一回應為 2.1 MB，50 併發下 p50 從 0.14 秒惡化到 2.7 秒、
        * p99 超過 10 秒，比其他端點慢 50~60 倍。門市營運一年後會直接不可用。
        * 新增訂單列表相關查詢時請一律帶 Pageable。
        */
       @Query("""
                     SELECT g FROM GroupOrder g
                     WHERE g.store.id = :storeId
                     AND (:status IS NULL OR g.status = :status)
                     ORDER BY g.createdAt DESC
                     """)
       Page<GroupOrder> findByStoreIdAndOptionalStatus(
                     @Param("storeId") Long storeId,
                     @Param("status") String status,
                     Pageable pageable);

       @Query("""
                     SELECT g FROM GroupOrder g
                     WHERE g.store.id = :storeId
                     AND g.status = :status
                     AND g.createdAt >= :from
                     AND g.createdAt < :to
                     """)
       List<GroupOrder> findByStoreIdAndStatusAndPeriod(
                     @Param("storeId") Long storeId,
                     @Param("status") String status,
                     @Param("from") LocalDateTime from,
                     @Param("to") LocalDateTime to);

       @Query(value = """
                            SELECT pt.name,
                                   COUNT(oi.id)            AS order_count,
                                   COALESCE(SUM(oi.final_price), 0) AS revenue
                            FROM order_items oi
                            JOIN orders g ON oi.group_order_id = g.id
                            JOIN stores s ON g.store_id = s.id
                            JOIN product_templates pt ON oi.product_id = pt.id
                            WHERE s.brand_id = :brandId
                     AND pt.brand_id = :brandId
                              AND g.status IN ('SUBMITTED','MAKING','READY','COMPLETED')
                              AND g.created_at >= :from
                              AND g.created_at < :to
                            GROUP BY pt.name
                            ORDER BY order_count DESC
                            """, nativeQuery = true)
       List<Object[]> findBrandProductRanking(
                     @Param("brandId") Long brandId,
                     @Param("from") LocalDateTime from,
                     @Param("to") LocalDateTime to);

       @Query(value = """
                     SELECT s.id, s.store_name,
                            COUNT(g.id)                     AS order_count,
                            COALESCE(SUM(g.total_amount), 0) AS revenue
                     FROM orders g
                     JOIN stores s ON g.store_id = s.id
                     WHERE s.brand_id = :brandId
                       AND g.status IN ('SUBMITTED','MAKING','READY','COMPLETED')
                       AND g.created_at >= :from
                       AND g.created_at < :to
                     GROUP BY s.id, s.store_name
                     ORDER BY revenue DESC
                     """, nativeQuery = true)
       List<Object[]> findBrandStoreFinance(
                     @Param("brandId") Long brandId,
                     @Param("from") LocalDateTime from,
                     @Param("to") LocalDateTime to);

       // 分店每日營收
       @Query(value = """
                     SELECT DATE(g.created_at) AS day,
                            COALESCE(SUM(g.total_amount), 0) AS revenue
                     FROM orders g
                     WHERE g.store_id = :storeId
                       AND g.status IN ('SUBMITTED','MAKING','READY','COMPLETED')
                       AND g.created_at >= :from
                       AND g.created_at < :to
                     GROUP BY DATE(g.created_at)
                     ORDER BY day
                     """, nativeQuery = true)
       List<Object[]> findStoreDailyRevenue(
                     @Param("storeId") Long storeId,
                     @Param("from") LocalDateTime from,
                     @Param("to") LocalDateTime to);

       // 品牌每日營收
       @Query(value = """
                     SELECT DATE(g.created_at) AS day,
                            COALESCE(SUM(g.total_amount), 0) AS revenue
                     FROM orders g
                     JOIN stores s ON g.store_id = s.id
                     WHERE s.brand_id = :brandId
                       AND g.status = 'COMPLETED'
                       AND g.created_at >= :from
                       AND g.created_at < :to
                     GROUP BY DATE(g.created_at)
                     ORDER BY day
                     """, nativeQuery = true)
       List<Object[]> findBrandDailyRevenue(
                     @Param("brandId") Long brandId,
                     @Param("from") LocalDateTime from,
                     @Param("to") LocalDateTime to);

       @Query(value = """
                     SELECT COALESCE(r.name, '未分區') AS region_name,
                            COUNT(DISTINCT s.id)       AS store_count,
                            COALESCE(SUM(g.total_amount), 0) AS revenue
                     FROM orders g
                     JOIN stores s ON g.store_id = s.id
                     LEFT JOIN regions r ON s.region_id = r.id
                     WHERE s.brand_id = :brandId
                       AND g.status = 'COMPLETED'
                       AND g.created_at >= :from
                       AND g.created_at < :to
                     GROUP BY r.id, region_name
                     ORDER BY revenue DESC
                     """, nativeQuery = true)
       List<Object[]> findBrandRegionFinance(
                     @Param("brandId") Long brandId,
                     @Param("from") LocalDateTime from,
                     @Param("to") LocalDateTime to);

       @Query("SELECT COUNT(g) FROM GroupOrder g WHERE g.store.id IN :storeIds AND g.status = 'COMPLETED'")
       long countCompletedByStoreIds(@Param("storeIds") List<Long> storeIds);
}
