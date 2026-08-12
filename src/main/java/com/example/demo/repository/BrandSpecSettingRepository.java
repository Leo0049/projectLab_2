package com.example.demo.repository;

import com.example.demo.entity.BrandSpecSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BrandSpecSettingRepository extends JpaRepository<BrandSpecSetting, Long> {
    @Query("SELECT s FROM BrandSpecSetting s JOIN FETCH s.master WHERE s.brand.id = :brandId")
    List<BrandSpecSetting> findByBrandId(@Param("brandId") Long brandId);
    
    List<BrandSpecSetting> findByBrandIdOrderBySortOrderAscIdAsc(Long brandId);

    @Query("SELECT COUNT(s) FROM BrandSpecSetting s WHERE s.brand.id = :brandId AND (s.master.type = :type OR s.specType = :type)")
    long countByBrandIdAndType(@Param("brandId") Long brandId, @Param("type") String type);

    @Query("SELECT COALESCE(MAX(s.sortOrder), -1) FROM BrandSpecSetting s WHERE s.brand.id = :brandId AND (s.master.type = :type OR s.specType = :type)")
    int findMaxSortOrderByBrandIdAndType(@Param("brandId") Long brandId, @Param("type") String type);

    @Query("SELECT COUNT(s) > 0 FROM BrandSpecSetting s WHERE s.brand.id = :brandId AND s.customName = :name AND (s.master.type = :type OR s.specType = :type)")
    boolean existsByBrandIdAndTypeAndCustomName(@Param("brandId") Long brandId, @Param("type") String type, @Param("name") String name);

    @Query("SELECT COUNT(s) > 0 FROM BrandSpecSetting s WHERE s.brand.id = :brandId AND s.customName = :name AND (s.master.type = :type OR s.specType = :type) AND s.id <> :excludeId")
    boolean existsByBrandIdAndTypeAndCustomNameExcluding(@Param("brandId") Long brandId, @Param("type") String type, @Param("name") String name, @Param("excludeId") Long excludeId);

    boolean existsByBrandIdAndMasterId(Long brandId, Long masterId);
}
