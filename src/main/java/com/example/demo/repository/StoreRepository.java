package com.example.demo.repository;

import com.example.demo.entity.Store;
import org.springframework.data.domain.Pageable;
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

    /**
     * 附近店家（兩段式查詢）。
     *
     * <p>第一段：以經緯度矩形做 {@code BETWEEN} 預篩，讓 {@code idx_stores_lat_lng} 可以做範圍掃描，
     * 不必對全表算 Haversine。矩形由 {@code GeoBounds} 換算，必為圓的超集合（見 GeoBoundsTest）。
     * 第二段：只對候選列算精確的 Haversine 距離，{@code HAVING} 以 {@code :radiusKm} 過濾並排序。
     *
     * <p>{@code acos} 的引數以 LEAST／GREATEST 夾在 [-1, 1]：同一座標時浮點誤差可能讓引數略大於 1，
     * MySQL 會回 NULL，於是「站在店家位置上」的店反而被 HAVING 過濾掉。
     *
     * <p>排序加上 {@code s.id} 作為次要鍵，分頁結果才穩定（距離相同時否則順序不定，會重複或漏列）。
     *
     * <p>SELECT 只取 {@code id} 與 {@code distance} 兩欄，呼叫端再以 id 載入實體。
     */
    @Query(value = """
            SELECT s.id AS id,
            (6371 * acos(LEAST(1, GREATEST(-1,
            cos(radians(:lat)) * cos(radians(s.latitude))
            * cos(radians(s.longitude) - radians(:lng))
            + sin(radians(:lat)) * sin(radians(s.latitude)))))) AS distance
            FROM stores s JOIN brands b ON s.brand_id = b.id
            WHERE s.status = 'active'
            AND s.latitude BETWEEN :latMin AND :latMax
            AND s.longitude BETWEEN :lngMin AND :lngMax
            AND (:brandId IS NULL OR s.brand_id = :brandId)
            AND (:minRating IS NULL OR s.avg_rating IS NULL OR s.avg_rating >= :minRating)
            HAVING distance < :radiusKm
            ORDER BY distance, s.id
            LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> findNearbyStores(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("latMin") double latMin,
            @Param("latMax") double latMax,
            @Param("lngMin") double lngMin,
            @Param("lngMax") double lngMax,
            @Param("radiusKm") double radiusKm,
            @Param("brandId") Long brandId,
            @Param("minRating") Double minRating,
            @Param("size") int size,
            @Param("offset") int offset);

    @Override
    @EntityGraph(attributePaths = {"brand"})
    List<Store> findAllById(Iterable<Long> ids);

    @EntityGraph(attributePaths = {"brand"})
    List<Store> findByStatus(String status);

    /** 無定位時的分頁列表（依 id 排序，分頁才穩定）。 */
    @EntityGraph(attributePaths = {"brand"})
    List<Store> findByStatusOrderByIdAsc(String status, Pageable pageable);

    /** 無定位且指定品牌時的分頁列表。 */
    @EntityGraph(attributePaths = {"brand"})
    List<Store> findByStatusAndBrandIdOrderByIdAsc(String status, Long brandId, Pageable pageable);

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
