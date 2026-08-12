package com.example.demo.repository;

import com.example.demo.entity.ProductToppingRule;
import com.example.demo.entity.ProductToppingRuleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ProductToppingRuleRepository extends JpaRepository<ProductToppingRule, ProductToppingRuleId> {
    @Query("SELECT r FROM ProductToppingRule r JOIN FETCH r.brandTopping bt LEFT JOIN FETCH bt.masterTopping WHERE r.id.productId = :productId")
    List<ProductToppingRule> findByIdProductId(@Param("productId") Long productId);
    void deleteByIdBrandToppingId(Long brandToppingId);

    @Modifying
    @Query("DELETE FROM ProductToppingRule r WHERE r.id.productId = :productId")
    void deleteByIdProductId(@Param("productId") Long productId);

    @Modifying
    @Query(value = "INSERT INTO product_topping_rule (product_id, brand_topping_id) VALUES (:productId, :toppingId)", nativeQuery = true)
    void insertRule(@Param("productId") Long productId, @Param("toppingId") Long toppingId);
}
