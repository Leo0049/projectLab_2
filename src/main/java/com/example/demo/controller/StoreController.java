package com.example.demo.controller;

import com.example.demo.common.Result;

import com.example.demo.dto.StoreDTO;
import com.example.demo.dto.StoreRequest;
import com.example.demo.entity.Store;
import com.example.demo.entity.StoreToppingStatus;
import com.example.demo.entity.StoreToppingStatusId;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.BrandToppingSettingRepository;
import com.example.demo.repository.StoreToppingStatusRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.service.AnalyticsService;
import com.example.demo.service.BrandService;
import com.example.demo.service.StoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Transactional;

@lombok.extern.slf4j.Slf4j
@RestController
@RequestMapping("/api/stores")
@Tag(name = "分店後台 (STORE)", description = "分店認證、基本資料、設定、訂單管理、庫存、Dashboard、財務")
public class StoreController {
    // 1
    @Autowired
    private StoreService storeService;
    @Autowired
    private AnalyticsService analyticsService;
    @Autowired
    private BrandService brandService;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private StoreToppingStatusRepository storeToppingStatusRepository;
    @Autowired
    private BrandToppingSettingRepository brandToppingSettingRepository;
    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private com.example.demo.service.ImageStorageService imageStorageService;

    // ==================== 認證 (公開) ====================

    @Operation(summary = "分店登入", description = "分店帳號密碼登入。\n\nBody: { account, password }\n\n回傳 JWT Token（role = STORE）。")
    @PostMapping("/auth/login")
    public Result login(@RequestBody Map<String, String> body) {
        String account = body.get("account"), password = body.get("password");
        if (account == null || password == null)
            return Result.error("400", "帳號與密碼不能為空");
        return Result.success(storeService.login(account, password));
    }

