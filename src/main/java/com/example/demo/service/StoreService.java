package com.example.demo.service;

import com.example.demo.common.JwtUtils;
import com.example.demo.dto.StoreDTO;
import com.example.demo.dto.StoreRequest;
import com.example.demo.entity.*;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;

    @Autowired(required = false)
    private BrandRepository brandRepository;

    @Autowired(required = false)
    private MenuCategoryRepository menuCategoryRepository;

    @Autowired(required = false)
    private RegionRepository regionRepository;

    @Autowired(required = false)
    private PasswordEncoder passwordEncoder;

    @Autowired(required = false)
    private JwtUtils jwtUtils;

    @Autowired(required = false)
    private LocationService locationService;

    @Autowired(required = false)
    private OpeningHoursValidator openingHoursValidator;

    // ============================================================
    // Existing entity-based methods
    // ============================================================

    public List<Store> getAllOpenStores() {
        return storeRepository.findByIsAcceptingOrdersTrue();
    }

    public List<Store> getStoresByBrandId(Long brandId) {
        return storeRepository.findByBrandId(brandId);
    }

    /**
     * 回傳 Store entity（原始版本）
     */
    public Store getStoreById(Long storeId) {
        return storeRepository.findById(storeId).orElse(null);
    }

    public List<StoreDTO> getOpenStoresSortedByDistance(double userLat, double userLon, String sort, String category,
            boolean freeDelivery) {
        List<Store> stores = storeRepository.findByIsAcceptingOrdersTrue();
        return stores.stream()
                .filter(s -> s.getLatitude() != null && s.getLongitude() != null)
                .map(s -> {
                    double distKm = calculateDistance(userLat, userLon, s.getLatitude().doubleValue(),
                            s.getLongitude().doubleValue());
                    String formattedDist = distKm < 1 ? String.format("%.0f m", distKm * 1000)
                            : String.format("%.1f km", distKm);
                    return new StoreDTO(s, formattedDist);
                })
                .filter(dto -> {
                    // Filter Category
                    if (!"all".equalsIgnoreCase(category)) {
                        if (dto.getCategories() == null || !dto.getCategories().contains(category)) {
                            return false;
                        }
                    }
                    // Filter Free Delivery
                    if (freeDelivery) {
                        if (dto.getDiscount() == null
                                || !dto.getDiscount().contains("免運") && !dto.getDiscount().contains("折扣")) {
                            return false;
                        }
                    }
                    return true;
                })
                .sorted((dto1, dto2) -> {
                    if ("rating".equalsIgnoreCase(sort)) {
                        // High rating first
                        return Double.compare(dto2.getRating() != null ? dto2.getRating() : 0.0,
                                dto1.getRating() != null ? dto1.getRating() : 0.0);
                    } else if ("time".equalsIgnoreCase(sort)) {
                        double d1 = extractNumericDistance(dto1.getDistance());
                        double d2 = extractNumericDistance(dto2.getDistance());
                        return Double.compare(d1, d2);
                    } else {
                        // Default is distance (nearest first)
                        double d1 = extractNumericDistance(dto1.getDistance());
                        double d2 = extractNumericDistance(dto2.getDistance());
                        return Double.compare(d1, d2);
                    }
                })
                .collect(Collectors.toList());
    }

    // ============================================================
    // methods (Map-based, from 整合StoreService.java)
    // ============================================================

    // ─── 登入 ────────────────────────────────────────────────
    public Map<String, Object> login(String account, String password) {
        Store store = storeRepository.findFirstByAccount(account)
                .orElseThrow(() -> new CustomException("404", "帳號不存在"));
        if (!passwordEncoder.matches(password, store.getPasswordHash()))
            throw new CustomException("401", "密碼錯誤");
        String token = jwtUtils.generateToken(store.getId(), store.getRole(), store.getAccount());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("storeId", store.getId());
        data.put("storeName", store.getStoreName());
        data.put("brandId", store.getBrand() != null ? store.getBrand().getId() : null);
        return data;
    }

    // ─── 基本資料 ────────────────────────────────────────────
    // readOnly 交易不可移除：open-in-view=false，toMap() 會讀 store.getBrand()，
    // 沒有交易時 session 已關閉，會拋 LazyInitializationException（曾使此端點固定 500）
    @Transactional(readOnly = true)
    public Map<String, Object> getStoreByIdV2(Long id) {
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new CustomException("404", "店家不存在"));
        return toMap(store);
    }

    @Transactional
    public void updateStoreByBrand(Long brandId, StoreRequest req) {
        Store store = storeRepository.findById(req.getId())
                .orElseThrow(() -> new CustomException("404", "店家不存在"));
        if (store.getBrand() == null || !store.getBrand().getId().equals(brandId))
            throw new CustomException("403", "無權限操作此分店");
        if (req.getStoreName() != null)
            store.setStoreName(req.getStoreName());
        if (req.getCoverUrl() != null)
            store.setCoverUrl(req.getCoverUrl());
        if (req.getManagerName() != null)
            store.setManagerName(req.getManagerName());
        if (req.getManagerPhone() != null)
            store.setManagerPhone(req.getManagerPhone());
        if (req.getAddress() != null)
            store.setAddress(req.getAddress());
        if (req.getAddress() != null && (req.getLatitude() == null || req.getLongitude() == null)) {
            locationService.geocodeAddress(req.getAddress()).ifPresentOrElse(coords -> {
                store.setLatitude(coords.getLatitude());
                store.setLongitude(coords.getLongitude());
            }, () -> System.out.println("[Warning] Geocoding failed for address: " + req.getAddress()));
        }
        if (req.getLatitude() != null)
            store.setLatitude(req.getLatitude());
        if (req.getLongitude() != null)
            store.setLongitude(req.getLongitude());
        if (req.getOpeningHours() != null)
            store.setOpeningHours(openingHoursValidator.normalize(req.getOpeningHours()));
        if (req.getRegionId() != null) {
            regionRepository.findById(req.getRegionId()).ifPresent(store::setRegion);
        }
        storeRepository.save(store);
    }

    /**
     * 分店自行更新資料。
     *
     * storeId 一律取自 JWT，不採用 req.getId()——舊版直接信任 request body 的 id，
     * 任何人都能改到別家店的資料。
     */
    @Transactional
    public void updateStore(Long storeId, StoreRequest req) {
        if (storeId == null)
            throw new CustomException("401", "請先登入");
        if (req.getId() != null && !req.getId().equals(storeId))
            throw new CustomException("403", "無權限操作此分店");

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException("404", "店家不存在"));
        if (req.getStoreName() != null)
            store.setStoreName(req.getStoreName());
        if (req.getCoverUrl() != null)
            store.setCoverUrl(req.getCoverUrl());
        if (req.getManagerName() != null)
            store.setManagerName(req.getManagerName());
        if (req.getManagerPhone() != null)
            store.setManagerPhone(req.getManagerPhone());
        if (req.getAddress() != null)
            store.setAddress(req.getAddress());
        if (req.getAddress() != null && (req.getLatitude() == null || req.getLongitude() == null)) {
            locationService.geocodeAddress(req.getAddress()).ifPresentOrElse(coords -> {
                store.setLatitude(coords.getLatitude());
                store.setLongitude(coords.getLongitude());
            }, () -> System.out.println("[Warning] Geocoding failed for address: " + req.getAddress()));
        }
        if (req.getLatitude() != null)
            store.setLatitude(req.getLatitude());
        if (req.getLongitude() != null)
            store.setLongitude(req.getLongitude());
        if (req.getOpeningHours() != null)
            store.setOpeningHours(openingHoursValidator.normalize(req.getOpeningHours()));
        if (req.getRegionId() != null) {
            regionRepository.findById(req.getRegionId()).ifPresent(store::setRegion);
        }
        storeRepository.save(store);
    }

    @Transactional
    public void updateStatus(Long storeId, String status) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException("404", "店家不存在"));
        if (status == null)
            throw new CustomException("400", "狀態不能為空");
        store.setStatus(status);
        storeRepository.save(store);
    }

    // ─── 設定 ────────────────────────────────────────────────
    public Map<String, Object> getProfile(Long storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException("404", "店家不存在"));
        return toMap(store);
    }

    @Transactional
    public void updateBusinessHours(Long storeId, Object openingHours) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException("404", "店家不存在"));
        store.setOpeningHours(openingHoursValidator.normalize(openingHours));
        storeRepository.save(store);
    }

    @Transactional
    public void updateDeliveryConfig(Long storeId, Boolean allowsDelivery, BigDecimal maxKm,
            BigDecimal minAmount, String startTime, String endTime) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException("404", "店家不存在"));
        if (allowsDelivery != null)
            store.setIsDeliveryAvailable(allowsDelivery);
        if (maxKm != null)
            store.setMaxDeliveryKm(maxKm);
        if (minAmount != null)
            store.setMinDeliveryAmount(minAmount);
        if (startTime != null)
            store.setDeliveryStartTime(startTime);
        if (endTime != null)
            store.setDeliveryEndTime(endTime);
        storeRepository.save(store);
    }

    // ─── private helpers ──────────────────────────────────────

    /**
     * 共用的 StoreRequest → Store 屬性複製邏輯
     */
    private void applyStoreRequest(Store store, StoreRequest req) {
        if (req.getStoreName() != null)
            store.setStoreName(req.getStoreName());
        if (req.getCoverUrl() != null)
            store.setCoverUrl(req.getCoverUrl());
        if (req.getManagerName() != null)
            store.setManagerName(req.getManagerName());
        if (req.getManagerPhone() != null)
            store.setManagerPhone(req.getManagerPhone());
        if (req.getAddress() != null)
            store.setAddress(req.getAddress());
        if (req.getAddress() != null && (req.getLatitude() == null || req.getLongitude() == null)) {
            if (locationService != null) {
                locationService.geocodeAddress(req.getAddress()).ifPresentOrElse(coords -> {
                    store.setLatitude(coords.getLatitude());
                    store.setLongitude(coords.getLongitude());
                }, () -> System.out.println("[Warning] Geocoding failed for address: " + req.getAddress()));
            }
        }
        if (req.getLatitude() != null)
            store.setLatitude(req.getLatitude());
        if (req.getLongitude() != null)
            store.setLongitude(req.getLongitude());
        if (req.getOpeningHours() != null && openingHoursValidator != null) {
            store.setOpeningHours(openingHoursValidator.normalize(req.getOpeningHours()));
        }
        if (req.getRegionId() != null && regionRepository != null) {
            regionRepository.findById(req.getRegionId()).ifPresent(store::setRegion);
        }
    }

    private Map<String, Object> toMap(Store store) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", store.getId());
        m.put("name", store.getStoreName());
        m.put("phone", store.getManagerPhone());
        m.put("storePhone", store.getStorePhone());
        m.put("address", store.getAddress());
        m.put("coverUrl", store.getCoverUrl());

        if (store.getBrand() != null) {
            Map<String, Object> b = new HashMap<>();
            b.put("brandId", store.getBrand().getId());
            b.put("name", store.getBrand().getName());
            b.put("logoUrl", store.getBrand().getLogoUrl());
            m.put("brand", b);
        }

        m.put("status", store.getStatus());
        m.put("isDeliveryAvailable", store.getIsDeliveryAvailable());
        m.put("maxDeliveryKm", store.getMaxDeliveryKm());
        m.put("minDeliveryAmount", store.getMinDeliveryAmount());
        m.put("deliveryStartTime", store.getDeliveryStartTime());
        m.put("deliveryEndTime", store.getDeliveryEndTime());
        m.put("openingHours", store.getOpeningHours());
        m.put("latitude", store.getLatitude());
        m.put("longitude", store.getLongitude());
        m.put("avgRating", store.getAvgRating());
        m.put("reviewCount", store.getReviewCount());
        return m;
    }

    private double extractNumericDistance(String distance) {
        if (distance == null)
            return 999.0;
        if (distance.endsWith(" km"))
            return Double.parseDouble(distance.replace(" km", ""));
        return Double.parseDouble(distance.replace(" m", "")) / 1000.0;
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        // Haversine formula
        final int R = 6371; // Earth radius in km
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c; // returns distance in km
    }
}
