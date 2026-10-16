package com.example.demo.service;

import com.example.demo.common.JwtUtils;
import com.example.demo.common.PhoneMask;
import com.example.demo.common.SafeImageUrl;
import com.example.demo.common.SafeText;
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
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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
    /** 手機驗證權杖只能成功使用一次（見 FirebaseTokenReplayGuard） */
    @Autowired private FirebaseTokenReplayGuard firebaseTokenReplayGuard;

    @Value("${sms.mode:firebase}")
    private String smsMode;

    /** ⚠️ prod profile 檢查需要，比照 JwtUtils（required=false 讓純單元測試可只注入需要的欄位）*/
    @Autowired(required = false)
    private Environment environment;

    /** 正式環境唯一允許的手機驗證模式 */
    static final String PROD_SMS_MODE = "firebase";

    /** 本機模擬用的固定 token；務必確認它只在非 prod 且 sms.mode=mock 時有意義 */
    static final String MOCK_TOKEN = "MOCK_TOKEN";

    /**
     * 啟動時就檢查「正式環境不得使用 mock 手機驗證」。
     *
     * <p>⚠️ 為什麼要 fail-fast：{@code sms.mode} 若被誤設為 mock，而 mock 模式下
     * {@link #MOCK_TOKEN} 會被視為已完成手機驗證——公開的
     * {@code POST /api/auth/reset-password-firebase} 因此可以對任何手機號碼重設密碼，
     * 等同任意顧客帳號接管。專案裡另外兩個同等敏感的開關
     * （{@code jwt.secret}、模擬儲值）都有 prod 防呆，只有這個漏掉。
     */
    @PostConstruct
    void assertSmsModeForProfile() {
        assertSmsModeForProfile(isProductionProfile(), smsMode);
    }

    /** package-private static 以利單元測試（同 {@code JwtUtils.assertProductionSecret} 的做法） */
    static void assertSmsModeForProfile(boolean productionProfile, String mode) {
        if (!productionProfile) {
            return;
        }
        if (mode == null || !PROD_SMS_MODE.equalsIgnoreCase(mode.trim())) {
            throw new IllegalStateException(
                    "偵測到 prod profile 使用 sms.mode=" + mode + "。正式環境必須為 "
                            + PROD_SMS_MODE + "，否則任何人只要送 MOCK_TOKEN 就能通過手機驗證"
                            + "並重設他人密碼（帳號接管）。請設定環境變數 SMS_MODE=firebase。");
        }
    }

    /** 是否為正式環境 profile */
    private boolean isProductionProfile() {
        return environment != null
                && Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }

    // ─── 傳統註冊 ────────────────────────────────────────────
    // ✅ 補上缺少的方法：UserController /api/auth/register 呼叫此方法，原本不存在會編譯失敗
    @Transactional
    public void customerRegister(ClassicAuthRequest req) throws Exception {
        verifyFirebasePhone(req.getIdToken(), req.getPhone());
        // 2. 檢查是否重複
        if (userRepository.existsByPhone(req.getPhone())) {
            throw new CustomException("409", "手機號碼已被註冊");
        }
        // 3. 建立 User
        User user = new User();
        user.setPhone(req.getPhone());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setName(req.getName() != null ? SafeText.normalizeDisplayName(req.getName()) : req.getPhone());
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
        String providerName = normalizeProvider(req.getProvider());
        String uid = resolveUid(req.getIdToken(), req.getPhone(), providerName);

        Optional<UserAuthProvider> existingProvider = userAuthProviderRepository.findByProviderAndProviderUid(providerName, uid);

        if (existingProvider.isPresent()) {
            User user = existingProvider.get().getUser();
            ensureActiveUser(user);
            if (req.getName() != null && !req.getName().isBlank() && !req.getName().equals(user.getName())) {
                user.setName(SafeText.sanitizeDisplayName(req.getName(), user.getName()));
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
            // ⚠️ 這裡不建立帳號：沒有任何手機驗證，舊版會直接用請求中的 phone 寫入新使用者。
                //    攻擊者可用自己的 Google 帳號綁定「尚未註冊的他人手機」，社群身分會留在對方的帳號上（帳號接管）。
                //    全新手機一律改走 socialSignupWithVerifiedPhone（先驗 OTP 再建立）。
                return Result.error("201", "首次登入，請綁定手機", Map.of("providerUid", uid, "provider", providerName));
        }
    }

    // ─── 帳號整合：三方帳號設定密碼 ──────────────────────────
    @Transactional
    public Result mergeSetPassword(String idToken, String phone, String newPassword) throws Exception {
        verifyFirebasePhone(idToken, phone);
        User user = userRepository.findByPhone(phone).orElseThrow(() -> new CustomException("404", "帳號不存在"));
        ensureActiveUser(user);

        user.changePasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        log.info("帳號整合成功（三方帳號設定密碼）：userId={} phone={}", user.getId(), PhoneMask.mask(phone));
        return Result.success(generateLoginResponse(user));
    }

    // ─── 帳號整合：由已登入的傳統帳號綁定三方 ─────────────────
    @Transactional
    public Result mergeBindSocial(Long userId, String idToken, String providerUid, String provider) throws Exception {
        User user = userRepository.findById(userId).orElseThrow(() -> new CustomException("404", "找不到此帳號"));
        ensureActiveUser(user);
        bindVerifiedSocialProvider(user, idToken, providerUid, provider);
        return Result.success(generateLoginResponse(user));
    }

    /**
     * 第三方登入的「全新手機」註冊：先驗證手機 OTP，通過後才建立帳號並綁定社群身分。
     * ⚠️ 這是唯一允許為第三方登入建立新帳號並寫入手機的路徑（socialLogin 已不再這麼做）。
     */
    @Transactional
    public Result socialSignupWithVerifiedPhone(String phoneIdToken, String phone, String socialIdToken,
            String providerUid, String provider, String name) throws Exception {
        verifyFirebasePhone(phoneIdToken, phone);
        String providerName = normalizeProvider(provider);
        FirebaseToken decoded = verifyFirebaseSocialToken(socialIdToken, providerName);
        String uid;
        if (decoded != null) {
            uid = decoded.getUid();
            if (providerUid != null && !providerUid.equals(uid)) {
                throw new CustomException("403", "第三方身分驗證失敗");
            }
        } else {
            // 僅 mock 模式（非 prod）會走到這裡，與 resolveUid 的做法一致
            uid = mockProviderUid(providerName, phone);
        }
        // 第三方身分已綁定既有帳號（例如舊帳號沒有手機，login.js 會導到這裡）：手機已通過 OTP 驗證，
        // 補在該帳號上並登入，不另建帳號。與 socialLogin 的 200 分支一致，避免同一社群身分綁到兩個帳號。
        Optional<UserAuthProvider> linked = userAuthProviderRepository.findByProviderAndProviderUid(providerName, uid);
        User linkedUser = linked.map(UserAuthProvider::getUser).orElse(null);
        boolean linkedHasPhone = linkedUser != null && linkedUser.getPhone() != null && !linkedUser.getPhone().isBlank();
        // 決策規則在 SocialSignupRules（純函式，有單元測試）；這裡只依結果執行讀寫
        SocialSignupRules.Action action = SocialSignupRules.decide(
                linked.isPresent(), linkedHasPhone, userRepository.existsByPhone(phone));
        if (action == SocialSignupRules.Action.LOGIN_LINKED) {
            ensureActiveUser(linkedUser);
            return Result.success(generateLoginResponse(linkedUser));
        }
        if (action == SocialSignupRules.Action.BIND_PHONE_AND_LOGIN) {
            ensureActiveUser(linkedUser);
            linkedUser.setPhone(phone);
            userRepository.save(linkedUser);
            return Result.success(generateLoginResponse(linkedUser));
        }
        if (action == SocialSignupRules.Action.MERGE_CONFLICT) {
            return Result.error("203", "此手機已有帳號，請進行整合",
                    Map.of("phone", phone, "provider", providerName, "action", "MERGE_BIND_SOCIAL"));
        }
        // CREATE_NEW：社群身分與手機都未被使用，才建立新帳號（見下方）
        User newUser = new User();
        newUser.setPhone(phone);
        newUser.setName(SafeText.sanitizeDisplayName(name, "新用戶"));
        newUser.setRole("CUSTOMER");
        newUser.setCreatedAt(LocalDateTime.now().withNano(0));
        userRepository.save(newUser);
        bindSocialProvider(newUser, providerName, uid);
        return Result.success(generateLoginResponse(newUser));
    }

    /** 以已驗證的手機 OTP 與社群 Firebase token 完成沒有密碼帳號的整合。 */
    @Transactional
    public Result mergeBindSocialByPhone(String phoneIdToken, String phone, String socialIdToken,
            String providerUid, String provider) throws Exception {
        verifyFirebasePhone(phoneIdToken, phone);
        User user = userRepository.findByPhone(phone).orElseThrow(() -> new CustomException("404", "找不到此帳號"));
        ensureActiveUser(user);
        bindVerifiedSocialProvider(user, socialIdToken, providerUid, provider);
        return Result.success(generateLoginResponse(user));
    }

    /**
     * 綁定已驗證的社群身分。刻意不在這裡簽發權杖：由呼叫端在帳號狀態定案後才簽發
     * （例如改密碼之後，N13 會遞增版本），否則同一流程會先簽出一張隨即失效的權杖。
     */
    private void bindVerifiedSocialProvider(User user, String idToken, String providerUid, String provider)
            throws Exception {
        String providerName = normalizeProvider(provider);
        FirebaseToken decoded = verifyFirebaseSocialToken(idToken, providerName);
        String tokenUid = decoded == null ? mockProviderUid(providerName, user.getPhone()) : decoded.getUid();
        if (providerUid == null || !providerUid.equals(tokenUid)) {
            throw new CustomException("401", "第三方帳號驗證失敗");
        }
        if (userAuthProviderRepository.findByProviderAndProviderUid(providerName, providerUid).isPresent()) {
            throw new CustomException("409", "此三方帳號已綁定過");
        }
        bindSocialProvider(user, providerName, providerUid);
        log.info("帳號整合成功：userId={} provider={}", user.getId(), providerName);
    }

    /** Atomically set a password and attach the social identity after proving phone ownership. */
    @Transactional
    public Result mergeSetPasswordAndBindSocial(String phoneIdToken, String phone, String newPassword,
            String socialIdToken, String providerUid, String provider) throws Exception {
        verifyFirebasePhone(phoneIdToken, phone);
        User user = userRepository.findByPhone(phone).orElseThrow(() -> new CustomException("404", "帳號不存在"));
        ensureActiveUser(user);
        bindVerifiedSocialProvider(user, socialIdToken, providerUid, provider);
        // MUTATION M4: token issued before the password change bumps tokenVersion (verification only)
        var resp = Result.success(generateLoginResponse(user));
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return resp;
    }

    // ─── 重設密碼 ─────────────────────────────────────────────
    // ✅ 補上缺少的方法：UserController /api/auth/reset-password-firebase 呼叫此方法
    @Transactional
    public void resetPasswordWithFirebase(String idToken, String phoneNumber, String newPassword) throws Exception {
        verifyFirebasePhone(idToken, phoneNumber);
        User user = userRepository.findByPhone(phoneNumber)
                .orElseThrow(() -> new CustomException("404", "帳號不存在"));
        ensureActiveUser(user);
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("密碼重設成功：userId={} phone={}", user.getId(), PhoneMask.mask(phoneNumber));
    }

    // ─── 更新個人資料 ─────────────────────────────────────────
    // ✅ 補上缺少的方法：UserController /api/auth/update 呼叫此方法
    @Transactional
    public void update(Long userId, UpdateUserRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("404", "使用者不存在"));
        if (req.getName() != null && !req.getName().isBlank()) {
            user.setName(SafeText.normalizeDisplayName(req.getName()));
        }
        if (req.getPicUrl() != null) {
            user.setPicUrl(SafeImageUrl.normalize(req.getPicUrl()));
        }
        if (req.getPhone() != null && !req.getPhone().isBlank() && !req.getPhone().equals(user.getPhone())) {
            // ⚠️ C-5 修復：手機同時是登入帳號與重設密碼的依據，舊版在沒有 OTP 的情況下就能改掉，
            //    等於可以先佔住尚未註冊的號碼。前端沒有呼叫此端點改手機，故直接拒絕；
            //    換號請走 Firebase 手機驗證流程。
            throw new CustomException("400", "更換手機號碼需要手機驗證，請改用驗證流程");
        }
        userRepository.save(user);
    }

    // ─── 檢查手機是否已註冊 ───────────────────────────────────
    // ✅ 補上缺少的方法：UserController /api/auth/check-phone 呼叫此方法
    public boolean existsByPhoneNumber(String phone) {
        return userRepository.existsByPhone(phone);
    }

    // getRecentSocialLogins() 已隨 /api/auth/debug/social-logins 一併移除：
    // 該端點未經認證即可取得他人手機號碼與 providerUid。

    // ─── Firebase Token 驗證（僅驗證合法性）────────────────────
    public void verifyOnly(String idToken) throws Exception {
        verifyFirebaseToken(idToken);
    }

    // ─── 工具方法 ────────────────────────────────────────────
    private void bindSocialProvider(User user, String provider, String uid) {
        UserAuthProvider p = new UserAuthProvider();
        p.setUser(user);
        p.setProvider(provider);
        p.setProviderUid(uid);
        userAuthProviderRepository.save(p);
    }

    // 驗證 Firebase Phone Auth token 的手機號碼；本機 mock token 僅在 sms.mode=mock 可用。
    // ⚠️ 真實的 token 只能成功使用一次：號碼比對通過後才記錄，號碼不符的嘗試不會燒掉 token。
    //    MOCK_TOKEN 不記錄（固定字串，本機模擬用）。verifyOnly 不呼叫此方法，故預先檢查不消耗。
    private String verifyFirebasePhone(String idToken, String phoneNumber) throws Exception {
        FirebaseToken decodedToken = verifyFirebaseToken(idToken);
        if (decodedToken == null) return phoneNumber;
        String verifiedPhone = Objects.toString(decodedToken.getClaims().get("phone_number"), null);
        if (verifiedPhone == null || !normalizePhone(verifiedPhone).equals(normalizePhone(phoneNumber))) {
            throw new CustomException("401", "驗證的手機號碼與輸入不符");
        }
        if (!firebaseTokenReplayGuard.claimOnce(idToken)) {
            throw new CustomException("401", "此手機驗證已使用，請重新取得驗證碼");
        }
        return verifiedPhone;
    }

    private String resolveUid(String idToken, String phone, String provider) throws Exception {
        FirebaseToken decoded = verifyFirebaseSocialToken(idToken, provider);
        return decoded == null ? mockProviderUid(provider, phone) : decoded.getUid();
    }

    private FirebaseToken verifyFirebaseSocialToken(String idToken, String provider) throws Exception {
        FirebaseToken decoded = verifyFirebaseToken(idToken);
        if (decoded == null) return null;

        Object firebaseClaim = decoded.getClaims().get("firebase");
        if (!(firebaseClaim instanceof Map<?, ?> firebaseInfo)) {
            throw new CustomException("401", "第三方帳號驗證失敗");
        }
        String signInProvider = Objects.toString(firebaseInfo.get("sign_in_provider"), "");
        String expectedProvider = "GOOGLE".equals(provider) ? "google.com" : "facebook.com";
        if (!expectedProvider.equals(signInProvider)) {
            throw new CustomException("401", "第三方登入方式與驗證帳號不符");
        }
        return decoded;
    }

    private FirebaseToken verifyFirebaseToken(String idToken) throws Exception {
        // ⚠️ 執行期第一層：只有「非 prod」且 sms.mode=mock 才接受本地 mock token。
        //    啟動檢查（assertSmsModeForProfile）是第二層；兩層都留，因為第一層擋的是
        //    「設定被改壞」的情況，第二層擋的是「部署忘了設定」。
        if (!isProductionProfile() && "mock".equalsIgnoreCase(smsMode) && MOCK_TOKEN.equals(idToken)) {
            return null;
        }
        if (idToken == null || idToken.isBlank() || MOCK_TOKEN.equals(idToken)) {
            throw new CustomException("401", "Firebase 驗證失敗");
        }
        return FirebaseAuth.getInstance().verifyIdToken(idToken);
    }

    private String normalizeProvider(String provider) {
        String value = provider == null || provider.isBlank() ? "GOOGLE" : provider.toUpperCase(Locale.ROOT);
        if (!List.of("GOOGLE", "FACEBOOK").contains(value)) {
            throw new CustomException("400", "不支援的第三方登入方式");
        }
        return value;
    }

    private String mockProviderUid(String provider, String phone) {
        return "MOCK_UID_" + provider + "_" + phone;
    }

    private void ensureActiveUser(User user) {
        if (user == null || Boolean.TRUE.equals(user.getIsDeleted())) {
            throw new CustomException("403", "帳號已停用");
        }
    }

    private String normalizePhone(String phone) {
        if (phone == null) return "";
        String normalized = phone.replaceAll("[\\s()-]", "");
        if (normalized.startsWith("+")) return normalized;
        if (normalized.startsWith("886")) return "+" + normalized;
        return normalized.startsWith("0") ? "+886" + normalized.substring(1) : normalized;
    }

    private Map<String, Object> generateLoginResponse(User user) {
        String token = jwtUtils.generateToken(user.getId(), user.getRole(), user.getPhone(), user.getTokenVersion());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", user.getId());
        data.put("name", user.getName());
        data.put("phone", user.getPhone() != null ? user.getPhone() : ""); // 前端判斷是否需要手機綁定
        return data;
    }
}
