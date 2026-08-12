package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 公開查詢（不需登入）。
 *
 * ⚠️ 類別層級的 readOnly 交易不可移除：本專案 `spring.jpa.open-in-view: false`，
 *    沒有交易時 session 會在 repository 呼叫結束就關閉，接著存取 lazy 關聯即拋
 *    LazyInitializationException。這裡 7 個方法全都會經過 buildStoreCard 讀取
 *    `store.getBrand().getName()`，其中走 findById 的路徑（getStoreDetail）沒有
 *    fetch join，曾因此讓 GET /api/stores/{id} 與 /api/stores/{id}/info 固定 500。
 */
@Service
@Transactional(readOnly = true)
public class PublicService {

    @Autowired private StoreRepository storeRepository;
    @Autowired private BrandRepository brandRepository;
    @Autowired private UserCouponRepository userCouponRepository;
    @Autowired private UserFavoriteRepository userFavoriteRepository;

    // ─── 首頁 ───────────────────────────────────────────────
    public Map<String, Object> getHome(Double lat, Double lng, Long userId) {
        Map<String, Object> result = new HashMap<>();

        // banners（目前回傳空陣列，未來可從 DB 取）
        result.put("banners", List.of());

        // 附近店家（最多 6 筆）
        result.put("nearbyStores", getNearbyStores(lat, lng, null, null, 6, 0, userId));

        // 轉盤狀態
        Map<String, Object> gameWheel = new HashMap<>();
        gameWheel.put("isLogin", userId != null);
        gameWheel.put("todayPlayed", false); // 由 UserCouponService 決定
        result.put("gameWheel", gameWheel);

        return result;
    }

    // ─── 附近店家 ──────────────────────────────────────────
    public Map<String, Object> getNearbyStoresPage(Double lat, Double lng,
                                                    Long brandId, Double minRating,
                                                    int page, int size, Long userId) {
        List<Map<String, Object>> stores = getNearbyStores(lat, lng, brandId, minRating, size, (page - 1) * size, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("stores", stores);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    private List<Map<String, Object>> getNearbyStores(Double lat, Double lng,
                                                        Long brandId, Double minRating,
                                                        int size, int offset, Long userId) {
        if (lat == null || lng == null) {
            // 沒有定位時回傳所有 active 店家
            List<Store> all = storeRepository.findByStatus("active");
            List<Map<String, Object>> res = new ArrayList<>();
        java.util.Set<Long> favoriteStoreIds = (userId != null) ? userFavoriteRepository.findStoreIdsByUserId(userId) : java.util.Collections.emptySet();
        for (Store s : all) res.add(buildStoreCard(s, null, favoriteStoreIds));
        return res;
    }
    List<Object[]> rows = storeRepository.findNearbyStores(lat, lng, brandId, minRating, size, offset);
    if (rows.isEmpty()) return Collections.emptyList();

    List<Long> storeIds = rows.stream().map(row -> ((Number) row[0]).longValue()).toList();
    Map<Long, Double> distanceMap = new HashMap<>();
    for (Object[] row : rows) {
        distanceMap.put(((Number) row[0]).longValue(), ((Number) row[row.length - 1]).doubleValue());
    }

    List<Store> stores = storeRepository.findAllById(storeIds);
    // 按原始距離順序排序
    stores.sort(Comparator.comparing(s -> storeIds.indexOf(s.getId())));

    java.util.Set<Long> favoriteStoreIds = (userId != null) ? userFavoriteRepository.findStoreIdsByUserId(userId) : java.util.Collections.emptySet();
    List<Map<String, Object>> res = new ArrayList<>();
    for (Store s : stores) {
        res.add(buildStoreCard(s, distanceMap.get(s.getId()), favoriteStoreIds));
    }
    return res;
}

    // ─── 地圖店家 ──────────────────────────────────────────
    public List<Map<String, Object>> getMapStores(Double lat, Double lng) {
        List<Store> stores = storeRepository.findByStatus("active");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Store s : stores) {
            Map<String, Object> m = new HashMap<>();
            m.put("storeId", s.getId());
            m.put("storeName", s.getStoreName());
            m.put("latitude", s.getLatitude());
            m.put("longitude", s.getLongitude());
            m.put("status", s.getStatus());
            result.add(m);
        }
        return result;
    }

    // ─── 搜尋店家 ──────────────────────────────────────────
    public Map<String, Object> searchStores(String keyword, Double lat, Double lng, int page, int size, Long userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (lat != null && lng != null) {
            String likeKeyword = "%" + keyword + "%";
            List<Object[]> rows = storeRepository.searchByKeywordWithDistance(likeKeyword, lat, lng);
            if (!rows.isEmpty()) {
                List<Long> storeIds = rows.stream().map(row -> ((Number) row[0]).longValue()).toList();
                Map<Long, Double> distanceMap = new HashMap<>();
                for (Object[] row : rows) {
                    distanceMap.put(((Number) row[0]).longValue(), ((Number) row[row.length - 1]).doubleValue());
                }
                List<Store> stores = storeRepository.findAllById(storeIds);
                stores.sort(Comparator.comparing(s -> storeIds.indexOf(s.getId())));
                java.util.Set<Long> favoriteStoreIds = (userId != null) ? userFavoriteRepository.findStoreIdsByUserId(userId) : java.util.Collections.emptySet();
                for (Store s : stores) {
                    list.add(buildStoreCard(s, distanceMap.get(s.getId()), favoriteStoreIds));
                }
            }
        } else {
            List<Store> stores = storeRepository.searchByBrandName(keyword);
            java.util.Set<Long> favoriteStoreIds = (userId != null) ? userFavoriteRepository.findStoreIdsByUserId(userId) : java.util.Collections.emptySet();
            for (Store s : stores) list.add(buildStoreCard(s, null, favoriteStoreIds));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("stores", list);
        result.put("total", list.size());
        return result;
    }

    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    // ─── 店家詳情 ─────────────────────────────────────────
    public Map<String, Object> getStoreDetail(Long storeId, Long userId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException("404", "找不到店家"));
        
        java.util.Set<Long> favoriteStoreIds = (userId != null) ? userFavoriteRepository.findStoreIdsByUserId(userId) : java.util.Collections.emptySet();
        Map<String, Object> result = buildStoreCard(store, null, favoriteStoreIds);

        // 品牌資訊
        Brand brand = store.getBrand();
        if (brand != null) {
            Map<String, Object> brandMap = new HashMap<>();
            brandMap.put("brandId", brand.getId());
            brandMap.put("brandName", brand.getName());
            brandMap.put("logoUrl", brand.getLogoUrl());
            result.put("brand", brandMap);
        }
        return result;
    }

    // ─── 品牌列表 ─────────────────────────────────────────
    public List<Map<String, Object>> getBrands() {
        List<Brand> brands = brandRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Brand b : brands) {
            Map<String, Object> m = new HashMap<>();
            m.put("brandId", b.getId());
            m.put("brandName", b.getName());
            m.put("logoUrl", b.getLogoUrl());
            result.add(m);
        }
        return result;
    }

