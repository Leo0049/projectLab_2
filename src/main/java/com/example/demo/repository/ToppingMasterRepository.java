package com.example.demo.repository;

import com.example.demo.entity.ToppingMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ToppingMasterRepository extends JpaRepository<ToppingMaster, Long> {
    boolean existsByName(String name);
}
