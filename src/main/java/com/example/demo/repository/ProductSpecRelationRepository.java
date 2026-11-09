package com.example.demo.repository;

import com.example.demo.entity.ProductSpecRelation;
import com.example.demo.entity.ProductSpecRelationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.List;

@Repository
public interface ProductSpecRelationRepository extends JpaRepository<ProductSpecRelation, ProductSpecRelationId> {

    @Query("SELECT r FROM ProductSpecRelation r JOIN FETCH r.brandSpec bs LEFT JOIN FETCH bs.master WHERE r.id.productId = :productId")
    List<ProductSpecRelation> findByIdProductId(@Param("productId") Long productId);

    /**
     * 一次撈多個商品的規格關聯，供建單的固定規格判斷使用（逐品項查詢會是 N+1）。
     *
     * <p>⚠️ 必須與 findByIdProductId 一樣用 LEFT JOIN FETCH master：品牌自建規格的 master_id 可為 null，
     * 改用 JOIN FETCH（內連線）會把這些選項丟掉，固定規格判斷就會漏看它。findByProductIds 用的是 JOIN FETCH，
     * 不可拿來取代這個方法。
     */
    @Query("SELECT r FROM ProductSpecRelation r JOIN FETCH r.brandSpec bs LEFT JOIN FETCH bs.master WHERE r.id.productId IN :productIds")
    List<ProductSpecRelation> findByIdProductIdIn(@Param("productIds") List<Long> productIds);

    void deleteByIdBrandSpecId(Long brandSpecId);

    @Modifying
    @Query("DELETE FROM ProductSpecRelation r WHERE r.id.productId = :productId")
    void deleteByIdProductId(@Param("productId") Long productId);

    // 兼容舊版
    java.util.Optional<ProductSpecRelation> findByIdProductIdAndIdBrandSpecId(Long productId, Long brandSpecId);

    @Modifying
    @Query(value = "INSERT INTO product_spec_relations (product_id, brand_spec_id, price) VALUES (:productId, :specId, NULL)", nativeQuery = true)
    void insertRelation(@Param("productId") Long productId, @Param("specId") Long specId);

    @Modifying
    @Query(value = "INSERT INTO product_spec_relations (product_id, brand_spec_id, price) VALUES (:productId, :specId, :price)", nativeQuery = true)
    void insertRelationWithPrice(@Param("productId") Long productId, @Param("specId") Long specId,
            @Param("price") BigDecimal price);

    /** 撈某飲品所有 SIZE 規格定價（price 不為 null） */
    @Query("SELECT r FROM ProductSpecRelation r JOIN FETCH r.brandSpec bs LEFT JOIN FETCH bs.master WHERE r.id.productId = :productId AND r.price IS NOT NULL")
    List<ProductSpecRelation> findSizePricingsByProductId(@Param("productId") Long productId);

    /**
     * 同 {@link #findSizePricingsByProductId}，一次撈整張訂單的商品（建單定價用，見 PricingService.forOrder）。
     * fetch 與價格條件必須與單一商品的版本相同，兩者才會得到相同的容量。
     */
    @Query("SELECT r FROM ProductSpecRelation r JOIN FETCH r.brandSpec bs LEFT JOIN FETCH bs.master WHERE r.id.productId IN :productIds AND r.price IS NOT NULL")
    List<ProductSpecRelation> findSizePricingsByProductIdIn(@Param("productIds") List<Long> productIds);

    /** 撈某品牌所有飲品的 SIZE 規格定價，避免 N+1 */
    @Query("SELECT r FROM ProductSpecRelation r JOIN FETCH r.brandSpec bs LEFT JOIN FETCH bs.master WHERE r.product.brand.id = :brandId AND r.price IS NOT NULL")
    List<ProductSpecRelation> findSizePricingsByBrandId(@Param("brandId") Long brandId);
    @Query("SELECT r FROM ProductSpecRelation r JOIN FETCH r.brandSpec bs JOIN FETCH bs.master WHERE r.id.productId IN :productIds")
    List<ProductSpecRelation> findByProductIds(@Param("productIds") List<Long> productIds);
}
