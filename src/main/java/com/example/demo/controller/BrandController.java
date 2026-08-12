package com.example.demo.controller;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.example.demo.common.Result;
import com.example.demo.dto.CategoryReorderRequest;
import com.example.demo.dto.CreateCategoryRequest;
import com.example.demo.dto.CreateProductRequest;
import com.example.demo.dto.StoreRequest;
import com.example.demo.dto.UpdateProductRequest;
import com.example.demo.service.StoreService;
import com.example.demo.service.AnalyticsService;
import com.example.demo.service.BrandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@Tag(name = "品牌後台 (BRAND)", description = "品牌認證、分類管理、飲品管理、優惠券、Dashboard、財務")
public class BrandController {

    @Autowired
    private BrandService brandService;
    @Autowired
    private StoreService storeService;
    @Autowired
    private AnalyticsService analyticsService;
    @Autowired
    private Cloudinary cloudinary;

    // ==================== 認證 (公開) ====================

    @Operation(summary = "品牌註冊", description = "建立品牌帳號。\n\nBody: { name, account, password }")
    @PostMapping("/api/brand-auth/register")
    public Result register(@RequestBody Map<String, String> body) {
        String name = body.get("name"), account = body.get("account"), password = body.get("password");
        if (name == null || account == null || password == null)
            return Result.error("400", "品牌名稱、帳號與密碼不能為空");
        brandService.register(name, account, password);
        return Result.success();
    }

    @Operation(summary = "品牌登入", description = "品牌帳號密碼登入。\n\nBody: { account, password }\n\n回傳 JWT Token（role = BRAND）。")
    @PostMapping("/api/brand-auth/login")
    public Result login(@RequestBody Map<String, String> body) {
        String account = body.get("account"), password = body.get("password");
        if (account == null || password == null)
            return Result.error("400", "帳號與密碼不能為空");
        return Result.success(brandService.login(account, password));
    }

    @Operation(summary = "建立分店帳號", description = "品牌替旗下建立分店帳號。\n\nBody: { storeName, account, password }")
    @PostMapping("/api/brand-auth/create-store")
    public Result createStore(@RequestAttribute("currentUserId") Long brandId,
            @RequestBody Map<String, Object> body) {
        String storeName = body.get("storeName") != null ? body.get("storeName").toString() : null;
        String account = body.get("account") != null ? body.get("account").toString() : null;
        String password = body.get("password") != null ? body.get("password").toString() : null;
        if (storeName == null || account == null || password == null)
            return Result.error("400", "店名、帳號與密碼不能為空");
        Long regionId = body.get("regionId") != null ? Long.parseLong(body.get("regionId").toString()) : null;
        String managerName = body.get("managerName") != null ? body.get("managerName").toString() : null;
        String managerPhone = body.get("managerPhone") != null ? body.get("managerPhone").toString() : null;
        String address = body.get("address") != null ? body.get("address").toString() : null;
        String coverUrl = body.get("coverUrl") != null ? body.get("coverUrl").toString() : null;
        java.math.BigDecimal latitude = body.get("latitude") != null
                ? new java.math.BigDecimal(body.get("latitude").toString())
                : null;
        java.math.BigDecimal longitude = body.get("longitude") != null
                ? new java.math.BigDecimal(body.get("longitude").toString())
                : null;
        Object openingHours = body.get("openingHours");
        return Result.success(brandService.createStore(
                brandId, storeName, account, password, regionId, managerName, managerPhone, address,
                coverUrl, latitude, longitude, openingHours));
    }

