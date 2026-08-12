package com.example.demo.service;

import com.example.demo.entity.BrandRegionCategoryPricing;
import com.example.demo.entity.MenuCategory;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Store;
import com.example.demo.entity.StoreProductStatus;
import com.example.demo.entity.StoreProductStatusId;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class MenuService {

    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuCategoryRepository menuCategoryRepository;
    @Autowired private ProductTemplateRepository productTemplateRepository;
    @Autowired private StoreProductStatusRepository storeProductStatusRepository;
    @Autowired private BrandRegionCategoryPricingRepository brandRegionCategoryPricingRepository;

    // 取得分店菜單（依分類分組，含售罄狀態、區域加價）
    public List<Map<String, Object>> getStoreMenu(Long storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException("404", "找不到店家"));

        Long brandId = store.getBrand().getId();
        List<MenuCategory> categories = menuCategoryRepository.findByBrandId(brandId);
        List<ProductTemplate> products = productTemplateRepository.findByBrandId(brandId);

        // 查這間店的售罄狀態
        Map<Long, Boolean> outOfStockMap = new HashMap<>();
        for (ProductTemplate p : products) {
            StoreProductStatusId sid = new StoreProductStatusId();
            sid.setStoreId(storeId);
            sid.setProductId(p.getId());
            Optional<StoreProductStatus> status = storeProductStatusRepository.findById(sid);
            outOfStockMap.put(p.getId(), status.map(s -> !s.getIsEnabled()).orElse(false));
        }

        // 查此門市區域的分類加價
        Map<Long, BigDecimal> offsetByCategoryId = new HashMap<>();
        if (store.getRegion() != null) {
            List<BrandRegionCategoryPricing> pricings =
                brandRegionCategoryPricingRepository.findByBrandIdAndRegionId(brandId, store.getRegion().getId());
            for (BrandRegionCategoryPricing p : pricings) {
                offsetByCategoryId.put(p.getCategory().getId(), p.getPriceOffset());
            }
        }

        // 依分類分組
        List<Map<String, Object>> result = new ArrayList<>();
        for (MenuCategory cat : categories) {
            BigDecimal offset = offsetByCategoryId.getOrDefault(cat.getId(), BigDecimal.ZERO);
            List<Map<String, Object>> items = new ArrayList<>();
            for (ProductTemplate p : products) {
                if (p.getCategory() != null && p.getCategory().getId().equals(cat.getId())) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("productId", p.getId());
                    item.put("sortOrder", p.getSortOrder());
                    item.put("name", p.getName());
                    item.put("basePrice", p.getBasePrice() != null ? p.getBasePrice().add(offset) : offset);
                    item.put("logoUrl", p.getLogoUrl());
                    item.put("isOutOfStock", outOfStockMap.getOrDefault(p.getId(), false));
                    items.add(item);
                }
            }
            Map<String, Object> catMap = new HashMap<>();
            catMap.put("categoryId", cat.getId());
            catMap.put("categoryName", cat.getName());
            catMap.put("sortOrder", cat.getSortOrder());
            catMap.put("items", items);
            result.add(catMap);
        }
        return result;
    }
}
