package com.example.demo.service;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountsSuspendedEvent;
import com.example.demo.common.BrandApproval;
import com.example.demo.common.JwtUtils;
import com.example.demo.entity.AdminAccount;
import com.example.demo.entity.Brand;
import com.example.demo.entity.Store;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.AdminAccountRepository;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理員後台的業務邏輯：管理員登入（N-28）、品牌審核（N-28）、品牌與門市的停用與恢復（stage 6）。
 *
 * <p>審核與停用都採條件式更新（{@link BrandRepository#transitionApprovalStatus}、
 * {@link BrandRepository#suspendBrand}、{@link StoreRepository#suspendStore} 等），同一筆資料只會被成功處理一次。
 * 管理員帳號只能由 {@link com.example.demo.common.AdminBootstrap} 建立，沒有公開的註冊途徑。
 *
 * <p>停用的權杖失效靠 tokenVersion：停用時遞增（品牌與其底下所有分店，或單一分店），
 * 恢復時不遞增，所以停用前簽發的權杖在恢復後仍然無效，使用者必須重新登入。
 * 既有的 WebSocket 連線在交易提交後由 {@link AccountsSuspendedEvent} 切斷。
 */
@Slf4j
@Service
public class AdminService {

    /** 列表每頁上限（列表端點一律分頁，見 CLAUDE.md） */
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 50;

    private static final String BRAND_ROLE = "BRAND";
    private static final String STORE_ROLE = "STORE";

    @Autowired
    private AdminAccountRepository adminAccountRepository;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** 管理員登入。帳號不存在與密碼錯誤回同一則訊息，避免列舉管理員帳號。 */
    public Map<String, Object> login(String username, String password) {
        AdminAccount admin = adminAccountRepository.findByUsername(username).orElse(null);
        if (admin == null || !passwordEncoder.matches(password, admin.getPasswordHash())) {
            throw new CustomException("401", "帳號或密碼錯誤");
        }
        String token = jwtUtils.generateToken(admin.getId(), admin.getRole(), admin.getUsername(),
                admin.getTokenVersion());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("role", admin.getRole());
        return data;
    }

    /** 待審品牌清單，依建立時間由舊到新。page 從 0 起算；size 預設 50、上限 100。 */
    @Transactional(readOnly = true)
    public Map<String, Object> listPendingBrands(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<Brand> result = brandRepository.findByApprovalStatus(BrandApproval.PENDING,
                PageRequest.of(safePage, safeSize,
                        Sort.by("createdAt").ascending().and(Sort.by("id").ascending())));

        List<Map<String, Object>> brands = new ArrayList<>();
        for (Brand brand : result.getContent()) {
            Map<String, Object> row = new HashMap<>();
            row.put("brandId", brand.getId());
            row.put("name", brand.getName());
            row.put("account", brand.getAccount());
            row.put("createdAt", brand.getCreatedAt());
            brands.add(row);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("brands", brands);
        data.put("page", safePage);
        data.put("size", safeSize);
        data.put("total", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());
        data.put("hasNext", result.hasNext());
        return data;
    }

    /** 已核准品牌清單（含停用狀態），依 id 由小到大；分頁規則同待審清單。 */
    @Transactional(readOnly = true)
    public Map<String, Object> listApprovedBrands(int page, int size) {
        int safePage = clampPage(page);
        int safeSize = clampSize(size);
        Page<Brand> result = brandRepository.findByApprovalStatus(BrandApproval.APPROVED,
                PageRequest.of(safePage, safeSize, Sort.by("id").ascending()));

        List<Map<String, Object>> brands = new ArrayList<>();
        for (Brand brand : result.getContent()) {
            Map<String, Object> row = new HashMap<>();
            row.put("brandId", brand.getId());
            row.put("name", brand.getName());
            row.put("account", brand.getAccount());
            row.put("suspended", brand.isSuspended());
            row.put("suspendedAt", brand.getSuspendedAt());
            brands.add(row);
        }
        return pageResult("brands", brands, safePage, safeSize, result);
    }

    /** 某品牌底下的分店（含停用狀態），依 id 由小到大；品牌不存在回 404。 */
    @Transactional(readOnly = true)
    public Map<String, Object> listStoresOfBrand(Long brandId, int page, int size) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new CustomException("404", "找不到此品牌"));
        int safePage = clampPage(page);
        int safeSize = clampSize(size);
        Page<Store> result = storeRepository.findPageByBrandId(brandId,
                PageRequest.of(safePage, safeSize, Sort.by("id").ascending()));

        List<Map<String, Object>> stores = new ArrayList<>();
        for (Store store : result.getContent()) {
            Map<String, Object> row = new HashMap<>();
            row.put("storeId", store.getId());
            row.put("storeName", store.getStoreName());
            row.put("status", store.getStatus());
            row.put("suspended", store.isSuspended());
            row.put("suspendedAt", store.getSuspendedAt());
            stores.add(row);
        }
        Map<String, Object> data = pageResult("stores", stores, safePage, safeSize, result);
        data.put("brandId", brand.getId());
        data.put("brandName", brand.getName());
        data.put("brandSuspended", brand.isSuspended());
        return data;
    }

    @Transactional
    public Map<String, Object> approveBrand(Long adminId, Long brandId) {
        return decide(adminId, brandId, BrandApproval.APPROVED);
    }

    @Transactional
    public Map<String, Object> rejectBrand(Long adminId, Long brandId) {
        return decide(adminId, brandId, BrandApproval.REJECTED);
    }

    private Map<String, Object> decide(Long adminId, Long brandId, String target) {
        int updated = brandRepository.transitionApprovalStatus(
                brandId, BrandApproval.PENDING, target, LocalDateTime.now());
        if (updated == 0) {
            if (!brandRepository.existsById(brandId)) {
                throw new CustomException("404", "找不到此品牌");
            }
            throw new CustomException("409", "只能處理待審核的品牌");
        }
        // 只記錄 id 與結果；品牌名稱與帳號不寫進記錄檔
        log.info("品牌審核 brandId={} result={} adminId={}", brandId, target, adminId);
        Map<String, Object> data = new HashMap<>();
        data.put("brandId", brandId);
        data.put("approvalStatus", target);
        return data;
    }

    /**
     * 停用已核准的品牌（stage 6）。在同一交易內完成三件事：
     * 停用品牌並遞增其版本、遞增底下所有分店的版本（兩者的既有權杖立即失效），
     * 並在交易提交後切斷這些帳號的 WebSocket 連線。分店 id 在停用前取得，供事件使用。
     *
     * <p>未核准的品牌回 409（只能停用已核准的品牌）；已停用回 409；不存在回 404。
     */
    @Transactional
    public Map<String, Object> suspendBrand(Long adminId, Long brandId) {
        List<Long> storeIds = storeRepository.findIdsByBrandId(brandId);
        int updated = brandRepository.suspendBrand(brandId, BrandApproval.APPROVED, LocalDateTime.now());
        if (updated == 0) {
            Brand brand = brandRepository.findById(brandId)
                    .orElseThrow(() -> new CustomException("404", "找不到此品牌"));
            if (!BrandApproval.isApproved(brand.getApprovalStatus())) {
                throw new CustomException("409", "只能停用已核准的品牌");
            }
            throw new CustomException("409", "品牌已停用");
        }
        storeRepository.bumpTokenVersionsOfBrand(brandId);

        List<AccountRef> accounts = new ArrayList<>();
        accounts.add(new AccountRef(BRAND_ROLE, brandId));
        for (Long storeId : storeIds) {
            accounts.add(new AccountRef(STORE_ROLE, storeId));
        }
        eventPublisher.publishEvent(new AccountsSuspendedEvent(accounts));

        // 只記錄 id 與數量；品牌名稱不寫進記錄檔
        log.info("品牌停用 brandId={} stores={} adminId={}", brandId, storeIds.size(), adminId);
        Map<String, Object> data = new HashMap<>();
        data.put("brandId", brandId);
        data.put("suspended", true);
        data.put("affectedStores", storeIds.size());
        return data;
    }

    /** 恢復被停用的品牌。不遞增版本：停用時舊權杖已失效，恢復後須重新登入。未停用回 409。 */
    @Transactional
    public Map<String, Object> reinstateBrand(Long adminId, Long brandId) {
        int updated = brandRepository.reinstateBrand(brandId);
        if (updated == 0) {
            if (!brandRepository.existsById(brandId)) {
                throw new CustomException("404", "找不到此品牌");
            }
            throw new CustomException("409", "品牌未停用，無須恢復");
        }
        log.info("品牌恢復 brandId={} adminId={}", brandId, adminId);
        Map<String, Object> data = new HashMap<>();
        data.put("brandId", brandId);
        data.put("suspended", false);
        return data;
    }

    /**
     * 停用單一分店（stage 6）：遞增其版本使既有權杖失效，交易提交後切斷其 WebSocket 連線。
     * 分店不存在回 404；已停用回 409。
     */
    @Transactional
    public Map<String, Object> suspendStore(Long adminId, Long storeId) {
        int updated = storeRepository.suspendStore(storeId, LocalDateTime.now());
        if (updated == 0) {
            if (!storeRepository.existsById(storeId)) {
                throw new CustomException("404", "找不到此分店");
            }
            throw new CustomException("409", "分店已停用");
        }
        eventPublisher.publishEvent(new AccountsSuspendedEvent(List.of(new AccountRef(STORE_ROLE, storeId))));
        log.info("分店停用 storeId={} adminId={}", storeId, adminId);
        Map<String, Object> data = new HashMap<>();
        data.put("storeId", storeId);
        data.put("suspended", true);
        return data;
    }

    /** 恢復被停用的分店。不遞增版本，理由同 {@link #reinstateBrand}。未停用回 409。 */
    @Transactional
    public Map<String, Object> reinstateStore(Long adminId, Long storeId) {
        int updated = storeRepository.reinstateStore(storeId);
        if (updated == 0) {
            if (!storeRepository.existsById(storeId)) {
                throw new CustomException("404", "找不到此分店");
            }
            throw new CustomException("409", "分店未停用，無須恢復");
        }
        log.info("分店恢復 storeId={} adminId={}", storeId, adminId);
        Map<String, Object> data = new HashMap<>();
        data.put("storeId", storeId);
        data.put("suspended", false);
        return data;
    }

    private static int clampPage(int page) {
        return Math.max(0, page);
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }

    private static Map<String, Object> pageResult(String listKey, List<Map<String, Object>> rows,
                                                  int page, int size, Page<?> result) {
        Map<String, Object> data = new HashMap<>();
        data.put(listKey, rows);
        data.put("page", page);
        data.put("size", size);
        data.put("total", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());
        data.put("hasNext", result.hasNext());
        return data;
    }
}
