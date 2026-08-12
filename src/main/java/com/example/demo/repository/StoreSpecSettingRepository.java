package com.example.demo.repository;

import com.example.demo.entity.StoreSpecSetting;
import com.example.demo.entity.StoreSpecSettingId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StoreSpecSettingRepository extends JpaRepository<StoreSpecSetting, StoreSpecSettingId> {
    List<StoreSpecSetting> findByStoreId(Long storeId);
    List<StoreSpecSetting> findByStoreIdAndIsEnabled(Long storeId, Boolean isEnabled);
    List<StoreSpecSetting> findByIdStoreIdAndIdBrandSpecIdIn(Long storeId, List<Long> brandSpecIds);
}
