package com.example.demo.service;

import com.example.demo.common.JwtUtils;
import com.example.demo.dto.BrandSpecReorderRequest;
import com.example.demo.dto.CreateCategoryRequest;
import com.example.demo.dto.CreateProductRequest;
import com.example.demo.dto.MenuCategoryResponse;
import com.example.demo.dto.SpecPriceEntry;
import com.example.demo.dto.UpdateProductRequest;
import com.example.demo.entity.*;
import com.example.demo.entity.BrandSpecSetting;
import com.example.demo.entity.BrandToppingSetting;
import com.example.demo.entity.SpecMaster;
import com.example.demo.entity.ToppingMaster;
import com.example.demo.repository.BrandSpecSettingRepository;
import com.example.demo.repository.BrandToppingSettingRepository;
import com.example.demo.repository.ProductSpecRelationRepository;
import com.example.demo.repository.ProductToppingRuleRepository;
import com.example.demo.repository.SpecMasterRepository;
import com.example.demo.repository.ToppingMasterRepository;
import java.util.LinkedHashMap;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Collator;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BrandService {

    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private com.example.demo.repository.RegionRepository regionRepository;
    @Autowired
    private SpecMasterRepository specMasterRepository;
    @Autowired
    private ToppingMasterRepository toppingMasterRepository;
    @Autowired
    private BrandSpecSettingRepository brandSpecSettingRepository;
    @Autowired
    private BrandToppingSettingRepository brandToppingSettingRepository;
    @Autowired
    private ProductSpecRelationRepository productSpecRelationRepository;
    @Autowired
    private ProductToppingRuleRepository productToppingRuleRepository;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private MenuCategoryRepository menuCategoryRepository;
    @Autowired
    private ProductTemplateRepository productTemplateRepository;
    @Autowired
    private StoreProductStatusRepository storeProductStatusRepository;
    @Autowired
    private CartItemRepository cartItemRepository;
    @Autowired
    private BrandRegionCategoryPricingRepository brandRegionCategoryPricingRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private CouponImageService couponImageService;
    @Autowired
    private LocationService locationService;
    @Autowired
    private OpeningHoursValidator openingHoursValidator;
    @Autowired
    private OrderRatingRepository orderRatingRepository;
    @Autowired
    private GroupOrderRepository groupOrderRepository;

    // ─── 認證 ──────────────────────────────────────────
    @Transactional
    public void register(String name, String account, String password) {
        if (brandRepository.existsByAccount(account))
            throw new CustomException("409", "帳號已被使用");
        if (brandRepository.existsByName(name))
            throw new CustomException("409", "品牌名稱已被使用");
        Brand brand = new Brand();
        brand.setName(name);
        brand.setAccount(account);
        brand.setPasswordHash(passwordEncoder.encode(password));
        brandRepository.save(brand);
        Map<String, Integer> specTypeOrder = new HashMap<>();

        // 自動綁定平台預設規格（全部預設停用，品牌自行開啟）
        for (SpecMaster spec : specMasterRepository.findAll()) {
            BrandSpecSetting s = new BrandSpecSetting();
            s.setBrand(brand);
            s.setMaster(spec);
            s.setIsEnabled(false);
            String type = spec.getType();
            int nextOrder = specTypeOrder.getOrDefault(type, 0);
            s.setSortOrder(nextOrder);
            specTypeOrder.put(type, nextOrder + 1);
            brandSpecSettingRepository.save(s);
        }
        // 自動綁定平台預設配料（全部預設停用）
        for (ToppingMaster topping : toppingMasterRepository.findAll()) {
            BrandToppingSetting t = new BrandToppingSetting();
            t.setBrand(brand);
            t.setMasterTopping(topping);
            t.setIsEnabled(false);
            brandToppingSettingRepository.save(t);
        }
    }

    public Map<String, Object> login(String account, String password) {
        Brand brand = brandRepository.findByAccount(account)
                .orElseThrow(() -> new CustomException("404", "帳號不存在"));
        if (!passwordEncoder.matches(password, brand.getPasswordHash()))
            throw new CustomException("401", "密碼錯誤");
        String token = jwtUtils.generateToken(brand.getId(), brand.getRole(), brand.getAccount());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("brandId", brand.getId());
        data.put("name", brand.getName());
        data.put("logoUrl", brand.getLogoUrl());
        return data;
    }

    @Transactional
    public Map<String, Object> updateBrandLogo(Long brandId, String logoUrl) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "Brand not found"));
        brand.setLogoUrl(logoUrl);
        brandRepository.save(brand);

        Map<String, Object> data = new HashMap<>();
        data.put("brandId", brand.getId());
        data.put("brandName", brand.getName());
        data.put("logoUrl", brand.getLogoUrl());
        return data;
    }

    @Transactional
    public Map<String, Object> createStore(Long brandId, String storeName, String account, String password,
            Long regionId, String managerName, String managerPhone, String address,
            String coverUrl,
            java.math.BigDecimal latitude, java.math.BigDecimal longitude,
            Object openingHours) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "品牌不存在"));
        Store store = new Store();
        store.setBrand(brand);
        store.setStoreName(storeName);
        store.setAccount(account);
        store.setPasswordHash(passwordEncoder.encode(password));
        if (regionId != null) {
            regionRepository.findById(regionId).ifPresent(store::setRegion);
        }
        if (managerName != null)
            store.setManagerName(managerName);
        if (managerPhone != null)
            store.setManagerPhone(managerPhone);
        if (address != null)
            store.setAddress(address);
        if (coverUrl != null)
            store.setCoverUrl(coverUrl);
        if (address != null && (latitude == null || longitude == null)) {
            LocationService.Coordinates coords = locationService.geocodeAddress(address)
                    .orElseThrow(() -> new CustomException("400", "Address geocoding failed"));
            if (latitude == null)
                latitude = coords.getLatitude();
            if (longitude == null)
                longitude = coords.getLongitude();
        }
        if (latitude != null)
            store.setLatitude(latitude);
        if (longitude != null)
            store.setLongitude(longitude);
        if (openingHours != null)
            store.setOpeningHours(openingHoursValidator.normalize(openingHours));
        store = storeRepository.save(store);
        Map<String, Object> data = new HashMap<>();
        data.put("storeId", store.getId());
        data.put("storeName", store.getStoreName());
        data.put("latitude", store.getLatitude());
        data.put("longitude", store.getLongitude());
        data.put("openingHours", store.getOpeningHours());
        return data;
    }

    // ─── 分類管理 ────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<MenuCategoryResponse> getCategories(Long brandId) {
        return menuCategoryRepository.findByBrandId(brandId).stream()
                .map(MenuCategoryResponse::from).collect(Collectors.toList());
    }

    @Transactional
    public List<MenuCategoryResponse> createCategories(Long brandId, CreateCategoryRequest req) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "品牌不存在"));
        List<MenuCategoryResponse> result = new ArrayList<>();
        int nextSortOrder = Optional.ofNullable(menuCategoryRepository.findMaxSortOrderByBrandId(brandId)).orElse(-1) + 1;
        for (String name : req.getNames()) {
            if (menuCategoryRepository.existsByBrandIdAndName(brandId, name))
                continue;
            MenuCategory cat = new MenuCategory();
            cat.setBrand(brand);
            cat.setName(name);
            cat.setSortOrder(nextSortOrder++);
            menuCategoryRepository.save(cat);
            result.add(MenuCategoryResponse.from(cat));
        }
        return result;
    }

    @Transactional
    public void deleteCategory(Long categoryId, Long brandId) {
        MenuCategory cat = menuCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new CustomException("404", "分類不存在"));
        if (!cat.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限");
        List<ProductTemplate> products = productTemplateRepository.findByCategoryId(categoryId);
        for (ProductTemplate p : products) {
            productToppingRuleRepository.deleteByIdProductId(p.getId());
            productSpecRelationRepository.deleteByIdProductId(p.getId());
            storeProductStatusRepository.deleteByProductId(p.getId());
            cartItemRepository.deleteByProductId(p.getId());
        }
        productTemplateRepository.deleteByCategoryId(categoryId);
        brandRegionCategoryPricingRepository.deleteByCategoryId(categoryId);
        menuCategoryRepository.delete(cat);
    }

    @Transactional
    public MenuCategoryResponse renameCategory(Long categoryId, Long brandId, String newName) {
        MenuCategory cat = menuCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new CustomException("404", "分類不存在"));
        if (!cat.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限");
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty())
            throw new CustomException("400", "分類名稱不可為空");
        if (trimmed.equals("全部"))
            throw new CustomException("400", "分類名稱不可為「全部」");
        if (menuCategoryRepository.existsByBrandIdAndName(brandId, trimmed))
            throw new CustomException("409", "分類名稱已存在");
        cat.setName(trimmed);
        menuCategoryRepository.save(cat);
        return MenuCategoryResponse.from(cat);
    }

    @Transactional
    public void moveCategory(Long categoryId, Long brandId, int direction) {
        if (direction != -1 && direction != 1)
            throw new CustomException("400", "Invalid category move direction");
        List<MenuCategory> orderedCategories = menuCategoryRepository.findByBrandId(brandId);
        int currentIndex = findCategoryIndex(orderedCategories, categoryId);
        if (currentIndex < 0)
            throw new CustomException("404", "分類不存在");
        int targetIndex = currentIndex + direction;
        if (targetIndex < 0 || targetIndex >= orderedCategories.size())
            return;
        swapCategorySortOrder(orderedCategories.get(currentIndex), orderedCategories.get(targetIndex));
    }

    @Transactional
    public void reorderCategories(Long brandId, List<Long> orderedCategoryIds) {
        if (orderedCategoryIds == null || orderedCategoryIds.isEmpty())
            throw new CustomException("400", "orderedCategoryIds required");

        List<MenuCategory> orderedCategories = menuCategoryRepository.findByBrandId(brandId);
        if (orderedCategories.size() != orderedCategoryIds.size())
            throw new CustomException("400", "orderedCategoryIds size mismatch");

        Map<Long, MenuCategory> categoryById = orderedCategories.stream()
                .collect(Collectors.toMap(MenuCategory::getId, category -> category));
        Set<Long> seenIds = new HashSet<>();

        for (Long categoryId : orderedCategoryIds) {
            if (!categoryById.containsKey(categoryId))
                throw new CustomException("400", "orderedCategoryIds contains invalid category");
            if (!seenIds.add(categoryId))
                throw new CustomException("400", "orderedCategoryIds contains duplicate category");
        }

        for (int i = 0; i < orderedCategoryIds.size(); i++) {
            MenuCategory category = categoryById.get(orderedCategoryIds.get(i));
            category.setSortOrder(i);
        }
        menuCategoryRepository.saveAll(orderedCategories);
    }

    // ─── 飲品管理 ────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getProducts(Long brandId) {
        List<ProductTemplate> products = productTemplateRepository.findByBrandId(brandId);

        // 一次撈所有容量規格定價，避免 N+1
        List<ProductSpecRelation> allPricings = productSpecRelationRepository.findSizePricingsByBrandId(brandId);
        Map<Long, List<ProductSpecRelation>> pricingsByProduct = allPricings.stream()
                .collect(Collectors.groupingBy(r -> r.getId().getProductId()));

        return products.stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("productId", p.getId());
            m.put("sortOrder", p.getSortOrder());
            m.put("categoryId", p.getCategory() != null ? p.getCategory().getId() : null);
            m.put("categoryName", p.getCategory() != null ? p.getCategory().getName() : null);
            m.put("name", p.getName());
            m.put("basePrice", p.getBasePrice());
            m.put("maxToppings", p.getMaxToppings());
            m.put("logoUrl", p.getLogoUrl());
            m.put("couponImageUrl", p.getCouponImageUrl());
            m.put("isEnabled", p.getIsEnabled());
            m.put("specPrices", buildSpecPriceList(pricingsByProduct.getOrDefault(p.getId(), List.of())));
            return m;
        }).collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> createProduct(Long brandId, CreateProductRequest req) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "品牌不存在"));
        ProductTemplate p = new ProductTemplate();
        p.setBrand(brand);
        Long categoryId = null;
        if (req.getCategoryId() != null) {
            MenuCategory cat = menuCategoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new CustomException("404", "分類不存在"));
            p.setCategory(cat);
            categoryId = cat.getId();
        }
        p.setSortOrder(nextProductSortOrder(brandId, categoryId));
        p.setName(req.getName());
        p.setBasePrice(deriveBasePrice(req.getBasePrice(), req.getSpecPrices()));
        if (req.getDescription() != null)
            p.setDescription(req.getDescription());
        if (req.getMaxToppings() != null)
            p.setMaxToppings(req.getMaxToppings());
        if (req.getLogoUrl() != null)
            p.setLogoUrl(req.getLogoUrl());
        final ProductTemplate savedP = productTemplateRepository.save(p);

        // 儲存 ICE + SWEETNESS 規格關聯
        saveSpecRelations(savedP.getId(), req.getBrandSpecIds());

        // 儲存 SIZE 容量規格關聯 + 定價
        saveSpecPricings(savedP.getId(), req.getSpecPrices());

        // 儲存配料關聯
        saveProductToppings(savedP.getId(), req.getBrandToppingIds());

        // 合成優惠券圖片並儲存
        try {
            String couponUrl = couponImageService.generateCouponImage(brand.getName(), savedP.getName());
            savedP.setCouponImageUrl(couponUrl);
            productTemplateRepository.save(savedP);
        } catch (Exception e) {
            log.warn("優惠券圖片合成失敗，飲品仍正常建立。productId={}, error={}", savedP.getId(), e.getMessage());
        }

        return buildProductResponse(savedP, req.getSpecPrices());
    }

    @Transactional
    public Map<String, Object> updateProduct(Long brandId, Long productId, UpdateProductRequest req) {
        ProductTemplate p = productTemplateRepository.findById(productId)
                .orElseThrow(() -> new CustomException("404", "飲品不存在"));
        if (!p.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限");
        Long originalCategoryId = p.getCategory() != null ? p.getCategory().getId() : null;
        if (req.getCategoryId() != null) {
            MenuCategory cat = menuCategoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new CustomException("404", "分類不存在"));
            p.setCategory(cat);
            Long nextCategoryId = cat.getId();
            if (!Objects.equals(originalCategoryId, nextCategoryId))
                p.setSortOrder(nextProductSortOrder(brandId, nextCategoryId));
        }
        if (req.getName() != null)
            p.setName(req.getName());
        if (req.getDescription() != null)
            p.setDescription(req.getDescription());
        p.setBasePrice(deriveBasePrice(req.getBasePrice(), req.getSpecPrices()));
        if (req.getMaxToppings() != null)
            p.setMaxToppings(req.getMaxToppings());
        if (req.getLogoUrl() != null)
            p.setLogoUrl(req.getLogoUrl().isEmpty() ? null : req.getLogoUrl());
        final ProductTemplate savedP = productTemplateRepository.save(p);

        // 更新規格關聯（ICE/SWEETNESS/SIZE）+ SIZE 定價
        if (req.getBrandSpecIds() != null || req.getSpecPrices() != null) {
            productSpecRelationRepository.deleteByIdProductId(productId);
            if (req.getBrandSpecIds() != null)
                saveSpecRelations(savedP.getId(), req.getBrandSpecIds());
            if (req.getSpecPrices() != null)
                saveSpecPricings(savedP.getId(), req.getSpecPrices());
        }

        // 更新配料關聯
        if (req.getBrandToppingIds() != null) {
            productToppingRuleRepository.deleteByIdProductId(productId);
            saveProductToppings(savedP.getId(), req.getBrandToppingIds());
        }

        return buildProductResponse(savedP, req.getSpecPrices());
    }

    @Transactional
    public void deleteProduct(Long brandId, Long productId) {
        ProductTemplate p = productTemplateRepository.findById(productId)
                .orElseThrow(() -> new CustomException("404", "飲品不存在"));
        if (!p.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限");
        productToppingRuleRepository.deleteByIdProductId(productId);
        productSpecRelationRepository.deleteByIdProductId(productId);
        storeProductStatusRepository.deleteByProductId(productId);
        cartItemRepository.deleteByProductId(productId);
        productTemplateRepository.delete(p);
    }

    @Transactional
    public void moveProduct(Long brandId, Long productId, int direction) {
        if (direction != -1 && direction != 1)
            throw new CustomException("400", "Invalid product move direction");
        ProductTemplate currentProduct = productTemplateRepository.findById(productId)
                .orElseThrow(() -> new CustomException("404", "飲品不存在"));
        if (!currentProduct.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限");

        Long categoryId = currentProduct.getCategory() != null ? currentProduct.getCategory().getId() : null;
        List<ProductTemplate> orderedProducts = productTemplateRepository.findByBrandId(brandId).stream()
                .filter(product -> Objects.equals(
                        product.getCategory() != null ? product.getCategory().getId() : null,
                        categoryId))
                .collect(Collectors.toList());

        int currentIndex = findProductIndex(orderedProducts, productId);
        if (currentIndex < 0)
            throw new CustomException("404", "飲品不存在");
        int targetIndex = currentIndex + direction;
        if (targetIndex < 0 || targetIndex >= orderedProducts.size())
            return;
        swapProductSortOrder(orderedProducts.get(currentIndex), orderedProducts.get(targetIndex));
    }

    // ─── 工具 ────────────────────────────────────────────────

    /** 計算 base_price：有 specPrices 時取最低價，否則用傳入的 basePrice */
    private BigDecimal deriveBasePrice(BigDecimal provided, List<SpecPriceEntry> specPrices) {
        if (specPrices != null && !specPrices.isEmpty()) {
            return specPrices.stream()
                    .map(SpecPriceEntry::getPrice)
                    .filter(Objects::nonNull)
                    .min(BigDecimal::compareTo)
                    .orElse(provided != null ? provided : BigDecimal.ZERO);
        }
        return provided != null ? provided : BigDecimal.ZERO;
    }

    /** 儲存 ICE / SWEETNESS 規格關聯（不帶定價） */
    private void saveSpecRelations(Long productId, List<Long> specIds) {
        if (specIds == null || specIds.isEmpty())
            return;
        for (Long specId : specIds) {
            productSpecRelationRepository.insertRelation(productId, specId);
        }
    }

    /** 儲存 SIZE 容量規格關聯（含定價） */
    private void saveSpecPricings(Long productId, List<SpecPriceEntry> specPrices) {
        if (specPrices == null || specPrices.isEmpty())
            return;
        for (SpecPriceEntry entry : specPrices) {
            if (entry.getBrandSpecId() == null)
                continue;
            productSpecRelationRepository.insertRelationWithPrice(productId, entry.getBrandSpecId(),
                    entry.getPrice() != null ? entry.getPrice() : BigDecimal.ZERO);
        }
    }

    /** 儲存配料關聯 */
    private void saveProductToppings(Long productId, List<Long> toppingIds) {
        if (toppingIds == null || toppingIds.isEmpty())
            return;
        for (Long toppingId : toppingIds) {
            productToppingRuleRepository.insertRule(productId, toppingId);
        }
    }

    /** 將 SIZE ProductSpecRelation 列表轉為回應格式 */
    private List<Map<String, Object>> buildSpecPriceList(List<ProductSpecRelation> pricings) {
        return pricings.stream().map(r -> {
            Map<String, Object> sp = new HashMap<>();
            sp.put("brandSpecId", r.getId().getBrandSpecId());
            BrandSpecSetting spec = r.getBrandSpec();
            sp.put("name", spec.getCustomName() != null ? spec.getCustomName() : spec.getMaster().getName());
            sp.put("price", r.getPrice());
            return sp;
        }).collect(Collectors.toList());
    }

    /** 組建飲品回應 Map（含 specPrices） */
    private Map<String, Object> buildProductResponse(ProductTemplate p, List<SpecPriceEntry> specPricesFromReq) {
        Map<String, Object> data = new HashMap<>();
        data.put("productId", p.getId());
        data.put("sortOrder", p.getSortOrder());
        data.put("name", p.getName());
        data.put("categoryId", p.getCategory() != null ? p.getCategory().getId() : null);
        data.put("categoryName", p.getCategory() != null ? p.getCategory().getName() : null);
        data.put("basePrice", p.getBasePrice());
        data.put("maxToppings", p.getMaxToppings());
        data.put("logoUrl", p.getLogoUrl());
        data.put("couponImageUrl", p.getCouponImageUrl());
        data.put("isEnabled", p.getIsEnabled());
        // 從 DB 重新讀取以確保 name 正確（JOIN FETCH 避免 N+1）
        List<ProductSpecRelation> pricings = productSpecRelationRepository.findSizePricingsByProductId(p.getId());
        data.put("specPrices", buildSpecPriceList(pricings));
        return data;
    }

    private int nextProductSortOrder(Long brandId, Long categoryId) {
        Integer currentMax = categoryId == null
                ? productTemplateRepository.findMaxSortOrderByBrandIdAndCategoryIsNull(brandId)
                : productTemplateRepository.findMaxSortOrderByBrandIdAndCategoryId(brandId, categoryId);
        return Optional.ofNullable(currentMax).orElse(-1) + 1;
    }

    private int findCategoryIndex(List<MenuCategory> orderedCategories, Long categoryId) {
        for (int i = 0; i < orderedCategories.size(); i++) {
            if (Objects.equals(orderedCategories.get(i).getId(), categoryId))
                return i;
        }
        return -1;
    }

    private int findProductIndex(List<ProductTemplate> orderedProducts, Long productId) {
        for (int i = 0; i < orderedProducts.size(); i++) {
            if (Objects.equals(orderedProducts.get(i).getId(), productId))
                return i;
        }
        return -1;
    }

    private void swapCategorySortOrder(MenuCategory current, MenuCategory target) {
        int currentSortOrder = Optional.ofNullable(current.getSortOrder()).orElse(0);
        int targetSortOrder = Optional.ofNullable(target.getSortOrder()).orElse(0);
        current.setSortOrder(targetSortOrder);
        target.setSortOrder(currentSortOrder);
        menuCategoryRepository.save(current);
        menuCategoryRepository.save(target);
    }

    private void swapProductSortOrder(ProductTemplate current, ProductTemplate target) {
        int currentSortOrder = Optional.ofNullable(current.getSortOrder()).orElse(0);
        int targetSortOrder = Optional.ofNullable(target.getSortOrder()).orElse(0);
        current.setSortOrder(targetSortOrder);
        target.setSortOrder(currentSortOrder);
        productTemplateRepository.save(current);
        productTemplateRepository.save(target);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getBrandStores(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "品牌不存在"));
        List<Store> stores = storeRepository.findByBrandId(brandId);
        List<Map<String, Object>> storeList = stores.stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("storeId", s.getId());
            m.put("storeName", s.getStoreName());
            m.put("account", s.getAccount());
            m.put("address", s.getAddress());
            m.put("status", s.getStatus());
            m.put("avgRating", s.getAvgRating());
            m.put("reviewCount", s.getReviewCount());
            m.put("managerName", s.getManagerName());
            m.put("managerPhone", s.getManagerPhone());
            m.put("storePhone", s.getStorePhone());

            m.put("coverUrl", s.getCoverUrl());
            m.put("regionId", s.getRegion() != null ? s.getRegion().getId() : null);
            m.put("regionName", s.getRegion() != null ? s.getRegion().getName() : null);
            m.put("latitude", s.getLatitude());
            m.put("longitude", s.getLongitude());
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> result = new HashMap<>();
        result.put("brandId", brand.getId());
        result.put("brandName", brand.getName());
        result.put("logoUrl", brand.getLogoUrl());
        result.put("storeCount", stores.size());
        result.put("stores", storeList);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getBrandReputation(Long brandId) {
        List<Store> stores = storeRepository.findByBrandId(brandId);
        List<Store> ratedStores = stores.stream()
                .filter(s -> s.getAvgRating() != null && s.getAvgRating().doubleValue() > 0)
                .collect(Collectors.toList());

        double brandAvg = ratedStores.isEmpty() ? 0
                : ratedStores.stream().mapToDouble(s -> s.getAvgRating().doubleValue()).average().orElse(0);
        double brandAvgRounded = Math.round(brandAvg * 10.0) / 10.0;

        List<Long> ratedStoreIds = ratedStores.stream().map(Store::getId).collect(Collectors.toList());

        // ratingDistribution: count individual order_ratings by star value
        Map<String, Object> dist = new LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) {
            dist.put(String.valueOf(star), 0L);
        }
        if (!ratedStoreIds.isEmpty()) {
            List<Object[]> distRows = orderRatingRepository.countByRatingInStores(ratedStoreIds);
            for (Object[] row : distRows) {
                int star = ((Number) row[0]).intValue();
                long count = ((Number) row[1]).longValue();
                if (star >= 1 && star <= 5) {
                    dist.put(String.valueOf(star), count);
                }
            }
        }
        long totalReviewCount = dist.values().stream().mapToLong(v -> ((Number) v).longValue()).sum();

        List<Map<String, Object>> lowRatedStores = ratedStores.stream()
                .sorted(Comparator.comparing(s -> s.getAvgRating().doubleValue()))
                .limit(5)
                .map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("storeId", s.getId());
                    m.put("storeName", s.getStoreName());
                    m.put("avgRating", s.getAvgRating());
                    m.put("regionName", s.getRegion() != null ? s.getRegion().getName() : null);
                    m.put("reviewCount", s.getReviewCount() != null ? s.getReviewCount() : 0);
                    // per-store rating distribution
                    Map<String, Long> storeDist = new LinkedHashMap<>();
                    for (int star = 5; star >= 1; star--) {
                        storeDist.put(String.valueOf(star), 0L);
                    }
                    List<Object[]> storeDistRows = orderRatingRepository.countByRatingForStore(s.getId());
                    for (Object[] row : storeDistRows) {
                        int star = ((Number) row[0]).intValue();
                        long count = ((Number) row[1]).longValue();
                        if (star >= 1 && star <= 5) {
                            storeDist.put(String.valueOf(star), count);
                        }
                    }
                    m.put("ratingDistribution", storeDist);
                    return m;
                }).collect(Collectors.toList());

        Map<String, List<Store>> byRegion = ratedStores.stream()
                .filter(s -> s.getRegion() != null)
                .collect(Collectors.groupingBy(s -> s.getRegion().getName()));
        List<Map<String, Object>> regions = byRegion.entrySet().stream().map(e -> {
            double avg = e.getValue().stream().mapToDouble(s -> s.getAvgRating().doubleValue()).average().orElse(0);
            Map<String, Object> m = new HashMap<>();
            m.put("regionName", e.getKey());
            m.put("avgRating", Math.round(avg * 10.0) / 10.0);
            m.put("storeCount", e.getValue().size());
            return m;
        }).sorted((a, b) -> Double.compare((double) b.get("avgRating"), (double) a.get("avgRating")))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        List<Long> allStoreIds = stores.stream().map(Store::getId).collect(Collectors.toList());
        long totalCompletedOrders = allStoreIds.isEmpty() ? 0 : groupOrderRepository.countCompletedByStoreIds(allStoreIds);

        result.put("avgRating", brandAvgRounded);
        result.put("storeCount", stores.size());
        result.put("ratedCount", ratedStores.size());
        result.put("totalReviewCount", totalReviewCount);
        result.put("totalCompletedOrders", totalCompletedOrders);
        result.put("lowRatedStores", lowRatedStores);
        result.put("regionSatisfaction", regions);
        result.put("ratingDistribution", dist);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRegions() {
        return regionRepository.findAll().stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            return m;
        }).collect(Collectors.toList());
    }

    // ─── 規格管理 ────────────────────────────────────────────
    @Transactional
    public Map<String, List<Map<String, Object>>> getBrandSpecs(Long brandId) {
        ensureBrandSpecDefaults(brandId);
        List<BrandSpecSetting> settings = brandSpecSettingRepository.findByBrandIdOrderBySortOrderAscIdAsc(brandId);
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        grouped.put("ICE", new ArrayList<>());
        grouped.put("SWEETNESS", new ArrayList<>());
        grouped.put("SIZE", new ArrayList<>());
        for (BrandSpecSetting s : settings) {
            SpecMaster master = getSpecMasterSafely(s);
            String type = master != null ? master.getType() : s.getSpecType();
            if (type == null)
                continue;
            Map<String, Object> m = new HashMap<>();
            m.put("brandSpecId", s.getId());
            m.put("masterId",
                    master != null ? master.getId() : null);
            m.put("type", type);
            m.put("name", s.getCustomName() != null ? s.getCustomName()
                    : (master != null ? master.getName() : ""));
            m.put("originalName", master != null ? master.getName() : s.getCustomName());
            m.put("isEnabled", Boolean.TRUE.equals(s.getIsEnabled()));
            m.put("sortOrder", s.getSortOrder() != null ? s.getSortOrder() : 0);
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(m);
        }
        return grouped;
    }

    private void ensureBrandSpecDefaults(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "Brand not found"));
        List<BrandSpecSetting> existingSettings = brandSpecSettingRepository
                .findByBrandIdOrderBySortOrderAscIdAsc(brandId);
        List<BrandSpecSetting> invalidSettings = new ArrayList<>();
        Set<Long> existingMasterIds = existingSettings.stream()
                .map(setting -> {
                    SpecMaster master = getSpecMasterSafely(setting);
                    if (setting.getMaster() != null && master == null) {
                        invalidSettings.add(setting);
                    }
                    return master;
                })
                .filter(Objects::nonNull)
                .map(SpecMaster::getId)
                .collect(Collectors.toSet());
        Map<String, Integer> nextOrderByType = new HashMap<>();
        for (BrandSpecSetting setting : existingSettings) {
            if (invalidSettings.contains(setting)) {
                continue;
            }
            SpecMaster master = getSpecMasterSafely(setting);
            String type = master != null ? master.getType() : setting.getSpecType();
            if (type == null) {
                continue;
            }
            int nextOrder = Math.max(nextOrderByType.getOrDefault(type, 0),
                    (setting.getSortOrder() != null ? setting.getSortOrder() : -1) + 1);
            nextOrderByType.put(type, nextOrder);
        }

        if (!invalidSettings.isEmpty()) {
            brandSpecSettingRepository.deleteAll(invalidSettings);
        }

        List<BrandSpecSetting> missingDefaults = new ArrayList<>();
        for (SpecMaster specMaster : specMasterRepository.findAll()) {
            if (existingMasterIds.contains(specMaster.getId())) {
                continue;
            }
            String type = specMaster.getType();
            BrandSpecSetting setting = new BrandSpecSetting();
            setting.setBrand(brand);
            setting.setMaster(specMaster);
            setting.setIsEnabled(false);
            setting.setSortOrder(nextOrderByType.getOrDefault(type, 0));
            nextOrderByType.put(type, setting.getSortOrder() + 1);
            missingDefaults.add(setting);
        }

        if (!missingDefaults.isEmpty()) {
            brandSpecSettingRepository.saveAll(missingDefaults);
        }
    }

    private SpecMaster getSpecMasterSafely(BrandSpecSetting setting) {
        try {
            SpecMaster master = setting.getMaster();
            if (master != null) {
                master.getId();
            }
            return master;
        } catch (Exception ex) {
            return null;
        }
    }

    private String getSpecType(BrandSpecSetting setting) {
        SpecMaster master = getSpecMasterSafely(setting);
        return master != null ? master.getType() : setting.getSpecType();
    }

    private String getSpecDisplayName(BrandSpecSetting setting) {
        String customName = setting.getCustomName();
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        SpecMaster master = getSpecMasterSafely(setting);
        return master != null ? master.getName() : null;
    }

    private boolean equalsDisplayName(String left, String right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        Collator collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(Collator.PRIMARY);
        collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        return collator.compare(left.trim(), right.trim()) == 0;
    }

    private String normalizeSpecCustomName(BrandSpecSetting setting, String customName) {
        String trimmed = customName == null ? null : customName.trim();
        if (trimmed == null || trimmed.isBlank()) {
            return null;
        }
        SpecMaster master = getSpecMasterSafely(setting);
        if (master != null && equalsDisplayName(trimmed, master.getName())) {
            return null;
        }
        return trimmed;
    }

    private boolean hasDuplicateSpecDisplayName(Long brandId, String type, String displayName, Long excludeId) {
        return brandSpecSettingRepository.findByBrandIdOrderBySortOrderAscIdAsc(brandId).stream()
                .filter(setting -> type.equals(getSpecType(setting)))
                .filter(setting -> excludeId == null || !setting.getId().equals(excludeId))
                .map(this::getSpecDisplayName)
                .anyMatch(existingName -> equalsDisplayName(existingName, displayName));
    }

    @Transactional
    public Map<String, Object> addBrandSpec(Long brandId, String type, String name) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "品牌不存在"));
        String upperType = type.toUpperCase();
        String trimmed = name == null ? "" : name.trim();
        if (!List.of("ICE", "SWEETNESS", "SIZE").contains(upperType))
            throw new CustomException("400", "type 必須是 ICE / SWEETNESS / SIZE");
        if (trimmed.isEmpty())
            throw new CustomException("400", "規格名稱必填");
        long current = brandSpecSettingRepository.countByBrandIdAndType(brandId, upperType);
        if (current >= 10)
            throw new CustomException("400", "該類型規格已達上限（最多 10 個）");
        if (hasDuplicateSpecDisplayName(brandId, upperType, trimmed, null))
            throw new CustomException("409", "此規格名稱已重複");
        BrandSpecSetting setting = new BrandSpecSetting();
        setting.setBrand(brand);
        setting.setMaster(null); // 品牌自訂規格，無 master 對應
        setting.setSpecType(upperType);
        setting.setCustomName(trimmed);
        setting.setIsEnabled(true);
        setting.setSortOrder(brandSpecSettingRepository.findMaxSortOrderByBrandIdAndType(brandId, upperType) + 1);
        brandSpecSettingRepository.save(setting);
        Map<String, Object> m = new HashMap<>();
        m.put("brandSpecId", setting.getId());
        m.put("type", upperType);
        m.put("name", trimmed);
        m.put("isEnabled", true);
        m.put("sortOrder", setting.getSortOrder());
        return m;
    }

    @Transactional
    public Map<String, Object> updateBrandSpec(Long brandId, Long brandSpecId, String customName) {
        BrandSpecSetting s = brandSpecSettingRepository.findById(brandSpecId)
                .orElseThrow(() -> new CustomException("404", "規格不存在"));
        if (!s.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限");
        String trimmed = normalizeSpecCustomName(s, customName);
        String type = getSpecType(s);
        String displayName = trimmed != null ? trimmed : getSpecDisplayName(s);
        if (displayName == null || displayName.isBlank())
            throw new CustomException("400", "規格名稱必填");
        if (displayName != null) {
            if (type != null && hasDuplicateSpecDisplayName(brandId, type, displayName, brandSpecId))
                throw new CustomException("409", "此規格名稱已重複");
        }
        s.setCustomName(trimmed);
        s.setIsEnabled(true);
        brandSpecSettingRepository.save(s);
        Map<String, Object> m = new HashMap<>();
        m.put("brandSpecId", s.getId());
        m.put("name", getSpecDisplayName(s));
        m.put("isEnabled", s.getIsEnabled());
        return m;
    }

    @Transactional
    public Map<String, Object> toggleBrandSpec(Long brandId, Long brandSpecId) {
        BrandSpecSetting s = brandSpecSettingRepository.findById(brandSpecId)
                .orElseThrow(() -> new CustomException("404", "規格不存在"));
        if (!s.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限");
        s.setIsEnabled(!Boolean.TRUE.equals(s.getIsEnabled()));
        brandSpecSettingRepository.save(s);
        Map<String, Object> m = new HashMap<>();
        m.put("brandSpecId", s.getId());
        m.put("isEnabled", s.getIsEnabled());
        return m;
    }

    @Transactional
    public void deleteBrandSpec(Long brandId, Long brandSpecId) {
        BrandSpecSetting s = brandSpecSettingRepository.findById(brandSpecId)
                .orElseThrow(() -> new CustomException("404", "規格不存在"));
        if (!s.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限");
        productSpecRelationRepository.deleteByIdBrandSpecId(brandSpecId);
        brandSpecSettingRepository.delete(s);
    }

    // ─── 配料管理 ────────────────────────────────────────────
    @Transactional
    public Map<String, List<Map<String, Object>>> reorderBrandSpecs(Long brandId, BrandSpecReorderRequest req) {
        String type = req.getType() == null ? "" : req.getType().trim().toUpperCase();
        if (!List.of("ICE", "SWEETNESS", "SIZE").contains(type))
            throw new CustomException("400", "type 敹???ICE / SWEETNESS / SIZE");
        List<Long> orderedIds = req.getOrderedSpecIds();
        if (orderedIds == null || orderedIds.isEmpty())
            throw new CustomException("400", "orderedSpecIds 敹‵");

        List<BrandSpecSetting> allSettings = brandSpecSettingRepository.findByBrandIdOrderBySortOrderAscIdAsc(brandId);
        List<BrandSpecSetting> typeSettings = allSettings.stream()
                .filter(s -> {
                    String currentType = s.getMaster() != null ? s.getMaster().getType() : s.getSpecType();
                    return type.equals(currentType);
                })
                .collect(Collectors.toList());

        if (typeSettings.size() != orderedIds.size())
            throw new CustomException("400", "orderedSpecIds 與現有規格數量不一致");

        Map<Long, BrandSpecSetting> settingById = typeSettings.stream()
                .collect(Collectors.toMap(BrandSpecSetting::getId, s -> s));
        for (Long id : orderedIds) {
            if (!settingById.containsKey(id))
                throw new CustomException("400", "排序資料包含非本品牌規格");
        }

        for (int i = 0; i < orderedIds.size(); i++) {
            settingById.get(orderedIds.get(i)).setSortOrder(i);
        }
        brandSpecSettingRepository.saveAll(typeSettings);
        return getBrandSpecs(brandId);
    }

    @Transactional
    public List<Map<String, Object>> getBrandToppings(Long brandId) {
        ensureBrandToppingDefaults(brandId);
        return brandToppingSettingRepository.findByBrandId(brandId).stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("brandToppingId", t.getId());
            ToppingMaster masterTopping = getToppingMasterSafely(t);
            String name = t.getCustomName() != null ? t.getCustomName()
                    : (masterTopping != null ? masterTopping.getName() : "");
            BigDecimal price = t.getBrandPrice() != null ? t.getBrandPrice()
                    : (masterTopping != null ? masterTopping.getDefaultPrice() : BigDecimal.ZERO);
            m.put("name", name);
            m.put("price", price);
            m.put("isEnabled", t.getIsEnabled());
            return m;
        }).collect(Collectors.toList());
    }

    private void ensureBrandToppingDefaults(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "Brand not found"));
        List<BrandToppingSetting> existingSettings = brandToppingSettingRepository.findByBrandId(brandId);
        List<BrandToppingSetting> invalidSettings = new ArrayList<>();
        Set<Long> existingMasterIds = existingSettings.stream()
                .map(setting -> {
                    ToppingMaster masterTopping = getToppingMasterSafely(setting);
                    if (setting.getMasterTopping() != null && masterTopping == null) {
                        invalidSettings.add(setting);
                    }
                    return masterTopping;
                })
                .filter(Objects::nonNull)
                .map(ToppingMaster::getId)
                .collect(Collectors.toSet());

        if (!invalidSettings.isEmpty()) {
            brandToppingSettingRepository.deleteAll(invalidSettings);
        }

        List<BrandToppingSetting> missingDefaults = new ArrayList<>();
        for (ToppingMaster toppingMaster : toppingMasterRepository.findAll()) {
            if (existingMasterIds.contains(toppingMaster.getId())) {
                continue;
            }
            BrandToppingSetting setting = new BrandToppingSetting();
            setting.setBrand(brand);
            setting.setMasterTopping(toppingMaster);
            setting.setIsEnabled(false);
            missingDefaults.add(setting);
        }

        if (!missingDefaults.isEmpty()) {
            brandToppingSettingRepository.saveAll(missingDefaults);
        }
    }

    private ToppingMaster getToppingMasterSafely(BrandToppingSetting setting) {
        try {
            ToppingMaster masterTopping = setting.getMasterTopping();
            if (masterTopping != null) {
                masterTopping.getId();
            }
            return masterTopping;
        } catch (Exception ex) {
            return null;
        }
    }

    private String getToppingDisplayName(BrandToppingSetting setting) {
        String customName = setting.getCustomName();
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        ToppingMaster masterTopping = getToppingMasterSafely(setting);
        return masterTopping != null ? masterTopping.getName() : null;
    }

    private String normalizeToppingCustomName(BrandToppingSetting setting, String customName) {
        String trimmed = customName == null ? null : customName.trim();
        if (trimmed == null || trimmed.isBlank()) {
            return null;
        }
        ToppingMaster masterTopping = getToppingMasterSafely(setting);
        if (masterTopping != null && equalsDisplayName(trimmed, masterTopping.getName())) {
            return null;
        }
        return trimmed;
    }

    private boolean hasDuplicateToppingDisplayName(Long brandId, String displayName, Long excludeId) {
        return brandToppingSettingRepository.findByBrandId(brandId).stream()
                .filter(setting -> excludeId == null || !setting.getId().equals(excludeId))
                .map(this::getToppingDisplayName)
                .anyMatch(existingName -> equalsDisplayName(existingName, displayName));
    }

    @Transactional
    public Map<String, Object> addBrandTopping(Long brandId, String name, BigDecimal price) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "品牌不存在"));
        String trimmed = name == null ? "" : name.trim();
        long current = brandToppingSettingRepository.countByBrandId(brandId);
        if (current >= 10)
            throw new CustomException("400", "配料已達上限（最多 20 個）");
        if (trimmed.isEmpty())
            throw new CustomException("400", "配料名稱必填");
        if (hasDuplicateToppingDisplayName(brandId, trimmed, null))
            throw new CustomException("409", "此配料名稱已重複");
        BrandToppingSetting setting = new BrandToppingSetting();
        setting.setBrand(brand);
        setting.setMasterTopping(null); // 品牌自訂配料
        setting.setCustomName(trimmed);
        setting.setBrandPrice(price != null ? price : BigDecimal.ZERO);
        setting.setIsEnabled(true);
        brandToppingSettingRepository.save(setting);
        Map<String, Object> m = new HashMap<>();
        m.put("brandToppingId", setting.getId());
        m.put("name", trimmed);
        m.put("price", price != null ? price : BigDecimal.ZERO);
        m.put("isEnabled", true);
        return m;
    }

    @Transactional
    public Map<String, Object> toggleBrandTopping(Long brandId, Long brandToppingId) {
        BrandToppingSetting t = brandToppingSettingRepository.findById(brandToppingId)
                .orElseThrow(() -> new CustomException("404", "配料不存在"));
        if (!t.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限");
        t.setIsEnabled(!Boolean.TRUE.equals(t.getIsEnabled()));
        brandToppingSettingRepository.save(t);
        Map<String, Object> m = new HashMap<>();
        m.put("brandToppingId", t.getId());
        m.put("isEnabled", t.getIsEnabled());
        return m;
    }

    @Transactional
    public void deleteBrandTopping(Long brandId, Long brandToppingId) {
        BrandToppingSetting t = brandToppingSettingRepository.findById(brandToppingId)
                .orElseThrow(() -> new CustomException("404", "配料不存在"));
        if (!t.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限");
        productToppingRuleRepository.deleteByIdBrandToppingId(brandToppingId);
        brandToppingSettingRepository.delete(t);
    }

    @Transactional
    public Map<String, Object> updateBrandTopping(Long brandId, Long brandToppingId, String customName,
            BigDecimal price) {
        BrandToppingSetting t = brandToppingSettingRepository.findById(brandToppingId)
                .orElseThrow(() -> new CustomException("404", "配料不存在"));
        if (!t.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限");
        String trimmed = normalizeToppingCustomName(t, customName);
        String displayName = trimmed != null ? trimmed : getToppingDisplayName(t);
        if (displayName == null || displayName.isBlank())
            throw new CustomException("400", "配料名稱必填");
        if (hasDuplicateToppingDisplayName(brandId, displayName, brandToppingId))
            throw new CustomException("409", "此配料名稱已重複");
        t.setCustomName(trimmed);
        if (price != null)
            t.setBrandPrice(price);
        t.setIsEnabled(true);
        brandToppingSettingRepository.save(t);
        String name = getToppingDisplayName(t);
        BigDecimal displayPrice = t.getBrandPrice() != null ? t.getBrandPrice()
                : (t.getMasterTopping() != null ? t.getMasterTopping().getDefaultPrice() : BigDecimal.ZERO);
        Map<String, Object> m = new HashMap<>();
        m.put("brandToppingId", t.getId());
        m.put("name", name);
        m.put("price", displayPrice);
        m.put("isEnabled", t.getIsEnabled());
        return m;
    }

    // ─── 取得單一飲品（含規格與配料）────────────────────────
    @Transactional(readOnly = true)
    public Map<String, Object> getProductDetail(Long brandId, Long productId) {
        ProductTemplate p = productTemplateRepository.findById(productId)
                .orElseThrow(() -> new CustomException("404", "飲品不存在"));
        if (!p.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限");

        Map<String, Object> m = new HashMap<>();
        m.put("productId", p.getId());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("categoryId", p.getCategory() != null ? p.getCategory().getId() : null);
        m.put("basePrice", p.getBasePrice());
        m.put("maxToppings", p.getMaxToppings());
        m.put("logoUrl", p.getLogoUrl());
        m.put("isEnabled", p.getIsEnabled());

        // 容量規格定價（SIZE）
        List<ProductSpecRelation> pricings = productSpecRelationRepository.findSizePricingsByProductId(productId);
        m.put("specPrices", buildSpecPriceList(pricings));

        // 非容量規格（ICE + SWEETNESS），供前端 checkboxes 預選
        List<Long> specIds = productSpecRelationRepository.findByIdProductId(productId)
                .stream().map(r -> r.getId().getBrandSpecId()).collect(Collectors.toList());
        m.put("brandSpecIds", specIds);

        // 配料
        List<Long> toppingIds = productToppingRuleRepository.findByIdProductId(productId)
                .stream().map(r -> r.getId().getBrandToppingId()).collect(Collectors.toList());
        m.put("brandToppingIds", toppingIds);

        return m;
    }
}
