package com.example.demo.repository;

import com.example.demo.entity.Store;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoreRepository extends JpaRepository<Store, Long> {

    @EntityGraph(attributePaths = {"brand", "region"})
    @Query("SELECT s FROM Store s WHERE s.isAcceptingOrders = true")
    List<Store> findByIsAcceptingOrdersTrue();

    Optional<Store> findFirstByAccount(String account);

    boolean existsByStoreName(String storeName);


    // 品牌名稱搜尋（LIKE）,同時搜尋品牌名稱和店家名稱
    @EntityGraph(attributePaths = {"brand"})
    @Query("SELECT s FROM Store s JOIN s.brand b WHERE (b.name LIKE %:keyword% OR s.storeName LIKE %:keyword%) AND s.status = 'active'")
    List<Store> searchByBrandName(@Param("keyword") String keyword);

    // 依關鍵字搜尋 + 依距離排序（Haversine）
    @Query(value = """
            SELECT s.*, b.name as brand_name,
            (6371 * acos(cos(radians(:lat)) * cos(radians(s.latitude))
            * cos(radians(s.longitude) - radians(:lng))
            + sin(radians(:lat)) * sin(radians(s.latitude)))) AS distance
            FROM stores s JOIN brands b ON s.brand_id = b.id
            WHERE s.status = 'active'
            AND (s.store_name LIKE :keyword OR b.name LIKE :keyword)
            ORDER BY distance
            """, nativeQuery = true)
    List<Object[]> searchByKeywordWithDistance(
            @Param("keyword") String keyword,
            @Param("lat") double lat,
            @Param("lng") double lng);

    // 附近店家 - 依距離排序（Haversine）
    @Query(value = """
            SELECT s.*, b.name as brand_name,
            (6371 * acos(cos(radians(:lat)) * cos(radians(s.latitude))
            * cos(radians(s.longitude) - radians(:lng))
            + sin(radians(:lat)) * sin(radians(s.latitude)))) AS distance
            FROM stores s JOIN brands b ON s.brand_id = b.id
            WHERE s.status = 'active'
            AND (:brandId IS NULL OR s.brand_id = :brandId)
            AND (:minRating IS NULL OR s.avg_rating IS NULL OR s.avg_rating >= :minRating)
            HAVING distance < 50
            ORDER BY distance
            LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> findNearbyStores(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("brandId") Long brandId,
            @Param("minRating") Double minRating,
            @Param("size") int size,
            @Param("offset") int offset);

    @Override
    @EntityGraph(attributePaths = {"brand"})
    List<Store> findAllById(Iterable<Long> ids);

    @EntityGraph(attributePaths = {"brand"})
    List<Store> findByStatus(String status);

    @EntityGraph(attributePaths = {"brand"})
    List<Store> findByBrandId(Long brandId);

    /**
     * 取得門市那一列的寫鎖。
     *
     * <p>⚠️ 評分流程一定要先拿這個鎖再寫 {@code order_ratings}。
     * 原本的順序是「先 insert 評分，再 update 門市的 avg_rating／review_count」：
     * insert 會因為外鍵在 stores 那一列上加共享鎖，接著的 update 要升級成排他鎖，
     * 兩個併發交易就互相卡住——**實測 12 個併發評分只有 2 筆成功，10 筆死鎖**。
     * 先取排他鎖之後，後到的交易會排隊而不是形成死結。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Store s WHERE s.id = :id")
    java.util.Optional<Store> findByIdForUpdate(@Param("id") Long id);

    /**
     * 用單一 SQL 重算門市的評分彙總。
     *
     * <p>⚠️ 不能在 Java 端「COUNT 出來 → setReviewCount → save」。
     * MySQL 預設 REPEATABLE READ 之下，那個 COUNT 讀的是**本交易的快照**，
     * 看不到別的交易剛提交的評分，於是每個交易都用自己的舊數字覆蓋回去——
     * 實測 12 筆併發評分寫完，門市顯示的則數只有 4、平均分數也差了 0.1。
     *
     * <p>寫成 UPDATE 之後，子查詢讀的是最新已提交的版本（不是快照），
     * 配合先取的門市列鎖，每個交易都會算到完整結果。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE stores s SET "
            + "s.review_count = (SELECT COUNT(*) FROM order_ratings r WHERE r.store_id = s.id), "
            + "s.avg_rating = COALESCE((SELECT ROUND(AVG(r.rating), 1) FROM order_ratings r WHERE r.store_id = s.id), 0) "
            + "WHERE s.id = :id", nativeQuery = true)
    int refreshRatingAggregate(@Param("id") Long id);
}
