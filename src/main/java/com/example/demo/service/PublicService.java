package com.example.demo.service;

import com.example.demo.common.GeoBounds;
import com.example.demo.dto.BrandSummary;
import com.example.demo.entity.Brand;
import com.example.demo.entity.Store;
import com.example.demo.exception.CustomException;
import com.example.demo.common.BrandApproval;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserFavoriteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公開查詢（不需登入）。
 *
 * ⚠️ 類別層級的 readOnly 交易不可移除：本專案 `spring.jpa.open-in-view: false`，
 *    沒有交易時 session 會在 repository 呼叫結束就關閉，接著存取 lazy 關聯即拋
 *    LazyInitializationException。buildStoreCard 會讀取 `store.getBrand()`，
 *    而 getStoreDetail 走 findById 的路徑（沒有 fetch join），曾因此讓
 *    GET /api/stores/{id} 與 /api/stores/{id}/info 固定 500。
 */
@Service
@Transactional(readOnly = true)
public class PublicService {

    /** 附近查詢的半徑（公里）。SQL 的 HAVING 與 GeoBounds 的矩形共用這個值。 */
    static final double NEARBY_RADIUS_KM = 50.0;
    /** 單頁筆數上限：分頁的目的是讓回應大小有上界（與 AnalyticsService.MAX_PAGE_SIZE 的做法一致）。 */
    static final int MAX_PAGE_SIZE = 50;
    /** 頁碼上限：避免 page × size 整數溢位（100000 × 50 = 5,000,000，遠小於 int 上限）。 */
    static final int MAX_PAGE = 100_000;
    /** 搜尋結果每頁筆數上限（預設值 50 在 PublicController）。 */
    static final int SEARCH_MAX_PAGE_SIZE = 200;

    @Autowired private StoreRepository storeRepository;
    @Autowired private BrandRepository brandRepository;
    @Autowired private UserFavoriteRepository userFavoriteRepository;

    // ─── 首頁 ───────────────────────────────────────────────
    public Map<String, Object> getHome(Double lat, Double lng, Long userId) {
        Map<String, Object> result = new HashMap<>();

        // banners（目前回傳空陣列，未來可從 DB 取）
        result.put("banners", List.of());

        // 附近店家（最多 6 筆，第一頁）
        result.put("nearbyStores", getNearbyStores(lat, lng, null, null, 0, 6, userId));

        // 轉盤狀態
        Map<String, Object> gameWheel = new HashMap<>();
        gameWheel.put("isLogin", userId != null);
        gameWheel.put("todayPlayed", false); // 由 UserCouponService 決定
        result.put("gameWheel", gameWheel);

        return result;
    }

    // ─── 附近店家 ──────────────────────────────────────────

