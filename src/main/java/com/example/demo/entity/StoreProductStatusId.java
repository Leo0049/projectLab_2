package com.example.demo.entity;

import jakarta.persistence.Embeddable;
import lombok.Data;
import java.io.Serializable;

@Embeddable
@Data
public class StoreProductStatusId implements Serializable {
    private Long storeId;
    private Long productId;
}
