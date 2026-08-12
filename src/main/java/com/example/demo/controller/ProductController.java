package com.example.demo.controller;

import com.example.demo.entity.ProductTemplate;
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

    @PostMapping
    public ResponseEntity<ProductTemplate> saveProduct(@RequestBody ProductTemplate product) {
        return ResponseEntity.ok(productService.saveProduct(product));
    }

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
