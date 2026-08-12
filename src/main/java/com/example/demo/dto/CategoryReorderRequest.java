package com.example.demo.dto;

import lombok.Data;

import java.util.List;

@Data
public class CategoryReorderRequest {
    private List<Long> orderedCategoryIds;
}
