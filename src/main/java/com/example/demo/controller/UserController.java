package com.example.demo.controller;

import com.example.demo.common.JwtUtils;
import com.example.demo.service.BrandService;
import com.example.demo.service.ProductService;
import com.example.demo.service.PublicService;
import com.example.demo.service.StoreService;
import com.example.demo.service.UserProfileService;
import java.math.BigDecimal;
import com.example.demo.common.Result;
import com.example.demo.dto.ClassicAuthRequest;
import com.example.demo.dto.CorporateAuthRequest;
import com.example.demo.dto.PlaceOrderRequest;
import com.example.demo.dto.SocialAuthRequest;
import com.example.demo.dto.UpdateUserRequest;
import com.example.demo.entity.TransactionRecord;
import com.example.demo.entity.User;
import com.example.demo.entity.UserAddress;
import com.example.demo.repository.UserAddressRepository;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.TransactionRecordRepository;
import com.example.demo.service.AuthService;
import com.example.demo.service.BrandService;
import com.example.demo.service.MenuService;
import com.example.demo.service.OrderService;
import com.example.demo.service.OrderRatingService;
import com.example.demo.service.StoreService;
import com.example.demo.service.TransactionRecordService;
import com.example.demo.service.UserCouponService;
import com.example.demo.service.UserProfileService;
import com.example.demo.service.UserService;
import com.example.demo.repository.UserAddressRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@lombok.extern.slf4j.Slf4j
@RestController
@Tag(name = "使用者 (CUSTOMER)", description = "使用者認證、菜單、訂單、優惠券")
public class UserController {

    @Autowired
    private AuthService authService;
    @Autowired
    private BrandService brandService;
    @Autowired
    private StoreService storeService;
    @Autowired
    private UserCouponService userCouponService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderRatingService orderRatingService;
    @Autowired
    private MenuService menuService;
    @Autowired
    private UserProfileService userProfileService;
    @Autowired
    private JwtUtils jwtUtils;

    @Autowired(required = false)
    private UserService userService;

    @Autowired(required = false)
    private TransactionRecordService transactionRecordService;

    @Autowired(required = false)
    private TransactionRecordRepository transactionRecordRepository;

    @Autowired(required = false)
    private UserAddressRepository userAddressRepository;

    // ==================== 認證 (公開) ====================

    @Operation(summary = "使用者註冊", description = "傳統帳號註冊。需先通過 Firebase 手機驗證取得 idToken。\n\nBody: { phone, password, name, idToken }")
    @PostMapping("/api/auth/register")
    public Result register(@Valid @RequestBody ClassicAuthRequest req) throws Exception {
        authService.customerRegister(req);
        return Result.success("註冊成功！歡迎來到平台");
    }

    @Operation(summary = "使用者登入", description = "傳統帳號密碼登入。\n\n**狀態碼：** 200=成功 / 202=三方帳號需整合 / 401=密碼錯誤 / 404=不存在\n\nBody: { phone, password }")
    @PostMapping("/api/auth/login")
    public Result login(@Valid @RequestBody ClassicAuthRequest req) throws Exception {
        return authService.customerLogin(req);
    }

    @Operation(summary = "第三方登入", description = "Firebase Google / Facebook 登入。\n\nBody: { idToken, phone, name, provider }\n- provider: GOOGLE / FACEBOOK（預設 GOOGLE）\n\n回傳 JWT Token。")
    @PostMapping("/api/auth/social-login")
    public Result socialLogin(@Valid @RequestBody SocialAuthRequest req) throws Exception {
        return authService.socialLogin(req);
    }

    @Operation(summary = "企業登入", description = "分店端 / 品牌端統一登入入口。系統自動辨識帳號身分。\n\nBody: { account, password }\n\n回傳 JWT Token，role = STORE | BRAND。")
    @PostMapping("/api/auth/corporate-login")
    public Result corporateLogin(@Valid @RequestBody CorporateAuthRequest req) {
        String account = req.getAccount();
        String password = req.getPassword();

        // 1. 先嘗試品牌端（BRAND）
        try {
            java.util.Map<String, Object> data = brandService.login(account, password);
            return Result.success(data);
        } catch (com.example.demo.exception.CustomException e) {
            // 帳號不存在才繼續試分店，密碼錯誤直接回錯
            if (!"404".equals(e.getCode())) {
                return Result.error(e.getCode(), e.getMessage());
            }
        }

        // 2. 再嘗試分店端（STORE）
        try {
            java.util.Map<String, Object> data = storeService.login(account, password);
            return Result.success(data);
        } catch (com.example.demo.exception.CustomException e) {
            if (!"404".equals(e.getCode())) {
                return Result.error(e.getCode(), e.getMessage());
            }
        }

        // 3. 兩邊都找不到
        return Result.error("404", "帳號不存在，請確認企業帳號是否正確");
    }

