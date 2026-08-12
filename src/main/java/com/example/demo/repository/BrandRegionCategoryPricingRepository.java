package com.example.demo.repository;

import com.example.demo.entity.BrandRegionCategoryPricing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrandRegionCategoryPricingRepository extends JpaRepository<BrandRegionCategoryPricing, Long> {
    List<BrandRegionCategoryPricing> findByBrandIdAndRegionId(Long brandId, Long regionId);
    List<BrandRegionCategoryPricing> findByBrandId(Long brandId);
    List<BrandRegionCategoryPricing> findByCategoryId(Long categoryId);
    Optional<BrandRegionCategoryPricing> findByBrandIdAndRegionIdAndCategoryId(Long brandId, Long regionId, Long categoryId);

    void deleteByCategoryId(Long categoryId);
}
