package com.example.demo.repository;

import com.example.demo.entity.GroupOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<GroupOrder, Long> {
    @org.springframework.data.jpa.repository.Query("SELECT o FROM GroupOrder o WHERE o.initiator.id = :userId AND o.shareToken IS NULL ORDER BY o.createdAt DESC")
    List<GroupOrder> findByInitiatorIdOrderByCreatedAtDesc(
            @org.springframework.data.repository.query.Param("userId") Long userId);

    List<GroupOrder> findByStoreIdOrderByCreatedAtDesc(Long storeId);

    // @Procedure(procedureName = "proc_ProcessOrder")
    // String processOrder(@Param("p_UserID") Long userId,
    // @Param("p_StoreID") Long storeId,
    // @Param("p_TotalAmount") BigDecimal totalAmount,
    // @Param("p_DeliveryFee") BigDecimal deliveryFee,
    // @Param("p_CouponID") Long couponId,
    // @Param("p_FinalAmount") BigDecimal finalAmount,
    // @Param("p_OrderID") Long[] orderIdOut);
    // Note: Depending on JPA implementation, OUT parameters with return might need
    // a separate call or specific mapping.
    // In many cases, it's easier to use native query or JdbcTemplate for complex
    // stored procedures with multiple OUT params.
}
