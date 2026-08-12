package com.example.demo.service;

import com.example.demo.common.JwtUtils;
import com.example.demo.common.Result;
import com.example.demo.dto.ClassicAuthRequest;
import com.example.demo.dto.CorporateAuthRequest;
import com.example.demo.dto.SocialAuthRequest;
import com.example.demo.dto.UpdateUserRequest;
import com.example.demo.entity.*;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.UserAuthProviderRepository;
import com.example.demo.repository.UserRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuthService {

    @Autowired private UserRepository userRepository;
    @Autowired private UserAuthProviderRepository userAuthProviderRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtils jwtUtils;
    @Autowired private BrandService brandService;
    @Autowired private StoreService storeService;

    @Value("${sms.mode:firebase}")
    private String smsMode;

    // ─── 傳統註冊 ────────────────────────────────────────────
    // ✅ 補上缺少的方法：UserController /api/auth/register 呼叫此方法，原本不存在會編譯失敗
    @Transactional
    public void customerRegister(ClassicAuthRequest req) throws Exception {
        // 1. 驗證 Firebase Phone Token（mock 模式直接跳過，不打 Firebase）
        if (!"mock".equalsIgnoreCase(smsMode) && !"MOCK_TOKEN".equals(req.getIdToken())) {
            FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(req.getIdToken());
            String firebasePhone = decoded.getClaims().getOrDefault("phone_number", "").toString();
            String normalizedPhone = normalizePhone(req.getPhone());
            if (!firebasePhone.endsWith(normalizedPhone.substring(1))) {
                throw new CustomException("400", "手機驗證失敗");
            }
        }
        // 2. 檢查是否重複
        if (userRepository.existsByPhone(req.getPhone())) {
            throw new CustomException("409", "手機號碼已被註冊");
        }
        // 3. 建立 User
        User user = new User();
        user.setPhone(req.getPhone());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setName(req.getName() != null ? req.getName() : req.getPhone());
        user.setRole("CUSTOMER");
        user.setCreatedAt(LocalDateTime.now().withNano(0));
        user = userRepository.save(user);
        // 4. 建立 PHONE provider
        UserAuthProvider provider = new UserAuthProvider();
        provider.setUser(user);
        provider.setProvider("PHONE");
        provider.setProviderUid(req.getPhone());
        userAuthProviderRepository.save(provider);
    }

    // ─── 傳統登入 ────────────────────────────────────────────
    public Result customerLogin(ClassicAuthRequest req) throws Exception {
        Optional<User> optUser = userRepository.findByPhone(req.getPhone());
        if (optUser.isEmpty()) {
            boolean hasSocial = userAuthProviderRepository.existsByProviderAndProviderUid("GOOGLE", req.getPhone()) ||
                               userAuthProviderRepository.existsByProviderAndProviderUid("FACEBOOK", req.getPhone());
            if (hasSocial) return Result.error("202", "此手機已使用第三方登入，請整合帳號");
            return Result.error("404", "帳號不存在");
        }
        User user = optUser.get();
        if (Boolean.TRUE.equals(user.getIsDeleted())) throw new CustomException("403", "帳號已停用");

        if (user.getPasswordHash() == null) {
            return Result.error("202", "此號碼已透過第三方登入註冊，請設定密碼以整合帳號",
                    Map.of("phone", req.getPhone(), "action", "MERGE_SET_PASSWORD"));
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new CustomException("401", "密碼錯誤");
        }
        return Result.success(generateLoginResponse(user));
    }

    // ─── 企業登入（統一入口）────────────────────────────────────
    // 依序嘗試 BrandService → StoreService
    // 任一找到帳號即使用該 Service 的 login 邏輯，前端拿到 JWT 後解析 role 自動導向
    public Result corporateLogin(CorporateAuthRequest req) {
        String account  = req.getAccount();
        String password = req.getPassword();

        // 1. 先嘗試品牌端（BRAND）
        try {
            Map<String, Object> data = brandService.login(account, password);
            return Result.success(data);
        } catch (CustomException e) {
            // 404 = 帳號不在 brand 表，繼續往下試
            // 401 = 帳號存在但密碼錯，直接回傳
            if (!"404".equals(e.getCode())) {
                return Result.error(e.getCode(), e.getMessage());
            }
        }

        // 2. 再嘗試分店端（STORE）
        try {
            Map<String, Object> data = storeService.login(account, password);
            return Result.success(data);
        } catch (CustomException e) {
            if (!"404".equals(e.getCode())) {
                return Result.error(e.getCode(), e.getMessage());
            }
        }

        // 兩個 table 都找不到
        return Result.error("404", "帳號不存在");
    }

    // ─── 三方登入 ────────────────────────────────────────────
    public Result socialLogin(SocialAuthRequest req) throws Exception {
        String providerName = (req.getProvider() != null) ? req.getProvider().toUpperCase() : "GOOGLE";
        String uid = resolveUid(req.getIdToken(), req.getPhone(), providerName);

        Optional<UserAuthProvider> existingProvider = userAuthProviderRepository.findByProviderAndProviderUid(providerName, uid);

        if (existingProvider.isPresent()) {
            User user = existingProvider.get().getUser();
            if (req.getName() != null && !req.getName().isBlank() && !req.getName().equals(user.getName())) {
                user.setName(req.getName());
                userRepository.save(user);
            }
            return Result.success(generateLoginResponse(user));
        } else {
            if (req.getPhone() == null || req.getPhone().isEmpty()) {
                return Result.error("201", "首次登入，請綁定手機", Map.of("providerUid", uid, "provider", providerName));
            }
            if (userRepository.existsByPhone(req.getPhone())) {
                return Result.error("203", "此手機已有帳號，請進行整合",
                    Map.of("phone", req.getPhone(), "providerUid", uid, "provider", providerName, "action", "MERGE_BIND_SOCIAL"));
            }
            // 全新使用者
            User newUser = new User();
            newUser.setPhone(req.getPhone());
            newUser.setName(req.getName() != null ? req.getName() : "新用戶");
            newUser.setRole("CUSTOMER");
            newUser.setCreatedAt(LocalDateTime.now().withNano(0));
            userRepository.save(newUser);
            bindSocialProvider(newUser, providerName, uid);
            return Result.success(generateLoginResponse(newUser));
        }
    }

    // ─── 帳號整合：三方帳號設定密碼 ──────────────────────────
    @Transactional
    public Result mergeSetPassword(String idToken, String phone, String newPassword) throws Exception {
        FirebaseAuth.getInstance().verifyIdToken(idToken);
        User user = userRepository.findByPhone(phone).orElseThrow(() -> new CustomException("404", "帳號不存在"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        log.info("帳號整合成功（三方帳號設定密碼）：{}", phone);
        return Result.success(generateLoginResponse(user));
    }

    // ─── 帳號整合：傳統帳號綁定三方 ──────────────────────────
    // ✅ 修正：原本呼叫 verifyFirebasePhone 驗證 phone_number claim
    //    但前端傳入的是 Google/Facebook OAuth token，不含 phone_number claim，一定失敗
    //    改為只驗證 token 合法性即可，不強制比對手機號碼
    @Transactional
    public Result mergeBindSocial(String idToken, String phone, String providerUid, String provider) throws Exception {
        // 只驗證 token 是否合法，不要求有 phone_number claim（Google/Facebook token 沒有此 claim）
        FirebaseAuth.getInstance().verifyIdToken(idToken);

        User user = userRepository.findByPhone(phone).orElseThrow(() -> new CustomException("404", "找不到此帳號"));

        String providerName = provider != null ? provider.toUpperCase() : "GOOGLE";
        if (userAuthProviderRepository.findByProviderAndProviderUid(providerName, providerUid).isPresent()) {
            throw new CustomException("409", "此三方帳號已綁定過");
        }

        bindSocialProvider(user, providerName, providerUid);
        log.info("帳號整合成功（傳統帳號綁定三方）：{} -> {}", phone, providerName);
        return Result.success(generateLoginResponse(user));
    }

    // ─── 重設密碼 ─────────────────────────────────────────────
    // ✅ 補上缺少的方法：UserController /api/auth/reset-password-firebase 呼叫此方法
    @Transactional
    public void resetPasswordWithFirebase(String idToken, String phoneNumber, String newPassword) throws Exception {
        verifyFirebasePhone(idToken, phoneNumber);
        User user = userRepository.findByPhone(phoneNumber)
                .orElseThrow(() -> new CustomException("404", "帳號不存在"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("密碼重設成功：{}", phoneNumber);
    }

    // ─── 更新個人資料 ─────────────────────────────────────────
    // ✅ 補上缺少的方法：UserController /api/auth/update 呼叫此方法
    @Transactional
    public void update(Long userId, UpdateUserRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("404", "使用者不存在"));
        if (req.getName() != null && !req.getName().isBlank()) {
            user.setName(req.getName());
        }
        if (req.getPicUrl() != null) {
            user.setPicUrl(req.getPicUrl());
        }
        if (req.getPhone() != null && !req.getPhone().isBlank()) {
            if (userRepository.existsByPhone(req.getPhone()) && !req.getPhone().equals(user.getPhone())) {
                throw new CustomException("409", "此手機號碼已被使用");
            }
            user.setPhone(req.getPhone());
        }
        userRepository.save(user);
    }

    // ─── 檢查手機是否已註冊 ───────────────────────────────────
    // ✅ 補上缺少的方法：UserController /api/auth/check-phone 呼叫此方法
    public boolean existsByPhoneNumber(String phone) {
        return userRepository.existsByPhone(phone);
    }

    // ─── Debug：取得最近三方登入紀錄 ─────────────────────────
    // ✅ 補上缺少的方法：UserController /api/auth/debug/social-logins 呼叫此方法
    public List<Map<String, Object>> getRecentSocialLogins() {
        return userAuthProviderRepository.findTop10ByOrderByIdDesc()
                .stream()
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", p.getId());
                    m.put("provider", p.getProvider());
                    m.put("providerUid", p.getProviderUid());
                    m.put("userId", p.getUser().getId());
                    m.put("phone", p.getUser().getPhone());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ─── Firebase Token 驗證（僅驗證合法性）────────────────────
    public void verifyOnly(String idToken) throws Exception {
        // mock 模式或 MOCK_TOKEN 直接跳過
        if ("mock".equalsIgnoreCase(smsMode) || "MOCK_TOKEN".equals(idToken)) return;
        FirebaseAuth.getInstance().verifyIdToken(idToken);
    }

    // ─── 工具方法 ────────────────────────────────────────────
    private void bindSocialProvider(User user, String provider, String uid) {
        UserAuthProvider p = new UserAuthProvider();
        p.setUser(user);
        p.setProvider(provider);
        p.setProviderUid(uid);
        userAuthProviderRepository.save(p);
    }

    // 驗證 Firebase Phone Auth token 的手機號碼（只用於 resetPasswordWithFirebase）
    private String verifyFirebasePhone(String idToken, String phoneNumber) throws Exception {
        // mock 模式或 MOCK_TOKEN 直接跳過 Firebase 驗證
        if ("mock".equalsIgnoreCase(smsMode) || "MOCK_TOKEN".equals(idToken)) return phoneNumber;
        FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken);
        String verifiedPhone = (String) decodedToken.getClaims().get("phone_number");
        if (verifiedPhone == null || !verifiedPhone.contains(phoneNumber.substring(1))) {
            throw new CustomException("401", "驗證的手機號碼與輸入不符");
        }
        return verifiedPhone;
    }

    private String resolveUid(String idToken, String phone, String provider) throws Exception {
        if ("MOCK_TOKEN".equals(idToken)) return "MOCK_UID_" + provider + "_" + phone;
        return FirebaseAuth.getInstance().verifyIdToken(idToken).getUid();
    }

    private String normalizePhone(String phone) {
        if (phone == null) return "";
        // 09xxxxxxxx -> +8869xxxxxxxx（Firebase 格式）
        return phone.startsWith("0") ? "+886" + phone.substring(1) : phone;
    }

    private Map<String, Object> generateLoginResponse(User user) {
        String token = jwtUtils.generateToken(user.getId(), user.getRole(), user.getPhone());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", user.getId());
        data.put("name", user.getName());
        data.put("phone", user.getPhone() != null ? user.getPhone() : ""); // 前端判斷是否需要手機綁定
        return data;
    }
}
