package com.example.demo.repository;

import com.example.demo.entity.Store;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
}
