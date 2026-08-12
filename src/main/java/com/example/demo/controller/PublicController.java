package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.ProductService;
import com.example.demo.service.PublicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Public（公開）", description = "首頁、附近店家、搜尋、品牌、商品（不需登入）")
public class PublicController {

    @Autowired private PublicService publicService;
    @Autowired private ProductService productService;

    // ─── 首頁 ────────────────────────────────────────────

    @Operation(summary = "首頁資料", description = "取得 banners、附近店家、轉盤狀態。\n\nQuery: ?lat=&lng=")
    @GetMapping("/api/home")
    public Result getHome(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestAttribute(value = "currentUserId", required = false) Long userId) {
        return Result.success(publicService.getHome(lat, lng, userId));
    }

    // ─── 店家探索 ─────────────────────────────────────────

    @Operation(summary = "地圖店家", description = "取得所有營業中店家的位置，用於地圖顯示。\n\nQuery: ?lat=&lng=")
    @GetMapping("/api/stores/map")
    public Result getMapStores(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        return Result.success(publicService.getMapStores(lat, lng));
    }

    @Operation(summary = "附近店家列表",
            description = "依使用者位置回傳附近店家，支援品牌篩選與評分篩選。\n\nQuery: ?lat=&lng=&brandId=&minRating=&page=1&size=6")
    @GetMapping("/api/stores/nearby")
    public Result getNearbyStores(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Double minRating,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "6") int size,
            @RequestAttribute(value = "currentUserId", required = false) Long userId) {
        return Result.success(publicService.getNearbyStoresPage(lat, lng, brandId, minRating, page, size, userId));
    }
//1
    @Operation(summary = "搜尋店家", description = "依品牌名稱關鍵字搜尋，回傳店家卡片列表。\n\nQuery: ?keyword=50嵐&lat=&lng=&page=1&size=10")
    @GetMapping("/api/stores/search")
    public Result searchStores(
            @RequestParam String keyword,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestAttribute(value = "currentUserId", required = false) Long userId) {
        return Result.success(publicService.searchStores(keyword, lat, lng, page, size, userId));
    }

    @Operation(summary = "店家公開資訊", description = "取得店家詳細資訊，包含品牌介紹、評分、營業時間。")
    @GetMapping("/api/stores/{storeId}/info")
    public Result getStoreInfo(
            @PathVariable Long storeId,
            @RequestAttribute(value = "currentUserId", required = false) Long userId) {
        return Result.success(publicService.getStoreDetail(storeId, userId));
    }

    // ─── 品牌 ────────────────────────────────────────────

    @Operation(summary = "品牌列表", description = "取得平台所有品牌，用於篩選下拉選單。")
    @GetMapping("/api/brands")
    public Result getBrands() {
        return Result.success(publicService.getBrands());
    }

    @Operation(summary = "品牌詳情", description = "取得品牌資訊與旗下所有分店列表。")
    @GetMapping("/api/brands/{brandId}")
    public Result getBrandDetail(@PathVariable Long brandId) {
        return Result.success(publicService.getBrandDetail(brandId));
    }

    // ─── 商品 ─────────────────────────────────────────────

    @Operation(summary = "商品詳情", description = "取得飲品基本資訊（名稱、描述、圖片、價格）。")
    @GetMapping("/api/products/{productId}")
    public Result getProduct(@PathVariable Long productId) {
        return Result.success(productService.getProductDetail(productId));
    }

    @Operation(summary = "商品客製化選項", description = "取得飲品可選規格（甜度/冰塊/杯型）與可加配料，合併成單一 API。")
    @GetMapping("/api/products/{productId}/customization")
    public Result getCustomization(@PathVariable Long productId) {
        return Result.success(productService.getProductCustomization(productId));
    }
}
