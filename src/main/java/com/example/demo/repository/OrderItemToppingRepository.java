package com.example.demo.repository;

import com.example.demo.entity.OrderItemTopping;
import com.example.demo.entity.OrderItemToppingId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface OrderItemToppingRepository extends JpaRepository<OrderItemTopping, OrderItemToppingId> {

    @Query("SELECT t FROM OrderItemTopping t WHERE t.orderItem.id IN :orderItemIds")
    List<OrderItemTopping> findByOrderItemIdIn(@Param("orderItemIds") List<Long> orderItemIds);

    List<OrderItemTopping> findByOrderItemId(Long orderItemId);

    @Modifying
    @Transactional
    @Query("DELETE FROM OrderItemTopping t WHERE t.id.orderItemId = :orderItemId")
    void deleteByOrderItemId(@Param("orderItemId") Long orderItemId);
}