    @Operation(summary = "檢查手機是否已註冊", description = "Query Param: ?phone=0912345678\n\n回傳 true（已註冊）/ false（未註冊）")
    @GetMapping("/api/auth/check-phone")
    public Result checkPhone(@RequestParam String phone) {
        return Result.success(authService.existsByPhoneNumber(phone));
    }

    // ✅ 修正：加上 try-catch，Firebase 驗證失敗時回傳標準 Result 格式，不會讓前端 JSON.parse 炸掉
    @Operation(summary = "Firebase Token 預檢", description = "前端跳轉前的安全性檢查，確保 Token 合法且未過期。")
    @PostMapping("/api/auth/firebase-verify")
    public Result verifyFirebase(@RequestBody Map<String, String> body) {
        String idToken = body.get("idToken");
        if (idToken == null || idToken.isBlank()) {
            return Result.error("400", "Token 缺失");
        }
        try {
            authService.verifyOnly(idToken);
            return Result.success("Token 有效");
        } catch (Exception e) {
            return Result.error("401", "Token 無效或已過期：" + e.getMessage());
        }
    }

    // ⚠️ 已移除 GET /api/auth/debug/social-logins。
    //    它會列出最近的三方登入綁定紀錄（含手機號碼等個資），且因落在
    //    /api/auth/** 的 permitAll 範圍內，未登入即可存取。

    @Operation(summary = "重設密碼", description = "透過 Firebase 驗證手機身份後重設密碼。\n\nBody: { idToken, phone, newPassword }")
    @PostMapping("/api/auth/reset-password-firebase")
    public Result resetPassword(@RequestBody Map<String, String> payload) {
        try {
            String idToken = payload.get("idToken");
            String phoneNumber = payload.get("phone");
            String newPassword = payload.get("newPassword");
            // ✅ 修正：同時檢查 null 與空字串，避免空字串傳入 Firebase 造成 JWT parse 錯誤
            if (idToken == null || idToken.isBlank())
                return Result.error("400", "驗證 Token 缺失，請重新進行手機驗證");
            if (phoneNumber == null || phoneNumber.isBlank())
                return Result.error("400", "手機號碼不可為空");
            if (newPassword == null || newPassword.isBlank())
                return Result.error("400", "新密碼不可為空");
            authService.resetPasswordWithFirebase(idToken, phoneNumber, newPassword);
            return Result.success("密碼修改成功！請使用新密碼登入。");
        } catch (CustomException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("重設密碼失敗", e);
            return Result.error("500", "伺服器發生錯誤，請稍後再試");
        }
    }

    @Operation(summary = "帳號整合：設定密碼", description = "情境：傳統登入收到 202（此手機是三方帳號）。\n\n流程：簡訊驗證 → 呼叫此 API 設定密碼 → 回傳 JWT\n\nBody: { idToken, phone, newPassword }")
    @PostMapping("/api/auth/merge/set-password")
    public Result mergeSetPassword(@RequestBody Map<String, String> body) throws Exception {
        String idToken = body.get("idToken");
        String phone = body.get("phone");
        String newPassword = body.get("newPassword");
        if (idToken == null || phone == null || newPassword == null)
            return Result.error("400", "參數不完整");
        return authService.mergeSetPassword(idToken, phone, newPassword);
    }

