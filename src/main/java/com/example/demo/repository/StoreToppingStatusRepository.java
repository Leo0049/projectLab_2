package com.example.demo.repository;

import com.example.demo.entity.StoreToppingStatus;
import com.example.demo.entity.StoreToppingStatusId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StoreToppingStatusRepository extends JpaRepository<StoreToppingStatus, StoreToppingStatusId> {
    List<StoreToppingStatus> findByIdStoreId(Long storeId);
    List<StoreToppingStatus> findByIdStoreIdAndIdBrandToppingIdIn(Long storeId, List<Long> brandToppingIds);
}
