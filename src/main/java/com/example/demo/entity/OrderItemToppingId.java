package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;
import java.io.Serializable;

@Embeddable
@Data
public class OrderItemToppingId implements Serializable {
    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "topping_name_snapshot")
    private String toppingNameSnapshot;
}