    @Operation(summary = "帳號整合：綁定三方", description = "情境：三方登入收到 203（此手機已是傳統帳號）。\n\n流程：簡訊驗證 → 呼叫此 API 綁定三方 → 回傳 JWT\n\nBody: { idToken, phone, providerUid, provider }")
    @PostMapping("/api/auth/merge/bind-social")
    public Result mergeBindSocial(@RequestBody Map<String, String> body) throws Exception {
        String idToken = body.get("idToken");
        String phone = body.get("phone");
        String providerUid = body.get("providerUid");
        String provider = body.get("provider");
        if (idToken == null || phone == null || providerUid == null)
            return Result.error("400", "參數不完整");
        return authService.mergeBindSocial(idToken, phone, providerUid, provider);
    }

    @Operation(summary = "更新個人資料", description = "修改使用者暱稱 / 頭像 / 手機，欄位皆為選填。\n\nBody: { name?, picUrl?, phone? }")
    @PutMapping("/api/auth/update")
    public Result update(@RequestAttribute("currentUserId") Long userId,
            @RequestBody UpdateUserRequest req) {
        authService.update(userId, req);
        return Result.success();
    }

    // ==================== 菜單 (公開) ====================

    @Operation(summary = "取得分店菜單", description = "查詢指定分店完整菜單，包含分類、飲品、規格、配料、售完狀態。")
    @GetMapping("/api/menu/store/{storeId}")
    public Result getStoreMenu(@PathVariable Long storeId) {
        return Result.success(menuService.getStoreMenu(storeId));
    }

    // ==================== 訂單 ====================

    @Operation(summary = "建立訂單", description = "使用者下單。\n\nBody:\n```json\n{\n  \"storeId\": 1,\n  \"note\": \"門口有管理員，請報到。\",\n  \"items\": [{\n    \"productId\": 1,\n    \"sugarSnapshot\": \"微糖\",\n    \"iceSnapshot\": \"少冰\",\n    \"paymentType\": \"CREDIT\",\n    \"toppingNames\": [\"珍珠\", \"椰果\"]\n  }]\n}\n```")
    @PostMapping("/api/orders/place")
    public Result placeOrder(@RequestAttribute("currentUserId") Long userId,
            @Valid @RequestBody PlaceOrderRequest req) {
        return Result.success(orderService.placeOrder(userId, req));
    }

    @Operation(summary = "查詢訂單歷史", description = "取得目前登入使用者的所有歷史訂單列表。")
    @GetMapping("/api/orders/history")
    public Result getHistory(@RequestAttribute("currentUserId") Long userId) {
        return Result.success(orderService.getOrderHistory(userId));
    }

    @Operation(summary = "查詢訂單明細", description = "取得單筆訂單詳細資料，包含品項、配料、甜度、冰塊、金額、狀態。")
    @GetMapping("/api/orders/{orderId}")
    public Result getDetail(@RequestAttribute("currentUserId") Long userId,
            @PathVariable Long orderId) {
        return Result.success(orderService.getOrderDetail(userId, orderId));
    }

    // ==================== 優惠券 ====================

    @Operation(summary = "查詢我的優惠券", description = "取得目前登入使用者持有的所有優惠券，含折扣、分類、是否使用、是否過期。")
    @GetMapping("/api/coupons/my")
    public Result getMyCoupons(@RequestAttribute("currentUserId") Long userId) {
        return Result.success(userCouponService.getMyCoupons(userId));
    }

    @Operation(summary = "使用優惠券", description = "將指定優惠券標記為已使用。每張只能使用一次，過期後無法使用。")
    @PutMapping("/api/coupons/{userCouponId}/use")
    public Result useCoupon(@PathVariable Long userCouponId,
            @RequestAttribute("currentUserId") Long userId) {
        userCouponService.useCoupon(userCouponId, userId);
        return Result.success("優惠券使用成功");
    }

    // ==================== 會員個人中心 ====================

    @Operation(summary = "取得會員資料", description = "取得目前登入會員的個人資訊、餘額。")
    @GetMapping("/api/users/me")
    public Result getMe(@RequestAttribute("currentUserId") Long userId) {
        return Result.success(userProfileService.getMe(userId));
    }

    @Operation(summary = "修改會員資料", description = "更新暱稱、頭像。Body: { name?, picUrl? }")
    @PutMapping("/api/users/me")
    public Result updateMe(@RequestAttribute("currentUserId") Long userId,
            @RequestBody Map<String, Object> body) {
        userProfileService.updateMe(userId,
                (String) body.get("name"),
                (String) body.get("picUrl"));
        return Result.success("更新成功");
    }

