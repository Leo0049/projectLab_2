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

    /** 撈某品牌所有飲品的 SIZE 規格定價，避免 N+1 */
    @Query("SELECT r FROM ProductSpecRelation r JOIN FETCH r.brandSpec bs LEFT JOIN FETCH bs.master WHERE r.product.brand.id = :brandId AND r.price IS NOT NULL")
    List<ProductSpecRelation> findSizePricingsByBrandId(@Param("brandId") Long brandId);
    @Query("SELECT r FROM ProductSpecRelation r JOIN FETCH r.brandSpec bs JOIN FETCH bs.master WHERE r.id.productId IN :productIds")
    List<ProductSpecRelation> findByProductIds(@Param("productIds") List<Long> productIds);
}
