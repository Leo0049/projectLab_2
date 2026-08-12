package com.example.demo.dto;

import lombok.Data;

import java.math.BigDecimal;

// ✏️ 修改：類別名稱改為單數 StoreRequest（原本是 StoresRequest）
@Data
public class StoreRequest {
    private Long id;
    private String storeName;
    private String coverUrl;
    private String managerName;
    private String managerPhone;
    private String address;
    private Long regionId;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private Object openingHours;
}
