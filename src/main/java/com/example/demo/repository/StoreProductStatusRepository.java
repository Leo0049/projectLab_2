package com.example.demo.repository;

import com.example.demo.entity.StoreProductStatus;
import com.example.demo.entity.StoreProductStatusId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StoreProductStatusRepository extends JpaRepository<StoreProductStatus, StoreProductStatusId> {
    List<StoreProductStatus> findByStoreId(Long storeId);
    void deleteByProductId(Long productId);
}