    @Operation(summary = "上傳圖片", description = "分店專用圖片上傳。有設定 Cloudinary 憑證則上傳雲端，否則存於本機 /uploads。")
    @PostMapping("/upload-image")
    public Result uploadImage(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            return Result.success(imageStorageService.upload(file, "stores", null));
        } catch (Exception e) {
            // 例外細節（含函式庫原始訊息）只寫進 log，不回傳給前端
            log.error("分店圖片上傳失敗", e);
            return Result.error("500", "圖片上傳失敗，請稍後再試");
        }
    }

    // ==================== 基本資料 ====================

    @Operation(summary = "取得分店公開資訊", description = "查詢指定分店基本資料。無需登入。")
    @GetMapping("/{id}")
    public Result getStore(@PathVariable Long id) {
        return Result.success(storeService.getStoreByIdV2(id));
    }

    @Operation(summary = "更新分店資料", description = "更新目前登入分店的資料。\n\n"
            + "Body: { storeName?, coverUrl?, managerName?, managerPhone?, address?, openingHours?, regionId? }\n"
            + "※ 分店 id 取自 JWT，body 帶入的 id 若與登入身分不符會回 403。")
    @PutMapping("/update")
    public Result updateStore(@RequestAttribute("currentUserId") Long storeId,
            @RequestBody StoreRequest req) {
        storeService.updateStore(storeId, req);
        return Result.success("店家資訊更新成功");
    }

    @Operation(summary = "更新營業狀態", description = "切換分店的營業狀態。\n\nBody: { status }\n- status: open / closed")
    @PutMapping("/status")
    public Result updateStatus(@RequestAttribute("currentUserId") Long storeId,
            @RequestBody Map<String, String> body) {
        storeService.updateStatus(storeId, body.get("status"));
        return Result.success();
    }

    // ==================== 設定 ====================

    @Operation(summary = "取得分店設定資料", description = "查詢目前分店的完整設定資料。")
    @GetMapping("/settings/profile")
    public Result getProfile(@RequestAttribute("currentUserId") Long storeId) {
        return Result.success(storeService.getProfile(storeId));
    }

    @Operation(summary = "更新營業時間", description = "設定分店每日營業時間。\n\nBody: { openingHours: \"09:00-22:00\" }")
    @PutMapping("/settings/business-hours")
    public Result updateBusinessHours(@RequestAttribute("currentUserId") Long storeId,
            @RequestBody Map<String, Object> body) {
        storeService.updateBusinessHours(storeId, body.get("openingHours"));
        return Result.success("營業時間更新成功");
    }

    @Operation(summary = "更新外送設定", description = "更新外送開關、距離與最低金額。\n\nBody: { allowsDelivery: true/false, maxDeliveryKm: 5.0, minDeliveryAmount: 0 }")
    @PatchMapping("/settings/delivery-config")
    public Result updateDeliveryConfig(@RequestAttribute("currentUserId") Long storeId,
            @RequestBody Map<String, Object> body) {
        Boolean allowsDelivery = body.get("allowsDelivery") instanceof Boolean b ? b : null;
        java.math.BigDecimal maxKm = body.get("maxDeliveryKm") != null
                ? new java.math.BigDecimal(body.get("maxDeliveryKm").toString())
                : null;
        java.math.BigDecimal minAmount = body.get("minDeliveryAmount") != null
                ? new java.math.BigDecimal(body.get("minDeliveryAmount").toString())
                : null;
        String startTime = body.get("deliveryStartTime") instanceof String s ? s : null;
        String endTime = body.get("deliveryEndTime") instanceof String s ? s : null;
        storeService.updateDeliveryConfig(storeId, allowsDelivery, maxKm, minAmount, startTime, endTime);
        return Result.success("外送設定更新成功");
    }

    // ==================== 訂單管理 ====================

    @Operation(summary = "訂單列表", description = "取得分店訂單列表，支援 status 篩選。\n\nQuery Param: status = OPEN / SUBMITTED / READY / COMPLETED / REJECTED / CANCELLED（不帶則查全部）")
    @GetMapping("/orders")
    public Result getOrders(@RequestAttribute("currentUserId") Long storeId,
            @RequestParam(required = false) String status) {
        return Result.success(analyticsService.getStoreOrders(storeId, status));
    }

    @Operation(summary = "待處理訂單列表", description = "取得目前所有 status = OPEN 的待接單列表。\n\n回傳: [{ orderId, orderNo, type, totalAmount, createdAt }]")
    @GetMapping("/dashboard/orders/pending")
    public Result getPendingOrders(@RequestAttribute("currentUserId") Long storeId) {
        return Result.success(analyticsService.getStorePendingOrders(storeId));
    }

    @Operation(summary = "接單", description = "確認接受訂單，狀態從 OPEN 改為 SUBMITTED（製作中）。")
    @PostMapping("/dashboard/orders/{orderId}/accept")
    public Result acceptOrder(@PathVariable Long orderId,
            @RequestAttribute("currentUserId") Long storeId) {
        analyticsService.acceptOrder(orderId, storeId);
        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/order/" + orderId, Map.of("status", "PREPARING"));
        }
        return Result.success(analyticsService.getOrderDetail(orderId));
    }

    @Operation(summary = "拒單", description = "拒絕訂單，狀態從 OPEN 改為 REJECTED。")
    @PostMapping("/dashboard/orders/{orderId}/reject")
    public Result rejectOrder(@PathVariable Long orderId,
            @RequestAttribute("currentUserId") Long storeId) {
        analyticsService.rejectOrder(orderId, storeId);
        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/order/" + orderId, Map.of("status", "REJECTED"));
        }
        return Result.success("拒單成功");
    }

    @Operation(summary = "製作完成", description = "標記訂單製作完成，狀態從 SUBMITTED 改為 READY（待取餐/待配送）。")
    @PostMapping("/dashboard/orders/{orderId}/complete-production")
    public Result completeProduction(@PathVariable Long orderId,
            @RequestAttribute("currentUserId") Long storeId) {
        analyticsService.completeProduction(orderId, storeId);
        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/order/" + orderId, Map.of("status", "READY"));
        }
        return Result.success(analyticsService.getOrderDetail(orderId));
    }

    @Operation(summary = "結案", description = "配送送達或客戶現場取餐，狀態從 READY 改為 COMPLETED。")
    @PostMapping("/dashboard/orders/{orderId}/finalize")
    public Result finalizeOrder(@PathVariable Long orderId,
            @RequestAttribute("currentUserId") Long storeId) {
        analyticsService.finalizeOrder(orderId, storeId);
        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/order/" + orderId, Map.of("status", "COMPLETED"));
        }
        return Result.success(analyticsService.getOrderDetail(orderId));
    }

    // ==================== 庫存管理 (飲品) ====================

    @Operation(summary = "取得分店飲品列表", description = "取得品牌所有飲品模板，並合併分店的供應狀態。")
    @GetMapping("/menu/drinks")
    public Result getStoreDrinks(@RequestAttribute("currentUserId") Long storeId) {
        return Result.success(analyticsService.getStoreMenuDrinks(storeId));
    }

    @Operation(summary = "切換分店飲品供應狀態", description = "切換指定飲品的供應狀態（isAvailable: true/false）。\n\nBody: { isAvailable }")
    @PatchMapping("/menu/drinks/{drinkId}/toggle")
    public Result toggleDrinkSupply(@RequestAttribute("currentUserId") Long storeId,
            @PathVariable Long drinkId,
            @RequestBody Map<String, Boolean> body) {
        analyticsService.toggleDrinkSupply(storeId, drinkId, body.get("isAvailable"));
        return Result.success();
    }

    // ==================== 庫存管理 (配料) ====================

    @Operation(summary = "取得分店配料列表", description = "取得品牌配料列表，並合併分店的供應狀態。")
    @GetMapping("/menu/toppings")
    @Transactional(readOnly = true)
    public Result getStoreToppings(@RequestAttribute("currentUserId") Long storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException("404", "店家不存在"));
        if (store.getBrand() == null)
            return Result.success(Collections.emptyList());

        // 取得分店缺貨設定 (store_topping_settings)
        Map<Long, Boolean> outOfStockMap = storeToppingStatusRepository.findByIdStoreId(storeId)
                .stream().collect(Collectors.toMap(
                        s -> s.getId().getBrandToppingId(),
                        s -> Boolean.TRUE.equals(s.getIsOutOfStock())));

        // 只顯示品牌已啟用的配料，並以分店缺貨狀態覆蓋 isEnabled
        List<Map<String, Object>> toppings = brandService.getBrandToppings(store.getBrand().getId())
                .stream()
                .filter(t -> Boolean.TRUE.equals(t.get("isEnabled"))) // 只取品牌已啟用
                .peek(t -> {
                    Long btId = ((Number) t.get("brandToppingId")).longValue();
                    boolean outOfStock = outOfStockMap.getOrDefault(btId, false);
                    t.put("isEnabled", !outOfStock); // 分店未缺貨 → 供應中
                })
                .collect(Collectors.toList());
        return Result.success(toppings);
    }

    @Operation(summary = "切換分店配料供應狀態", description = "切換指定配料的供應狀態（isAvailable: true/false）。\n\nBody: { isAvailable }")
    @PatchMapping("/menu/toppings/{brandToppingId}/toggle")
    @Transactional
    public Result toggleStoreTopping(@RequestAttribute("currentUserId") Long storeId,
            @PathVariable Long brandToppingId,
            @RequestBody Map<String, Boolean> body) {
        storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException("404", "店家不存在"));
        brandToppingSettingRepository.findById(brandToppingId)
                .orElseThrow(() -> new CustomException("404", "配料不存在"));

        StoreToppingStatusId statusId = new StoreToppingStatusId();
        statusId.setStoreId(storeId);
        statusId.setBrandToppingId(brandToppingId);

        StoreToppingStatus status = storeToppingStatusRepository.findById(statusId)
                .orElseGet(() -> {
                    Store st = storeRepository.getReferenceById(storeId);
                    com.example.demo.entity.BrandToppingSetting bt = brandToppingSettingRepository
                            .getReferenceById(brandToppingId);
                    StoreToppingStatus s = new StoreToppingStatus();
                    s.setId(statusId);
                    s.setStore(st);
                    s.setBrandTopping(bt);
                    s.setIsOutOfStock(false);
                    return s;
                });

        status.setIsOutOfStock(!body.get("isAvailable"));
        storeToppingStatusRepository.save(status);

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("brandToppingId", brandToppingId);
        result.put("isEnabled", !status.getIsOutOfStock());
        return Result.success(result);
    }

    @Operation(summary = "取得分店進行中訂單", description = "取得 OPEN / SUBMITTED / READY 三種狀態的訂單，用於首頁訂單預覽。")
    @GetMapping("/dashboard/orders/active")
    public Result getActiveOrders(@RequestAttribute("currentUserId") Long storeId) {
        return Result.success(analyticsService.getStoreActiveOrders(storeId));
    }

    // ==================== Dashboard ====================

    @Operation(summary = "今日概覽", description = "查詢分店今日訂單數、總營收、平均客單價。\n\n回傳: { orderCount, totalRevenue, avgOrderValue }")
    @GetMapping("/dashboard/today-summary")
    public Result getTodaySummary(@RequestAttribute("currentUserId") Long storeId) {
        return Result.success(analyticsService.getStoreTodaySummary(storeId));
    }

    @Operation(summary = "銷售趨勢與排行", description = "今日各時段訂單分佈 + 熱門飲品前5名。\n\n回傳: { hourlySales: [{ hour, count }], topProducts: [{ rank, productName, count, revenue }] }")
    @GetMapping("/dashboard/analytics")
    public Result getAnalytics(@RequestAttribute("currentUserId") Long storeId) {
        return Result.success(analyticsService.getStoreAnalytics(storeId));
    }

    @Operation(summary = "本周銷售趨勢", description = "本周（週一～週日）每日營收。\n\n回傳: { labels: [\"週一\",...], values: [0, 1200, ...] }")
    @GetMapping("/dashboard/sales-trend")
    public Result getSalesTrend(@RequestAttribute("currentUserId") Long storeId) {
        return Result.success(analyticsService.getStoreSalesTrend(storeId));
    }

    // ==================== 數據報表 ====================

    @Operation(summary = "商品排行報表", description = "取得分店指定期間的熱門商品排行。\n\nQuery Param: period = week / month / year")
    @GetMapping("/reports/product-ranking")
    public Result getProductRanking(@RequestAttribute("currentUserId") Long storeId,
            @RequestParam(defaultValue = "month") String period) {
        return Result.success(analyticsService.getStoreProductRanking(storeId, period));
    }

    @Operation(summary = "近期營收明細", description = "取得分店最近 10 天的日營收彙總。")
    @GetMapping("/reports/recent-daily")
    public Result getRecentDailyReports(@RequestAttribute("currentUserId") Long storeId) {
        return Result.success(analyticsService.getStoreRecentDailyReports(storeId));
    }

    @Operation(summary = "評分統計報表", description = "取得分店評分分佈與最近評論。")
    @GetMapping("/reports/rating-stats")
    public Result getRatingStats(@RequestAttribute("currentUserId") Long storeId) {
        return Result.success(analyticsService.getStoreRatingStats(storeId));
    }

    // ==================== 財務 ====================

    @Operation(summary = "分店財務摘要", description = "查詢分店財務概況。\n\nQuery Param: period = week / month / year（預設 month）")
    @GetMapping("/finance/summary")
    public Result getFinanceSummary(@RequestAttribute("currentUserId") Long storeId,
            @RequestParam(defaultValue = "month") String period) {
        return Result.success(analyticsService.getStoreFinanceSummary(storeId, period));
    }

    @Operation(summary = "分店財務分類分析", description = "按飲品類別統計營收與佔比。")
    @GetMapping("/finance/categorized")
    public Result getFinanceCategorized(@RequestAttribute("currentUserId") Long storeId,
            @RequestParam(defaultValue = "month") String period) {
        return Result.success(analyticsService.getStoreFinanceCategorized(storeId, period));
    }

    @Operation(summary = "分店佣金明細", description = "查詢分店各筆佣金明細列表。\n\nQuery Param: period = week / month / year（預設 month）")
    @GetMapping("/finance/commissions")
    public Result getCommissions(@RequestAttribute("currentUserId") Long storeId,
            @RequestParam(defaultValue = "month") String period) {
        return Result.success(analyticsService.getStoreCommissionDetails(storeId, period));
    }

    @Operation(summary = "各類別營收趨勢圖", description = "按類別分組的每日營收趨勢 (本週/上週)")
    @GetMapping("/finance/category-trend")
    public Result getFinanceCategoryTrend(@RequestAttribute("currentUserId") Long storeId,
            @RequestParam(defaultValue = "this-week") String period) {
        return Result.success(analyticsService.getStoreCategoryTrend(storeId, period));
    }

    @Operation(summary = "透過品牌 ID 查詢分店", description = "取得指定品牌下所有分店。")
    @GetMapping("/brand/{brandId}")
    public ResponseEntity<List<Store>> getStoresByBrandId(@PathVariable Long brandId) {
        return ResponseEntity.ok(storeService.getStoresByBrandId(brandId));
    }

    // ============================================================
    // methods (from 待處理/StoreController — ResponseEntity-based)
    // ============================================================

    @Operation(summary = "附近店家列表 (V2)", description = "舊版依距離排序查詢附近店家，包含排序與分類篩選。回傳 ResponseEntity 格式。")
    @GetMapping("/nearby/v2")
    public ResponseEntity<List<StoreDTO>> getNearbyStoresV2(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam(required = false, defaultValue = "distance") String sort,
            @RequestParam(required = false, defaultValue = "all") String category,
            @RequestParam(required = false, defaultValue = "false") boolean freeDelivery) {
        List<StoreDTO> stores = storeService.getOpenStoresSortedByDistance(lat, lon, sort, category, freeDelivery);
        return ResponseEntity.ok(stores);
    }

    @Operation(summary = "取得分店詳情 (V2)", description = "舊版依 storeId 查詢分店詳情。回傳 ResponseEntity 格式。")
    @GetMapping("/{storeId}/v2")
    public ResponseEntity<Store> getStoreByIdV2(@PathVariable Long storeId) {
        Store store = storeService.getStoreById(storeId);
        return store != null ? ResponseEntity.ok(store) : ResponseEntity.notFound().build();
    }
}
