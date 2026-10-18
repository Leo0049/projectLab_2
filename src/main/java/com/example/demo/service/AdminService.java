package com.example.demo.service;

import com.example.demo.common.BrandApproval;
import com.example.demo.common.JwtUtils;
import com.example.demo.entity.AdminAccount;
import com.example.demo.entity.Brand;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.AdminAccountRepository;
import com.example.demo.repository.BrandRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 管理員後台的業務邏輯（N-28）：管理員登入與品牌審核。
 *
 * <p>審核採條件式更新（{@link BrandRepository#transitionApprovalStatus}），同一筆品牌只會被成功處理一次。
 * 管理員帳號只能由 {@link com.example.demo.common.AdminBootstrap} 建立，沒有公開的註冊途徑。
 */
@Slf4j
@Service
public class AdminService {

    /** 待審清單每頁上限（列表端點一律分頁，見 CLAUDE.md） */
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 50;

    @Autowired
    private AdminAccountRepository adminAccountRepository;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtUtils jwtUtils;

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
}
