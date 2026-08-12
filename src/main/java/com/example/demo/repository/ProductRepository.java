package com.example.demo.repository;

import com.example.demo.entity.ProductTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface ProductRepository extends JpaRepository<ProductTemplate, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"category", "brand"})
    @Query("""
            SELECT p FROM ProductTemplate p
            JOIN Store s ON p.brand.id = s.brand.id
            WHERE s.id = :storeId
            AND p.isEnabled = true
            """)
    List<ProductTemplate> findByStoreId(@Param("storeId") Long storeId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"category", "brand"})
    @Query("""
            SELECT p FROM ProductTemplate p
            JOIN Store s ON p.brand.id = s.brand.id
            WHERE s.id = :storeId
            AND p.category.id = :categoryId
            AND p.isEnabled = true
            """)
    List<ProductTemplate> findByStoreIdAndCategoryId(
            @Param("storeId") Long storeId,
            @Param("categoryId") Long categoryId);

    @Query("""
            SELECT p FROM ProductTemplate p
            WHERE p.brand.id = :brandId
            AND p.isEnabled = true
            """)
    List<ProductTemplate> findByBrandId(@Param("brandId") Long brandId);
}