    @Operation(summary = "刪除會員（邏輯刪除）", description = "停用帳號，資料保留但無法登入。")
    @DeleteMapping("/api/users/me")
    public Result deleteMe(@RequestAttribute("currentUserId") Long userId) {
        userProfileService.deleteMe(userId);
        return Result.success("帳號已停用");
    }

    // ==================== 錢包 ====================

    @Operation(summary = "錢包餘額")
    @GetMapping("/api/wallet")
    public Result getWallet(@RequestAttribute("currentUserId") Long userId) {
        return Result.success(userProfileService.getWallet(userId));
    }

    @Operation(summary = "交易紀錄", description = "Query: ?months=3&page=1&size=10")
    @GetMapping("/api/wallet/transactions")
    public Result getTransactions(@RequestAttribute("currentUserId") Long userId,
            @RequestParam(required = false) Integer months,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(userProfileService.getTransactions(userId, months, page, size));
    }

    // ✅ 修正：amount 為 null 時先檢查，避免 NullPointerException
    @Operation(summary = "儲值", description = "Body: { amount: 500 }")
    @PostMapping("/api/wallet/topup")
    public Result topUp(@RequestAttribute("currentUserId") Long userId,
            @RequestBody Map<String, Object> body) {
        Object amountObj = body.get("amount");
        if (amountObj == null)
            return Result.error("400", "amount 不可為空");
        try {
            BigDecimal amount = new BigDecimal(amountObj.toString());
            return Result.success(userProfileService.topUp(userId, amount));
        } catch (NumberFormatException e) {
            return Result.error("400", "amount 格式錯誤");
        }
    }

    // ==================== 訂單補充 ====================

    @Operation(summary = "訂單統計", description = "取得各狀態訂單數量，用於列表頁上方統計標籤。")
    @GetMapping("/api/orders/summary")
    public Result getOrderSummary(@RequestAttribute("currentUserId") Long userId) {
        return Result.success(orderService.getOrderSummary(userId));
    }

    @Operation(summary = "訂單狀態時間線", description = "取得訂單各階段時間紀錄。")
    @GetMapping("/api/orders/{orderId}/timeline")
    public Result getOrderTimeline(@RequestAttribute("currentUserId") Long userId,
            @PathVariable Long orderId) {
        return Result.success(orderService.getOrderTimeline(userId, orderId));
    }

    @Operation(summary = "取得我的訂單評分", description = "回傳目前登入者對這筆訂單的評分狀態與是否可評分。")
    @GetMapping("/api/orders/{orderId}/rating")
    public Result getOrderRating(@RequestAttribute("currentUserId") Long userId,
            @PathVariable Long orderId) {
        return Result.success(orderRatingService.getMyRating(userId, orderId));
    }

    @Operation(summary = "提交訂單評分", description = "Body: { rating: 1~5 }。個人訂單一筆評分，揪團訂單每個參與帳號各自一筆。")
    @PostMapping("/api/orders/{orderId}/rating")
    public Result submitOrderRating(@RequestAttribute("currentUserId") Long userId,
            @PathVariable Long orderId,
            @RequestBody Map<String, Object> body) {
        Integer rating = body.get("rating") == null ? null : Integer.parseInt(body.get("rating").toString());
        return Result.success(orderRatingService.upsertRating(userId, orderId, rating));
    }

    // ✅ 修正：改用 @RequestAttribute 取得 userId，避免 Authorization header 為 null 時 NPE
    @Operation(summary = "上傳頭像", description = "multipart/form-data，欄位名稱: avatar（圖片檔案）。")
    @PostMapping("/api/users/avatar")
    public Result uploadAvatar(
            @RequestAttribute("currentUserId") Long userId,
            @RequestParam("avatar") org.springframework.web.multipart.MultipartFile file) {
        try {
            return Result.success(userProfileService.uploadAvatar(userId, file));
        } catch (Exception e) {
            log.error("頭像上傳失敗", e);
            return Result.error("500", "圖片上傳失敗，請稍後再試");
        }
    }

