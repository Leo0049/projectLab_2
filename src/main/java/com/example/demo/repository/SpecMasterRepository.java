package com.example.demo.repository;

import com.example.demo.entity.SpecMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SpecMasterRepository extends JpaRepository<SpecMaster, Long> {
    List<SpecMaster> findByType(String type);
    boolean existsByTypeAndName(String type, String name);
}
