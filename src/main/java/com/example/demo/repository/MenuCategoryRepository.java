package com.example.demo.repository;

import com.example.demo.entity.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {

    @Query("SELECT c FROM MenuCategory c WHERE c.brand.id = :brandId ORDER BY COALESCE(c.sortOrder, 0) ASC, c.id ASC")
    List<MenuCategory> findByBrandId(@Param("brandId") Long brandId);

    boolean existsByBrandIdAndName(Long brandId, String name);

    List<MenuCategory> findByBrandIdOrderBySortOrderAsc(Long brandId);

    @Query("SELECT COALESCE(MAX(c.sortOrder), -1) FROM MenuCategory c WHERE c.brand.id = :brandId")
    Integer findMaxSortOrderByBrandId(@Param("brandId") Long brandId);
}