    // ✅ 修正：改用 @RequestAttribute 取得 userId，避免 Authorization header 為 null 時 NPE
    @Operation(summary = "再次訂購", description = "根據歷史訂單建立一筆新的相同訂單（OPEN 狀態）。")
    @PostMapping("/api/orders/{orderId}/reorder")
    public Result reorder(
            @RequestAttribute("currentUserId") Long userId,
            @PathVariable Long orderId) {
        return Result.success(orderService.reorder(userId, orderId));
    }

    // ✅ 修正：改用 @RequestAttribute 取得 userId，避免 Authorization header 為 null 時 NPE
    @Operation(summary = "取消訂單", description = "只有 OPEN 狀態的訂單可以取消，已送出的訂單無法取消。")
    @PostMapping("/api/orders/{orderId}/cancel")
    public Result cancelOrder(
            @RequestAttribute("currentUserId") Long userId,
            @PathVariable Long orderId) {
        orderService.cancelOrder(userId, orderId);
        return Result.success("訂單已取消");
    }

    // ============================================================
    // methods (from 待處理/UserController — ResponseEntity-based)
    // 使用 /api/users 路徑前綴，以避免與上方 Result-based 端點衝突
    // ============================================================

    /**
     * 確認路徑上的 userId 就是 token 所代表的使用者本人。
     *
     * ⚠️ 這裡的 currentUserId 只能來自 JwtAuthenticationFilter 寫入的 request attribute。
     *    舊版是以 @RequestParam Long authUserId 接收、再寫成
     *    {@code if (authUserId != null && !authUserId.equals(userId))}，
     *    但那個值是「用戶端自己送的」，攻擊者只要不帶這個參數，整段檢查就被跳過，
     *    等於任何登入者都能讀寫他人的個資、地址與錢包。新增端點時請一律沿用本方法。
     */
    private void requireSelf(Long pathUserId, Long currentUserId) {
        if (currentUserId == null || !currentUserId.equals(pathUserId)) {
            throw new CustomException("403", "無權存取其他使用者的資料");
        }
    }

