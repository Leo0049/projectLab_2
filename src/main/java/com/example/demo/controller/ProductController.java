package com.example.demo.controller;

import com.example.demo.service.ProductService;

import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/store/{storeId}")
    public ResponseEntity<List<com.example.demo.dto.ProductDTO>> getActiveProducts(@PathVariable Long storeId) {
        return ResponseEntity.ok(productService.getActiveProductsByStoreId(storeId));
    }

    @GetMapping("/store/{storeId}/all")
    public ResponseEntity<List<com.example.demo.dto.ProductDTO>> getStoreProducts(@PathVariable Long storeId) {
        return ResponseEntity.ok(productService.getAllProductsByStoreId(storeId));
    }

    // ⚠️ 已移除 POST /api/products。
    //    它直接接收原始 ProductTemplate 實體並 save()，而 /api/products/** 為 permitAll，
    //    等於任何人不需登入就能新增商品；又因 save() 具 merge 語意，帶入既有 id 即可
    //    竄改他人商品（實測可將售價改為 0.01）。品牌端建立/修改商品請改用需要 BRAND
    //    身分的 POST /api/brand/products 與 PUT /api/brand/products/{productId}。

    @GetMapping("/{productId}/specs")
    public ResponseEntity<List<com.example.demo.dto.ProductSpecDTO>> getProductSpecs(@PathVariable Long productId) {
        return ResponseEntity.ok(productService.getProductSpecs(productId));
    }

    @Operation(summary = "查詢商品客製化選項 (V2)", description = "查詢商品可選規格與配料，支援分店層級過濾（store_spec_settings、store_topping_settings）。")
    @GetMapping("/{productId}/customization/v2")
    public ResponseEntity<com.example.demo.dto.ProductCustomizationDTO> getProductCustomizationV2(
            @PathVariable Long productId,
            @RequestParam(required = false) Long storeId) {
        com.example.demo.dto.ProductCustomizationDTO customization = productService.getProductCustomization(productId, storeId);
        return customization != null ? ResponseEntity.ok(customization) : ResponseEntity.notFound().build();
    }
}
