package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.ProductToppingRuleRepository;
import com.example.demo.repository.StoreProductStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductTemplateRepository productTemplateRepository;
    private final com.example.demo.repository.ProductSpecRelationRepository productSpecRelationRepository;
    private final com.example.demo.repository.BrandSpecSettingRepository brandSpecSettingRepository;
    private final com.example.demo.repository.MenuCategoryRepository menuCategoryRepository;
    private final com.example.demo.repository.SpecMasterRepository specMasterRepository;
    private final com.example.demo.repository.BrandToppingSettingRepository brandToppingSettingRepository;
    private final com.example.demo.repository.StoreSpecSettingRepository storeSpecSettingRepository;
    private final com.example.demo.repository.StoreToppingStatusRepository storeToppingStatusRepository;

    @Autowired(required = false)
    private ProductToppingRuleRepository productToppingRuleRepository;

    @Autowired(required = false)
    private StoreProductStatusRepository storeProductStatusRepository;

    // ============================================================
    // Existing methods (updated to use ProductTemplate)
    // ============================================================

    public List<com.example.demo.dto.ProductDTO> getActiveProductsByStoreId(Long storeId) {
        List<ProductTemplate> products = productRepository.findByStoreId(storeId);
        return convertToDTOList(products);
    }

    public List<com.example.demo.dto.ProductDTO> getAllProductsByStoreId(Long storeId) {
        List<ProductTemplate> products = productRepository.findByStoreId(storeId);
        return convertToDTOList(products);
    }

    private List<com.example.demo.dto.ProductDTO> convertToDTOList(List<ProductTemplate> products) {
        if (products.isEmpty()) return List.of();

        // Bulk fetch all relations for these products to avoid N+1
        List<Long> ids = products.stream().map(ProductTemplate::getId).toList();
        List<ProductSpecRelation> allRelations = productSpecRelationRepository.findByProductIds(ids);
        
        // Group relations by product ID
        Map<Long, List<ProductSpecRelation>> relationMap = allRelations.stream()
                .collect(Collectors.groupingBy(r -> r.getId().getProductId()));

        return products.stream()
                .map(p -> convertToDTO(p, relationMap.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    private com.example.demo.dto.ProductDTO convertToDTO(ProductTemplate p, List<ProductSpecRelation> relations) {
        String categoryName = "精選商品";
        Long categoryId = null;
        if (p.getCategory() != null) {
            categoryId = p.getCategory().getId();
            categoryName = p.getCategory().getName() != null ? p.getCategory().getName() : "精選商品";
        }

        // 偵測冷熱標籤
        boolean canBeIced = false;
        boolean canBeHot = false;
        
        for (ProductSpecRelation rel : relations) {
            BrandSpecSetting bss = rel.getBrandSpec();
            if (bss == null) continue;
            String displayName = bss.getCustomName() != null ? bss.getCustomName()
                    : (bss.getMaster() != null ? bss.getMaster().getName() : "");
            if (displayName.contains("冰")) canBeIced = true;
            if (displayName.contains("熱")) canBeHot = true;
        }

        return new com.example.demo.dto.ProductDTO(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getBasePrice(),
                p.getLogoUrl(),
                categoryName,
                categoryId,
                canBeIced,
                canBeHot);
    }

    public ProductTemplate getProductById(Long productId) {
        return productRepository.findById(productId).orElse(null);
    }

    public List<com.example.demo.dto.ProductSpecDTO> getProductSpecs(Long productId) {
        ProductTemplate product = productRepository.findById(productId).orElse(null);
        if (product == null)
            return List.of();

        // 1. Get all relations for this specific product (mainly for SIZE and extra
        // prices)
        List<ProductSpecRelation> relations = productSpecRelationRepository.findByIdProductId(productId);
        Map<Long, ProductSpecRelation> relationMap = relations.stream()
                .collect(Collectors.toMap(r -> r.getId().getBrandSpecId(), r -> r));

        Long brandId = product.getBrand() != null ? product.getBrand().getId() : null;
        if (brandId == null)
            return List.of();

        // 2. 判斷此飲品是否已有明確設定 ICE / SWEETNESS 的 product_spec_relations
        // 同時支援標準規格（master != null）與自訂規格（master == null，靠 specType 判斷）
        boolean hasExplicitIce = relations.stream()
                .anyMatch(r -> {
                    BrandSpecSetting bss = r.getBrandSpec();
                    if (bss == null) return false;
                    String t = bss.getMaster() != null ? bss.getMaster().getType() : bss.getSpecType();
                    return "ICE".equals(t);
                });
        boolean hasExplicitSweetness = relations.stream()
                .anyMatch(r -> {
                    BrandSpecSetting bss = r.getBrandSpec();
                    if (bss == null) return false;
                    String t = bss.getMaster() != null ? bss.getMaster().getType() : bss.getSpecType();
                    return "SWEETNESS".equals(t);
                });

        // 3. Get all brand-level settings and merge with product relations
        return brandSpecSettingRepository.findByBrandId(brandId).stream()
                .map(setting -> {
                    SpecMaster master = setting.getMaster();
                    // 取得規格類型：優先用 spec_master.type，自訂規格則用 brand_spec_setting.spec_type
                    String type = master != null ? master.getType() : setting.getSpecType();
                    if (type == null)
                        return null;

                    ProductSpecRelation relation = relationMap.get(setting.getId());

                    if (relation == null) {
                        // ICE/SWEETNESS：若飲品已有明確設定，非關聯項目一律排除
                        // 若飲品尚未設定過，才 fallback 顯示品牌全部選項
                        if ("ICE".equals(type) && hasExplicitIce) return null;
                        if ("SWEETNESS".equals(type) && hasExplicitSweetness) return null;
                        // SIZE 無關聯一律不顯示
                        if ("SIZE".equals(type)) return null;
                    }

                    String name = (setting.getCustomName() != null && !setting.getCustomName().isEmpty())
                            ? setting.getCustomName()
                            : (master != null ? master.getName() : "");
                    BigDecimal extraPrice = (relation != null) ? relation.getPrice() : BigDecimal.ZERO;

                    return new com.example.demo.dto.ProductSpecDTO(
                            setting.getId(),
                            name,
                            type,
                            extraPrice);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public com.example.demo.dto.ProductCustomizationDTO getProductCustomization(Long productId) {
        return getProductCustomization(productId, null);
    }

    public com.example.demo.dto.ProductCustomizationDTO getProductCustomization(Long productId, Long storeId) {
        ProductTemplate product = productRepository.findById(productId).orElse(null);
        if (product == null)
            return null;

        List<com.example.demo.dto.ProductSpecDTO> specs = getProductSpecs(productId);

        // 若有 storeId，過濾掉分店停用的規格 (優化性能：僅查詢相關的規格設定)
        if (storeId != null && !specs.isEmpty()) {
            List<Long> brandSpecIds = specs.stream().map(com.example.demo.dto.ProductSpecDTO::getBrandSpecId).toList();
            Set<Long> disabledBrandSpecIds = storeSpecSettingRepository.findByIdStoreIdAndIdBrandSpecIdIn(storeId, brandSpecIds).stream()
                    .filter(s -> Boolean.FALSE.equals(s.getIsEnabled()))
                    .map(s -> s.getId().getBrandSpecId())
                    .collect(Collectors.toSet());
            
            if (!disabledBrandSpecIds.isEmpty()) {
                specs = specs.stream()
                        .filter(s -> !disabledBrandSpecIds.contains(s.getBrandSpecId()))
                        .collect(Collectors.toList());
            }
        }

        // 配料：依 product_topping_rule 取得此飲品允許的配料 (優化：一次性 FETCH 關聯對像)
        List<com.example.demo.dto.ProductCustomizationDTO.ToppingDTO> toppings = List.of();
        if (productToppingRuleRepository != null) {
            List<ProductToppingRule> rules = productToppingRuleRepository.findByIdProductId(productId);
            
            // 若有 storeId，僅取出相關配料在此分店售罄的狀態
            Set<Long> outOfStockBrandToppingIds = Set.of();
            if (storeId != null && !rules.isEmpty()) {
                List<Long> ruleToppingIds = rules.stream()
                        .map(r -> r.getBrandTopping().getId())
                        .toList();
                
                outOfStockBrandToppingIds = storeToppingStatusRepository.findByIdStoreIdAndIdBrandToppingIdIn(storeId, ruleToppingIds).stream()
                        .filter(s -> Boolean.TRUE.equals(s.getIsOutOfStock()))
                        .map(s -> s.getId().getBrandToppingId())
                        .collect(Collectors.toSet());
            }

            final Set<Long> outOfStock = outOfStockBrandToppingIds;
            toppings = rules.stream()
                    .map(rule -> {
                        BrandToppingSetting bts = rule.getBrandTopping();
                        if (bts == null || !Boolean.TRUE.equals(bts.getIsEnabled()))
                            return null;
                        if (outOfStock.contains(bts.getId()))
                            return null;
                        ToppingMaster master = bts.getMasterTopping();
                        String name = bts.getCustomName() != null ? bts.getCustomName()
                                : (master != null ? master.getName() : "未命名");
                        return new com.example.demo.dto.ProductCustomizationDTO.ToppingDTO(
                                bts.getId(), name, bts.getBrandPrice());
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        return new com.example.demo.dto.ProductCustomizationDTO(specs, toppings, product.getBasePrice(), product.getMaxToppings());
    }

    // saveProduct() 已隨 POST /api/products 一併移除（未認證寫入漏洞）。
    // 商品建立/修改統一由 BrandService 處理，該處會驗證品牌歸屬。

    // ============================================================
    // methods (Map-based, from 整合ProductService.java)
    // ============================================================

    // ─── 商品詳情 ─────────────────────────────────────────
    public Map<String, Object> getProductDetail(Long productId) {
        ProductTemplate p = productTemplateRepository.findById(productId)
                .orElseThrow(() -> new CustomException("404", "找不到商品"));

        Map<String, Object> result = new HashMap<>();
        result.put("productId", p.getId());
        result.put("name", p.getName());
        result.put("description", p.getDescription());
        result.put("imageUrl", p.getLogoUrl());
        result.put("basePrice", p.getBasePrice());
        result.put("maxToppings", p.getMaxToppings());
        result.put("isEnabled", p.getIsEnabled());
        if (p.getCategory() != null) {
            result.put("categoryId", p.getCategory().getId());
            result.put("categoryName", p.getCategory().getName());
        }
        return result;
    }

    // ─── 商品客製化（規格 + 配料）合併 V2 ───────────────────────
    public Map<String, Object> getProductCustomizationV2(Long productId) {
        ProductTemplate p = productTemplateRepository.findById(productId)
                .orElseThrow(() -> new CustomException("404", "找不到商品"));

        Map<String, Object> result = new HashMap<>();
        result.put("productId", p.getId());
        result.put("name", p.getName());
        result.put("basePrice", p.getBasePrice());

        // 規格（甜度 / 冰塊 / 杯型）
        List<ProductSpecRelation> specRelations = productSpecRelationRepository.findByIdProductId(productId);
        List<Map<String, Object>> sweetness = new ArrayList<>();
        List<Map<String, Object>> ice = new ArrayList<>();
        List<Map<String, Object>> size = new ArrayList<>();

        for (ProductSpecRelation rel : specRelations) {
            BrandSpecSetting bss = rel.getBrandSpec();
            if (bss == null) continue;
            SpecMaster master = bss.getMaster();
            // 取得規格類型：標準規格用 spec_master.type，自訂規格用 brand_spec_setting.spec_type
            String specType = master != null ? master.getType() : bss.getSpecType();
            if (specType == null) continue;
            String displayName = bss.getCustomName() != null ? bss.getCustomName()
                    : (master != null ? master.getName() : "");

            Map<String, Object> opt = new HashMap<>();
            opt.put("specId", bss.getId());
            opt.put("name", displayName);

            switch (specType) {
                case "SWEETNESS" -> sweetness.add(opt);
                case "ICE" -> ice.add(opt);
                case "SIZE" -> {
                    opt.put("price", rel.getPrice());
                    size.add(opt);
                }
            }
        }
        result.put("sweetnessOptions", sweetness);
        result.put("iceOptions", ice);
        result.put("sizeOptions", size);

        // 配料
        if (productToppingRuleRepository != null) {
            List<ProductToppingRule> toppingRules = productToppingRuleRepository.findByIdProductId(productId);
            List<Map<String, Object>> toppings = new ArrayList<>();
            for (ProductToppingRule rule : toppingRules) {
                BrandToppingSetting bts = rule.getBrandTopping();
                if (bts == null || !Boolean.TRUE.equals(bts.getIsEnabled()))
                    continue;
                ToppingMaster master = bts.getMasterTopping();
                String displayName = bts.getCustomName() != null ? bts.getCustomName()
                        : (master != null ? master.getName() : "配料");
                Map<String, Object> t = new HashMap<>();
                t.put("toppingId", bts.getId());
                t.put("name", displayName);
                t.put("price", bts.getBrandPrice() != null ? bts.getBrandPrice()
                        : (master != null ? master.getDefaultPrice() : 0));
                toppings.add(t);
            }
            result.put("toppings", toppings);
        } else {
            result.put("toppings", List.of());
        }

        return result;
    }
}