    /**
     * @param page 1-based 頁碼；小於 1 視為 1，超過 {@link #MAX_PAGE} 截為上限
     * @param size 每頁筆數；小於 1 視為 1，超過 {@link #MAX_PAGE_SIZE} 截為上限
     */
    public Map<String, Object> getNearbyStoresPage(Double lat, Double lng,
                                                    Long brandId, Double minRating,
                                                    int page, int size, Long userId) {
        int safePage = Math.min(Math.max(1, page), MAX_PAGE);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        List<Map<String, Object>> stores =
                getNearbyStores(lat, lng, brandId, minRating, safePage - 1, safeSize, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("stores", stores);
        result.put("page", safePage);
        result.put("size", safeSize);
        return result;
    }

    /**
     * @param pageIndex 0-based 頁索引（已由呼叫端夾在合法範圍內）
     */
    private List<Map<String, Object>> getNearbyStores(Double lat, Double lng,
                                                      Long brandId, Double minRating,
                                                      int pageIndex, int size, Long userId) {
        Set<Long> favorites = favoritesOf(userId);

        if (lat == null || lng == null) {
            // 沒有定位：依 id 分頁列出營業中店家。
            // 原本這裡忽略 page／size，會把全部店家回傳給呼叫端。
            // 評分篩選需要距離排序的查詢路徑，無定位時不套用（見 API.md）。
            PageRequest page = PageRequest.of(pageIndex, size);
            List<Store> stores = (brandId == null)
                    ? storeRepository.findByStatusOrderByIdAsc("active", page)
                    : storeRepository.findByStatusAndBrandIdOrderByIdAsc("active", brandId, page);
            List<Map<String, Object>> cards = new ArrayList<>(stores.size());
            for (Store s : stores) {
                cards.add(buildStoreCard(s, null, favorites));
            }
            return cards;
        }

        requireValidCoordinates(lat, lng);
        // 第一段：矩形預篩（可走 idx_stores_lat_lng）；第二段：SQL 內以 Haversine 精確篩選與排序
        GeoBounds box = GeoBounds.around(lat, lng, NEARBY_RADIUS_KM);
        List<Object[]> rows = storeRepository.findNearbyStores(
                lat, lng,
                box.latMin(), box.latMax(), box.lngMin(), box.lngMax(),
                NEARBY_RADIUS_KM, brandId, minRating,
                size, pageIndex * size);
        if (rows.isEmpty()) {
            return List.of();
        }

        // 查詢的 SELECT 順序：第一欄 id、最後一欄 distance
        List<Long> storeIds = new ArrayList<>(rows.size());
        Map<Long, Double> distanceById = new HashMap<>();
        for (Object[] row : rows) {
            Long id = ((Number) row[0]).longValue();
            storeIds.add(id);
            distanceById.put(id, ((Number) row[row.length - 1]).doubleValue());
        }

        Map<Long, Store> storeById = indexById(storeRepository.findAllById(storeIds));
        List<Map<String, Object>> cards = new ArrayList<>(storeIds.size());
        for (Long id : storeIds) {          // 依查詢回傳的距離順序輸出
            Store s = storeById.get(id);
            if (s != null) {
                cards.add(buildStoreCard(s, distanceById.get(id), favorites));
            }
        }
        return cards;
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
    /**
     * 搜尋店家（分頁）。total 是符合條件的真實總筆數（另以 COUNT 取得）；
     * 舊版的 total 只是本頁長度，前端無從判斷還有沒有下一頁。
     *
     * @param page 1-based 頁碼；小於 1 視為 1，超過 {@link #MAX_PAGE} 截為上限
     * @param size 每頁筆數；小於 1 視為 1，超過 {@link #SEARCH_MAX_PAGE_SIZE} 截為上限
     */
    public Map<String, Object> searchStores(String keyword, Double lat, Double lng,
                                            int page, int size, Long userId) {
        int safePage = Math.min(Math.max(1, page), MAX_PAGE);
        int safeSize = Math.min(Math.max(1, size), SEARCH_MAX_PAGE_SIZE);
        int pageIndex = safePage - 1;

        Set<Long> favorites = favoritesOf(userId);
        List<Map<String, Object>> list = new ArrayList<>();
        if (lat != null && lng != null) {
            requireValidCoordinates(lat, lng);
            String likeKeyword = "%" + keyword + "%";
            List<Object[]> rows = storeRepository.searchByKeywordWithDistance(
                    likeKeyword, lat, lng, safeSize, pageIndex * safeSize);
            if (!rows.isEmpty()) {
                List<Long> storeIds = new ArrayList<>(rows.size());
                Map<Long, Double> distanceById = new HashMap<>();
                for (Object[] row : rows) {
                    Long id = ((Number) row[0]).longValue();
                    storeIds.add(id);
                    // 距離為 NULL 代表店家沒有座標：卡片不顯示距離，不讓整個搜尋失敗
                    Object rawDistance = row[row.length - 1];
                    distanceById.put(id, rawDistance == null ? null : ((Number) rawDistance).doubleValue());
                }
                Map<Long, Store> storeById = indexById(storeRepository.findAllById(storeIds));
                for (Long id : storeIds) {          // 依查詢回傳的距離順序輸出
                    Store s = storeById.get(id);
                    if (s != null) {
                        list.add(buildStoreCard(s, distanceById.get(id), favorites));
                    }
                }
            }
        } else {
            PageRequest pageable = PageRequest.of(pageIndex, safeSize);
            for (Store s : storeRepository.searchByBrandName(keyword, pageable)) {
                list.add(buildStoreCard(s, null, favorites));
            }
        }

        long total = storeRepository.countSearchByKeyword(keyword);
        int totalPages = (int) ((total + safeSize - 1) / safeSize);
        Map<String, Object> result = new HashMap<>();
        result.put("stores", list);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", total);
        result.put("totalPages", totalPages);
        result.put("hasNext", safePage < totalPages);
        return result;
    }

    // ─── 店家詳情 ─────────────────────────────────────────
    public Map<String, Object> getStoreDetail(Long storeId, Long userId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException("404", "找不到店家"));

        Map<String, Object> result = buildStoreCard(store, null, favoritesOf(userId));

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

    /**
     * 品牌列表（首頁篩選與轉盤共用）。
     * 回傳的 record 欄位名稱就是前端讀取的 JSON key，契約見 {@link BrandSummary}。
     */
    public List<BrandSummary> getBrands() {
        // N-28：待審與未通過的品牌不進入公開列表
        return brandRepository.findByApprovalStatus(BrandApproval.APPROVED).stream()
                .map(b -> new BrandSummary(b.getId(), b.getName(), b.getLogoUrl()))
                .toList();
    }

    // ─── 品牌詳情 ─────────────────────────────────────────
    public Map<String, Object> getBrandDetail(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "找不到品牌"));
        // N-28：未核准的品牌對外視同不存在
        if (!BrandApproval.isApproved(brand.getApprovalStatus()))
            throw new CustomException("404", "找不到品牌");
        List<Store> stores = storeRepository.findByBrandId(brandId);

        Map<String, Object> result = new HashMap<>();
        result.put("brandId", brand.getId());
        result.put("brandName", brand.getName());
        result.put("logoUrl", brand.getLogoUrl());
        result.put("storeCount", stores.size());

        List<Map<String, Object>> storeList = new ArrayList<>();
        for (Store s : stores) {
            storeList.add(buildStoreCard(s, null, Collections.emptySet()));
        }
        result.put("stores", storeList);
        return result;
    }

    // ─── 共用 ─────────────────────────────────────────────

    /** 未登入回傳空集合；已登入回傳收藏的店家 id。 */
    private Set<Long> favoritesOf(Long userId) {
        return userId != null ? userFavoriteRepository.findStoreIdsByUserId(userId) : Collections.emptySet();
    }

    private static Map<Long, Store> indexById(List<Store> stores) {
        Map<Long, Store> byId = new HashMap<>();
        for (Store s : stores) {
            byId.put(s.getId(), s);
        }
        return byId;
    }

    /** 經緯度必須是有限數字，且落在地球座標範圍內；否則 400（不再靜默算出錯誤距離）。 */
    public static void requireValidCoordinates(double lat, double lng) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
            throw new CustomException("400", "經緯度超出合法範圍");
        }
    }

    // ─── 共用：建立店家卡片 ─────────────────────────────────
    private Map<String, Object> buildStoreCard(Store s, Double distance, Set<Long> favoriteStoreIds) {
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
