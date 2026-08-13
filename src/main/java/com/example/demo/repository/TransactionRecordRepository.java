package com.example.demo.repository;

import com.example.demo.entity.TransactionRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, Long> {
    List<TransactionRecord> findByUserIdOrderByCreatedAtDesc(Long userId);
    Page<TransactionRecord> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    List<TransactionRecord> findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(Long userId, LocalDateTime after);

    Page<TransactionRecord> findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(Long userId, LocalDateTime after,
            Pageable pageable);
}
