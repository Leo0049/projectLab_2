package com.example.demo.repository;

import com.example.demo.entity.BrandToppingSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BrandToppingSettingRepository extends JpaRepository<BrandToppingSetting, Long> {
    List<BrandToppingSetting> findByBrandIdAndIsEnabledTrue(Long brandId);
    List<BrandToppingSetting> findByBrandId(Long brandId);
    long countByBrandId(Long brandId);
    boolean existsByBrandIdAndMasterToppingId(Long brandId, Long masterToppingId);
    boolean existsByBrandIdAndCustomName(Long brandId, String customName);
    boolean existsByBrandIdAndCustomNameAndIdNot(Long brandId, String customName, Long excludeId);
}