    @Operation(summary = "取得使用者資料", description = "依 userId 查詢使用者公開資料。")
    @GetMapping("/api/users/{userId}")
    public ResponseEntity<?> getUser(@PathVariable Long userId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        if (userService == null) {
            return ResponseEntity.status(500).body("UserService not available");
        }
        return userService.findById(userId)
                .map(u -> ResponseEntity.ok(new UserResponse(u.getId(), u.getName(), u.getRole(), u.getPicUrl(),
                        u.getPhone(), u.getBalance())))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "取得使用者常用地址列表", description = "回傳該使用者所有儲存的常用地址。")
    @GetMapping("/api/users/{userId}/address")
    public ResponseEntity<?> getUserAddress(@PathVariable Long userId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        if (userAddressRepository == null) {
            return ResponseEntity.ok(List.of());
        }
        List<UserAddress> addresses = userAddressRepository.findByUserId(userId);
        List<Map<String, Object>> result = addresses.stream().map(a -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", a.getId());
            m.put("label", a.getLabel());
            m.put("address", a.getAddressName());
            m.put("latitude", a.getLatitude());
            m.put("longitude", a.getLongitude());
            m.put("isDefault", a.getIsDefault());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "另存新地址", description = "為使用者新增一筆非預設的常用地址。")
    @PostMapping("/api/users/{userId}/addresses")
    public ResponseEntity<?> saveNewAddress(@PathVariable Long userId,
            @Valid @RequestBody SaveAddressRequest request,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        if (userAddressRepository == null || userService == null) {
            return ResponseEntity.status(503).body("Service unavailable");
        }
        User user = userService.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        String addressName = String.join("",
                request.getCity() != null ? request.getCity() : "",
                request.getDistrict() != null ? request.getDistrict() : "",
                request.getStreet() != null ? request.getStreet() : "").trim();
        if (addressName.isEmpty()) return ResponseEntity.badRequest().body("地址不得為空");

        UserAddress addr = new UserAddress();
        addr.setUser(user);
        addr.setAddressName(addressName);
        addr.setLabel(request.getLabel() != null && !request.getLabel().isBlank() ? request.getLabel() : "其他");
        addr.setIsDefault(false);
        if (request.getLatitude() != null)  addr.setLatitude(request.getLatitude());
        if (request.getLongitude() != null) addr.setLongitude(request.getLongitude());
        userAddressRepository.save(addr);

        Map<String, Object> res = new HashMap<>();
        res.put("id", addr.getId());
        res.put("label", addr.getLabel());
        res.put("address", addr.getAddressName());
        res.put("latitude", addr.getLatitude());
        res.put("longitude", addr.getLongitude());
        res.put("isDefault", false);
        return ResponseEntity.ok(res);
    }

    @Operation(summary = "更新指定地址", description = "更新指定 ID 的常用地址內容（限本人）。")
    @PutMapping("/api/users/{userId}/addresses/{addressId}")
    public ResponseEntity<?> updateAddressById(@PathVariable Long userId,
            @PathVariable Long addressId,
            @Valid @RequestBody SaveAddressRequest request,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        if (userAddressRepository == null) return ResponseEntity.status(503).body("Service unavailable");
        return userAddressRepository.findById(addressId).map(addr -> {
            if (!addr.getUser().getId().equals(userId))
                return ResponseEntity.status(403).body("無權限修改此地址");
            String addressName = String.join("",
                    request.getCity() != null ? request.getCity() : "",
                    request.getDistrict() != null ? request.getDistrict() : "",
                    request.getStreet() != null ? request.getStreet() : "").trim();
            if (!addressName.isEmpty()) addr.setAddressName(addressName);
            if (request.getLabel() != null && !request.getLabel().isBlank()) addr.setLabel(request.getLabel());
            if (request.getLatitude() != null)  addr.setLatitude(request.getLatitude());
            if (request.getLongitude() != null) addr.setLongitude(request.getLongitude());
            userAddressRepository.save(addr);
            Map<String, Object> res = new HashMap<>();
            res.put("id", addr.getId()); res.put("label", addr.getLabel());
            res.put("address", addr.getAddressName());
            res.put("isDefault", addr.getIsDefault());
            return ResponseEntity.ok(res);
        }).orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "刪除常用地址", description = "刪除指定的常用地址（限本人）。")
    @DeleteMapping("/api/users/{userId}/addresses/{addressId}")
    public ResponseEntity<?> deleteAddress(@PathVariable Long userId,
            @PathVariable Long addressId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        if (userAddressRepository == null) {
            return ResponseEntity.status(503).body("Service unavailable");
        }
        return userAddressRepository.findById(addressId).map(addr -> {
            if (!addr.getUser().getId().equals(userId)) {
                return ResponseEntity.status(403).body("無權限刪除此地址");
            }
            userAddressRepository.delete(addr);
            return ResponseEntity.ok("已刪除");
        }).orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "更新頭像", description = "更新使用者頭像 URL。")
    @PutMapping("/api/users/{userId}/avatar")
    public ResponseEntity<?> updateAvatar(@PathVariable Long userId, @RequestBody AvatarRequest request,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        if (userService == null) {
            return ResponseEntity.status(500).body("UserService not available");
        }
        try {
            User updatedUser = userService.updateUserAvatar(userId, request.getAvatarUrl());
            return ResponseEntity.ok(new UserResponse(
                    updatedUser.getId(), updatedUser.getName(), updatedUser.getRole(),
                    updatedUser.getPicUrl(), updatedUser.getPhone(), updatedUser.getBalance()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Operation(summary = "更新個人資料", description = "更新使用者名稱。")
    @PutMapping("/api/users/{userId}/profile")
    public ResponseEntity<?> updateProfile(@PathVariable Long userId, @Valid @RequestBody ProfileRequest request,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        if (userService == null) {
            return ResponseEntity.status(500).body("UserService not available");
        }
        try {
            User updatedUser = userService.updateUserProfile(userId, request.getUsername());
            return ResponseEntity.ok(new UserResponse(
                    updatedUser.getId(), updatedUser.getName(), updatedUser.getRole(),
                    updatedUser.getPicUrl(), updatedUser.getPhone(), updatedUser.getBalance()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Operation(summary = "儲值", description = "使用者自行儲值，更新餘額並記錄交易。")
    @PostMapping("/api/users/{userId}/recharge")
    public ResponseEntity<?> recharge(@PathVariable Long userId, @Valid @RequestBody RechargeRequest request,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        if (transactionRecordService == null) {
            return ResponseEntity.status(500).body("TransactionRecordService not available");
        }
        try {
            User updatedUser = transactionRecordService.updateStoreCredit(
                    userId, request.getAmount(), com.example.demo.service.wallet.TxType.TOPUP,
                    "帳戶儲值", LocalDateTime.now());
            return ResponseEntity.ok(new UserResponse(
                    updatedUser.getId(), updatedUser.getName(), updatedUser.getRole(),
                    updatedUser.getPicUrl(), updatedUser.getPhone(), updatedUser.getBalance()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Operation(summary = "交易紀錄", description = "取得使用者儲值/消費交易紀錄。")
    @GetMapping("/api/users/{userId}/store-credit-records")
    public ResponseEntity<?> getStoreCreditRecords(@PathVariable Long userId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        if (transactionRecordRepository == null) {
            return ResponseEntity.ok(List.of());
        }
        List<TransactionRecord> records = transactionRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return ResponseEntity.ok(records);
    }

    @Operation(summary = "修改密碼", description = "使用者修改密碼。")
    @PostMapping("/api/users/{userId}/change-password")
    public ResponseEntity<?> changePassword(@PathVariable Long userId, @Valid @RequestBody PasswordRequest request,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        if (userService == null) {
            return ResponseEntity.status(500).body("UserService not available");
        }
        try {
            userService.changePassword(userId, request.getOldPassword(), request.getNewPassword());
            return ResponseEntity.ok("Password changed successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ─── Request/Response DTOs ──────────────────────────────

    @Data
    static class AddressRequest {
        private String city;
        private String district;
        private String street;
    }

    @Data
    static class SaveAddressRequest {
        @jakarta.validation.constraints.Size(max = 50, message = "縣市不可超過 50 字")
        private String city;

        @jakarta.validation.constraints.Size(max = 50, message = "區域不可超過 50 字")
        private String district;

        @jakarta.validation.constraints.Size(max = 150, message = "地址不可超過 150 字")
        private String street;

        @jakarta.validation.constraints.Size(max = 20, message = "標籤不可超過 20 字")
        private String label;

        @jakarta.validation.constraints.DecimalMin(value = "-90.0", message = "緯度超出範圍")
        @jakarta.validation.constraints.DecimalMax(value = "90.0", message = "緯度超出範圍")
        private BigDecimal latitude;

        @jakarta.validation.constraints.DecimalMin(value = "-180.0", message = "經度超出範圍")
        @jakarta.validation.constraints.DecimalMax(value = "180.0", message = "經度超出範圍")
        private BigDecimal longitude;
    }

    @Data
    static class AvatarRequest {
        private String avatarUrl;
    }

    @Data
    static class ProfileRequest {
        @jakarta.validation.constraints.NotBlank(message = "暱稱不可為空")
        @jakarta.validation.constraints.Size(max = 50, message = "暱稱不可超過 50 字")
        private String username;
    }

    @Data
    static class PasswordRequest {
        @jakarta.validation.constraints.NotBlank(message = "請輸入原密碼")
        private String oldPassword;

        @jakarta.validation.constraints.NotBlank(message = "請輸入新密碼")
        @jakarta.validation.constraints.Size(min = 8, max = 64, message = "新密碼長度需介於 8~64 字元")
        private String newPassword;
    }

    @Data
    static class RechargeRequest {
        // ⚠️ 這個值會直接進 updateStoreCredit：null 會 NPE 變成 500，
        //    負數則會「儲值變扣款」。必須在入口就擋掉。
        @jakarta.validation.constraints.NotNull(message = "儲值金額不可為空")
        @jakarta.validation.constraints.DecimalMin(value = "0.01", message = "儲值金額必須大於 0")
        @jakarta.validation.constraints.Digits(integer = 10, fraction = 2, message = "儲值金額格式錯誤")
        private BigDecimal amount;
    }

    @Data
    public static class UserResponse {
        private Long id;
        private String name;
        private String role;
        private String picUrl;
        private String phone;
        private BigDecimal balance;

        public UserResponse(Long id, String name, String role, String picUrl, String phone, BigDecimal balance) {
            this.id = id;
            this.name = name;
            this.role = role;
            this.picUrl = picUrl;
            this.phone = phone;
            this.balance = balance;
        }
    }

}
