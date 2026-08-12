package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.BrandSpecReorderRequest;
import com.example.demo.service.BrandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "品牌規格排序", description = "品牌後台規格拖曳排序")
public class BrandSpecOrderController {

    @Autowired
    private BrandService brandService;

    @Operation(summary = "重新排序品牌規格", description = "Body: { type: ICE|SWEETNESS|SIZE, orderedSpecIds: [3,1,2] }")
    @PatchMapping("/api/brand/specs/reorder")
    public Result reorderSpecs(@RequestAttribute("currentUserId") Long brandId,
                               @RequestBody BrandSpecReorderRequest req) {
        return Result.success(brandService.reorderBrandSpecs(brandId, req));
    }
}