    @Operation(summary = "上傳圖片", description = "上傳圖片至 Cloudinary，回傳圖片 URL。\n\nForm: file (multipart), folder (optional, default: stores)")
    @PostMapping("/api/brand/upload-image")
    public Result uploadImage(@RequestAttribute("currentUserId") Long brandId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folder", defaultValue = "stores") String folder) {
        try {
            java.util.Map<?, ?> uploadResult = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap("folder", folder, "resource_type", "image"));
            return Result.success(uploadResult.get("secure_url"));
        } catch (Exception e) {
            return Result.error("500", "圖片上傳失敗：" + e.getMessage());
        }
    }

    @Operation(summary = "Create store with images", description = "Create store and optionally upload avatar / cover images to Cloudinary in one request")
    @PostMapping("/api/brand-auth/create-store-with-images")
    public Result createStoreWithImages(@RequestAttribute("currentUserId") Long brandId,
            @RequestParam("storeName") String storeName,
            @RequestParam("account") String account,
            @RequestParam("password") String password,
            @RequestParam(value = "regionId", required = false) Long regionId,
            @RequestParam(value = "managerName", required = false) String managerName,
            @RequestParam(value = "managerPhone", required = false) String managerPhone,
            @RequestParam(value = "address", required = false) String address,
            @RequestParam(value = "latitude", required = false) java.math.BigDecimal latitude,
            @RequestParam(value = "longitude", required = false) java.math.BigDecimal longitude,
            @RequestParam(value = "openingHours", required = false) String openingHours,
            @RequestParam(value = "coverFile", required = false) MultipartFile coverFile) {
        try {
            String coverUrl = uploadStoreAsset(coverFile, "stores");
            return Result.success(brandService.createStore(
                    brandId, storeName, account, password, regionId, managerName, managerPhone, address,
                    coverUrl, latitude, longitude, openingHours));
        } catch (Exception e) {
            return Result.error("500", "Create store with images failed: " + e.getMessage());
        }
    }

    private String uploadStoreAsset(MultipartFile file, String folder) throws Exception {
        if (file == null || file.isEmpty()) {
            return null;
        }
        java.util.Map<?, ?> uploadResult = cloudinary.uploader().upload(
                file.getBytes(),
                ObjectUtils.asMap("folder", folder, "resource_type", "image"));
        return String.valueOf(uploadResult.get("secure_url"));
    }

    @Operation(summary = "Upload brand logo", description = "Upload brand logo to Cloudinary and save secure_url into brands.logo_url")
    @PostMapping("/api/brand/logo")
    public Result uploadBrandLogo(@RequestAttribute("currentUserId") Long brandId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folder", defaultValue = "brands") String folder) {
        try {
            java.util.Map<?, ?> uploadResult = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap("folder", folder, "resource_type", "image"));
            String logoUrl = String.valueOf(uploadResult.get("secure_url"));
            return Result.success(brandService.updateBrandLogo(brandId, logoUrl));
        } catch (Exception e) {
            return Result.error("500", "Upload brand logo failed: " + e.getMessage());
        }
    }

    @Operation(summary = "取得地區列表", description = "回傳所有可用地區（北部/中部/南部）。")
    @GetMapping("/api/brand/regions")
    public Result getRegions() {
        return Result.success(brandService.getRegions());
    }

    // ==================== 分類管理 ====================

    @Operation(summary = "查詢所有分類", description = "取得品牌旗下所有飲品分類列表。")
    @GetMapping("/api/brand/categories")
    public Result getCategories(@RequestAttribute("currentUserId") Long brandId) {
        return Result.success(brandService.getCategories(brandId));
    }

    @Operation(summary = "批量建立分類", description = "一次建立多個飲品分類，系統自動合成優惠券圖片上傳至 Cloudinary。\n\nBody: { names: [\"奶茶\", \"果茶\", \"冰沙\"] }")
    @PostMapping("/api/brand/categories")
    public Result createCategories(@RequestAttribute("currentUserId") Long brandId,
            @RequestBody CreateCategoryRequest req) {
        return Result.success(brandService.createCategories(brandId, req));
    }

    @Operation(summary = "刪除分類", description = "刪除指定飲品分類。只能刪除自己品牌的分類。")
    @DeleteMapping("/api/brand/categories/{categoryId}")
    public Result deleteCategory(@PathVariable Long categoryId,
            @RequestAttribute("currentUserId") Long brandId) {
        brandService.deleteCategory(categoryId, brandId);
        return Result.success("刪除成功");
    }

    @Operation(summary = "重新命名分類", description = "修改指定分類的名稱。分類名稱不可重複且不可為「全部」。\n\nBody: { name: \"新名稱\" }")
    @PutMapping("/api/brand/categories/{categoryId}")
    public Result renameCategory(@PathVariable Long categoryId,
            @RequestAttribute("currentUserId") Long brandId,
            @RequestBody java.util.Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.trim().isEmpty())
            return Result.error("400", "分類名稱不可為空");
        return Result.success(brandService.renameCategory(categoryId, brandId, name));
    }

    @PatchMapping("/api/brand/categories/{categoryId}/move-up")
    public Result moveCategoryUp(@PathVariable Long categoryId,
            @RequestAttribute("currentUserId") Long brandId) {
        brandService.moveCategory(categoryId, brandId, -1);
        return Result.success("ok");
    }

    @PatchMapping("/api/brand/categories/{categoryId}/move-down")
    public Result moveCategoryDown(@PathVariable Long categoryId,
            @RequestAttribute("currentUserId") Long brandId) {
        brandService.moveCategory(categoryId, brandId, 1);
        return Result.success("ok");
    }

    @PatchMapping("/api/brand/categories/reorder")
    public Result reorderCategories(@RequestAttribute("currentUserId") Long brandId,
            @RequestBody CategoryReorderRequest req) {
        brandService.reorderCategories(brandId, req.getOrderedCategoryIds());
        return Result.success("ok");
    }

    // ==================== 飲品管理 ====================

    @Operation(summary = "查詢所有飲品", description = "取得品牌旗下所有 product_templates 飲品列表。\n\n回傳: [{ productId, categoryId, categoryName, name, basePrice, maxToppings, logoUrl }]")
    @GetMapping("/api/brand/products")
    public Result getProducts(@RequestAttribute("currentUserId") Long brandId) {
        return Result.success(brandService.getProducts(brandId));
    }

    @Operation(summary = "新增飲品", description = "在 product_templates 新增一款飲品，所有分店自動繼承。\n\nBody: { categoryId, name, basePrice, maxToppings?, logoUrl? }")
    @PostMapping("/api/brand/products")
    public Result createProduct(@RequestAttribute("currentUserId") Long brandId,
            @RequestBody CreateProductRequest req) {
        return Result.success(brandService.createProduct(brandId, req));
    }

    @Operation(summary = "更新飲品", description = "修改指定飲品資料（欄位皆選填）。\n\nBody: { categoryId?, name?, basePrice?, maxToppings?, logoUrl? }")
    @PutMapping("/api/brand/products/{productId}")
    public Result updateProduct(@RequestAttribute("currentUserId") Long brandId,
            @PathVariable Long productId,
            @RequestBody UpdateProductRequest req) {
        return Result.success(brandService.updateProduct(brandId, productId, req));
    }

    @Operation(summary = "刪除飲品", description = "從 product_templates 刪除指定飲品。只能刪除自己品牌的飲品。")
    @DeleteMapping("/api/brand/products/{productId}")
    public Result deleteProduct(@RequestAttribute("currentUserId") Long brandId,
            @PathVariable Long productId) {
        brandService.deleteProduct(brandId, productId);
        return Result.success("刪除成功");
    }

    @PatchMapping("/api/brand/products/{productId}/move-up")
    public Result moveProductUp(@RequestAttribute("currentUserId") Long brandId,
            @PathVariable Long productId) {
        brandService.moveProduct(brandId, productId, -1);
        return Result.success("ok");
    }

    @PatchMapping("/api/brand/products/{productId}/move-down")
    public Result moveProductDown(@RequestAttribute("currentUserId") Long brandId,
            @PathVariable Long productId) {
        brandService.moveProduct(brandId, productId, 1);
        return Result.success("ok");
    }

    // ==================== Dashboard ====================

    @Operation(summary = "品牌今日總覽", description = "今日整體營收 / 訂單數 / 客單價，附昨日對比趨勢百分比。\n\n回傳: { total_revenue, revenue_trend, total_orders, order_trend, avg_order_value, aov_trend }")
    @GetMapping("/api/brand/dashboard/overview")
    public Result getBrandOverview(@RequestAttribute("currentUserId") Long brandId) {
        return Result.success(analyticsService.getBrandDailyOverview(brandId));
    }

    @Operation(summary = "分店營業狀態統計", description = "統計所有分店目前的營業狀態。\n\n回傳: { open, closed, abnormal, total }")
    @GetMapping("/api/brand/dashboard/storestatus")
    public Result getStoreStatus(@RequestAttribute("currentUserId") Long brandId) {
        return Result.success(analyticsService.getStoreStatus(brandId));
    }

    @Operation(summary = "本週業績前五名分店", description = "本週業績排名前五的分店，附上週業績趨勢。\n\n回傳: [{ rank, storeId, storeName, revenue, trend(UP/DOWN/FLAT) }]")
    @GetMapping("/api/brand/dashboard/top-stores")
    public Result getTop5StoreRevenue(@RequestAttribute("currentUserId") Long brandId) {
        return Result.success(analyticsService.getTop5StoreRevenue(brandId));
    }

    // ==================== 規格管理 ====================

    @Operation(summary = "取得品牌規格列表", description = "回傳品牌所有已啟用的規格，依 ICE / SWEETNESS / SIZE 分組。")
    @GetMapping("/api/brand/specs")
    public Result getSpecs(@RequestAttribute("currentUserId") Long brandId) {
        return Result.success(brandService.getBrandSpecs(brandId));
    }

    @Operation(summary = "新增規格", description = "Body: { type: ICE|SWEETNESS|SIZE, name: \"去冰\" }")
    @PostMapping("/api/brand/specs")
    public Result addSpec(@RequestAttribute("currentUserId") Long brandId,
            @RequestBody java.util.Map<String, String> body) {
        return Result.success(brandService.addBrandSpec(brandId, body.get("type"), body.get("name")));
    }

    @Operation(summary = "刪除規格")
    @DeleteMapping("/api/brand/specs/{brandSpecId}")
    public Result deleteSpec(@PathVariable Long brandSpecId,
            @RequestAttribute("currentUserId") Long brandId) {
        brandService.deleteBrandSpec(brandId, brandSpecId);
        return Result.success("已刪除");
    }

    @Operation(summary = "切換規格啟用狀態")
    @PatchMapping("/api/brand/specs/{brandSpecId}/toggle")
    public Result toggleSpec(@PathVariable Long brandSpecId,
            @RequestAttribute("currentUserId") Long brandId) {
        return Result.success(brandService.toggleBrandSpec(brandId, brandSpecId));
    }

    @Operation(summary = "編輯規格名稱", description = "Body: { name: \"自訂名稱\" }")
    @PatchMapping("/api/brand/specs/{brandSpecId}")
    public Result updateSpec(@PathVariable Long brandSpecId,
            @RequestAttribute("currentUserId") Long brandId,
            @RequestBody java.util.Map<String, String> body) {
        return Result.success(brandService.updateBrandSpec(brandId, brandSpecId, body.get("name")));
    }

    // ==================== 配料管理 ====================

    @Operation(summary = "取得品牌配料列表")
    @GetMapping("/api/brand/toppings")
    public Result getToppings(@RequestAttribute("currentUserId") Long brandId) {
        return Result.success(brandService.getBrandToppings(brandId));
    }

    @Operation(summary = "新增配料", description = "Body: { name: \"珍珠\", price: 10 }")
    @PostMapping("/api/brand/toppings")
    public Result addTopping(@RequestAttribute("currentUserId") Long brandId,
            @RequestBody java.util.Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) throw new com.example.demo.exception.CustomException("400", "name 必填");
        java.math.BigDecimal price = body.get("price") != null
                ? new java.math.BigDecimal(body.get("price").toString())
                : java.math.BigDecimal.ZERO;
        return Result.success(brandService.addBrandTopping(brandId, name, price));
    }

    @Operation(summary = "刪除配料")
    @DeleteMapping("/api/brand/toppings/{brandToppingId}")
    public Result deleteTopping(@PathVariable Long brandToppingId,
            @RequestAttribute("currentUserId") Long brandId) {
        brandService.deleteBrandTopping(brandId, brandToppingId);
        return Result.success("已刪除");
    }

    @Operation(summary = "切換配料啟用狀態")
    @PatchMapping("/api/brand/toppings/{brandToppingId}/toggle")
    public Result toggleTopping(@PathVariable Long brandToppingId,
            @RequestAttribute("currentUserId") Long brandId) {
        return Result.success(brandService.toggleBrandTopping(brandId, brandToppingId));
    }

    @Operation(summary = "編輯配料名稱與價格", description = "Body: { name: \"自訂名稱\", price: 10 }")
    @PatchMapping("/api/brand/toppings/{brandToppingId}")
    public Result updateTopping(@PathVariable Long brandToppingId,
            @RequestAttribute("currentUserId") Long brandId,
            @RequestBody java.util.Map<String, Object> body) {
        String name = (String) body.get("name");
        java.math.BigDecimal price = body.get("price") != null
                ? new java.math.BigDecimal(body.get("price").toString())
                : null;
        return Result.success(brandService.updateBrandTopping(brandId, brandToppingId, name, price));
    }

    // ==================== 飲品詳情（含規格）====================

    @Operation(summary = "取得單一飲品詳情（含已選規格與配料）")
    @GetMapping("/api/brand/products/{productId}/detail")
    public Result getProductDetail(@RequestAttribute("currentUserId") Long brandId,
            @PathVariable Long productId) {
        return Result.success(brandService.getProductDetail(brandId, productId));
    }

    // ==================== 全局報表 ====================

    @Operation(summary = "全台熱銷/滯銷品項", description = "Query: period = week / month / year")
    @GetMapping("/api/brand/analytics/products")
    public Result getProductRanking(@RequestAttribute("currentUserId") Long brandId,
            @RequestParam(defaultValue = "month") String period) {
        return Result.success(analyticsService.getBrandProductRanking(brandId, period));
    }

    @Operation(summary = "品牌財務完整摘要（含各分店明細）", description = "Query: period = week / month / year")
    @GetMapping("/api/brand/finance/full")
    public Result getFinanceFull(@RequestAttribute("currentUserId") Long brandId,
            @RequestParam(defaultValue = "month") String period) {
        return Result.success(analyticsService.getBrandFinanceSummaryFull(brandId, period));
    }

    @Operation(summary = "財務匯總概況（佣金/淨收入）", description = "Query: period = week / month / quarter / year")
    @GetMapping("/api/brand/finance/overview")
    public Result getFinanceOverview(@RequestAttribute("currentUserId") Long brandId,
            @RequestParam(defaultValue = "month") String period) {
        return Result.success(analyticsService.getBrandFinanceOverview(brandId, period));
    }

    @Operation(summary = "品牌營收趨勢", description = "Query: period = week / month / quarter")
    @GetMapping("/api/brand/finance/trend")
    public Result getFinanceTrend(@RequestAttribute("currentUserId") Long brandId,
            @RequestParam(defaultValue = "month") String period) {
        return Result.success(analyticsService.getBrandRevenueTrend(brandId, period));
    }

    @Operation(summary = "各區域營收統計", description = "Query: period = week / month / year")
    @GetMapping("/api/brand/finance/regions")
    public Result getFinanceRegions(@RequestAttribute("currentUserId") Long brandId,
            @RequestParam(defaultValue = "month") String period) {
        return Result.success(analyticsService.getBrandRegionFinance(brandId, period));
    }

    // ==================== 分店管理 ====================

    @Operation(summary = "品牌分店列表（含完整欄位）", description = "回傳品牌旗下所有分店，包含 regionName、managerName、managerPhone 等管理欄位。")
    @GetMapping("/api/brand/stores")
    public Result getBrandStores(@RequestAttribute("currentUserId") Long brandId) {
        return Result.success(brandService.getBrandStores(brandId));
    }

    @Operation(summary = "品牌口碑監控", description = "回傳品牌平均評分、最低評分分店清單與各區域滿意度。")
    @GetMapping("/api/brand/reputation")
    public Result getBrandReputation(@RequestAttribute("currentUserId") Long brandId) {
        return Result.success(brandService.getBrandReputation(brandId));
    }

    @Operation(summary = "品牌更新分店資料", description = "品牌管理員更新旗下分店資料（storeName, regionId, managerName, managerPhone, address, coverUrl）。")
    @PutMapping("/api/brand/stores/{storeId}")
    public Result updateBrandStore(@RequestAttribute("currentUserId") Long brandId,
            @PathVariable Long storeId,
            @RequestBody StoreRequest req) {
        req.setId(storeId);
        storeService.updateStoreByBrand(brandId, req);
        return Result.success("分店資訊更新成功");
    }

}
