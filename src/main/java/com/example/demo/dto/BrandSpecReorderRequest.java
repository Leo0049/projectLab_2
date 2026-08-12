package com.example.demo.dto;

import lombok.Data;

import java.util.List;

@Data
public class BrandSpecReorderRequest {
    private String type;
    private List<Long> orderedSpecIds;
}