    // ─── 品牌詳情 ─────────────────────────────────────────
    public Map<String, Object> getBrandDetail(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "找不到品牌"));
        List<Store> stores = storeRepository.findByBrandId(brandId);

        Map<String, Object> result = new HashMap<>();
        result.put("brandId", brand.getId());
        result.put("brandName", brand.getName());
        result.put("logoUrl", brand.getLogoUrl());
        result.put("storeCount", stores.size());

        List<Map<String, Object>> storeList = new ArrayList<>();
        for (Store s : stores) storeList.add(buildStoreCard(s, null, java.util.Collections.emptySet()));
        result.put("stores", storeList);
        return result;
    }

    // ─── 共用：建立店家卡片 ─────────────────────────────────
    private Map<String, Object> buildStoreCard(Store s, Double distance, java.util.Set<Long> favoriteStoreIds) {
        Map<String, Object> m = new HashMap<>();
        m.put("storeId", s.getId());
        m.put("id", s.getId());
        m.put("storeName", s.getStoreName());
        m.put("store_name", s.getStoreName());
        
        // 圖片優化 (Cloudinary 轉換)
        String coverUrl = s.getCoverUrl();
        if (coverUrl != null && coverUrl.contains("cloudinary.com/")) {
            coverUrl = coverUrl.replace("/upload/", "/upload/c_fill,h_400,w_600,f_auto,q_auto/");
        }
        m.put("coverUrl", coverUrl);
        m.put("cover_url", coverUrl);
        
        m.put("rating", s.getAvgRating());
        m.put("avgRating", s.getAvgRating());
        m.put("avg_rating", s.getAvgRating());
        m.put("reviewCount", s.getReviewCount());
        m.put("review_count", s.getReviewCount());
        m.put("address", s.getAddress());
        m.put("status", s.getStatus());
        m.put("isAcceptingOrders", s.getIsAcceptingOrders());
        m.put("allowsDelivery", s.getIsDeliveryAvailable());
        m.put("minDeliveryAmount", s.getMinDeliveryAmount());
        if (s.getBrand() != null) {
            m.put("brandName", s.getBrand().getName());
            m.put("brand_name", s.getBrand().getName());
            m.put("brandLogoUrl", s.getBrand().getLogoUrl());
        }
        if (distance != null) {
            m.put("distance", Math.round(distance * 10.0) / 10.0);
        }
        
        // 檢查是否收藏 (節省前端一次 API) - 已優化為 O(1) Set 查找
        boolean isFavorite = favoriteStoreIds.contains(s.getId());
        m.put("isFavorite", isFavorite);
        m.put("is_favorite", isFavorite);
        // 今日營業時間（從 JSON 取當天）
        m.put("openingHours", s.getOpeningHours());
        m.put("latitude", s.getLatitude());
        m.put("longitude", s.getLongitude());
        return m;
    }
}
