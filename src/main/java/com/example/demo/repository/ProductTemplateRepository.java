package com.example.demo.repository;

import com.example.demo.entity.ProductTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ProductTemplateRepository extends JpaRepository<ProductTemplate, Long> {

    @Query("""
            SELECT p
            FROM ProductTemplate p
            LEFT JOIN FETCH p.category
            WHERE p.brand.id = :brandId
            ORDER BY
              CASE WHEN p.category IS NULL THEN 1 ELSE 0 END,
              COALESCE(p.category.sortOrder, 0) ASC,
              COALESCE(p.sortOrder, 0) ASC,
              p.id ASC
            """)
    List<ProductTemplate> findByBrandId(@Param("brandId") Long brandId);

    List<ProductTemplate> findByCategoryId(Long categoryId);
    void deleteByCategoryId(Long categoryId);

    @Query("""
            SELECT COALESCE(MAX(p.sortOrder), -1)
            FROM ProductTemplate p
            WHERE p.brand.id = :brandId
              AND p.category.id = :categoryId
            """)
    Integer findMaxSortOrderByBrandIdAndCategoryId(@Param("brandId") Long brandId,
                                                   @Param("categoryId") Long categoryId);

    @Query("""
            SELECT COALESCE(MAX(p.sortOrder), -1)
            FROM ProductTemplate p
            WHERE p.brand.id = :brandId
              AND p.category IS NULL
            """)
    Integer findMaxSortOrderByBrandIdAndCategoryIsNull(@Param("brandId") Long brandId);
}
