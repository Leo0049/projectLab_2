package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.math.BigDecimal;

@Entity
@Table(name = "order_item_toppings")
@Data
public class OrderItemTopping {

    @EmbeddedId
    private OrderItemToppingId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("orderItemId")
    @JoinColumn(name = "order_item_id", foreignKey = @ForeignKey(name = "fk_order_item_toppings_order_item"))
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private OrderItem orderItem;

    @Column(name = "topping_price_snapshot", precision = 10, scale = 2)
    private BigDecimal toppingPriceSnapshot;
}
