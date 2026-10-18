package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.AdminService;
import com.example.demo.service.LoginAttemptLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理員後台（N-28）。登入以外的端點都需要 ADMIN 權杖（SecurityConfig 的 /api/admin/** 規則）。
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "管理員後台 (ADMIN)", description = "管理員登入、品牌審核")
public class AdminController {

    @Autowired
    private AdminService adminService;
    @Autowired
    private LoginAttemptLimiter loginAttemptLimiter;

    @Operation(summary = "管理員登入", description = "Body: { username, password }\n\n回傳 JWT Token（role = ADMIN）。")
    @PostMapping("/auth/login")
    public Result login(@RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        String username = body.get("username"), password = body.get("password");
        if (username == null || password == null)
            return Result.error("400", "帳號與密碼不能為空");
        loginAttemptLimiter.check("admin-login", LoginAttemptLimiter.identity(
                LoginAttemptLimiter.clientIp(httpRequest), username));
        return Result.success(adminService.login(username, password));
    }

    @Operation(summary = "待審核品牌清單", description = "page 從 0 起算，size 預設 50、上限 100。")
    @GetMapping("/brands/pending")
    public Result pendingBrands(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        return Result.success(adminService.listPendingBrands(page, size));
    }

    @Operation(summary = "核准品牌", description = "只能核准待審核（PENDING）的品牌；核准後品牌即可登入。")
    @PostMapping("/brands/{brandId}/approve")
    public Result approveBrand(@RequestAttribute("currentUserId") Long adminId, @PathVariable Long brandId) {
        return Result.success(adminService.approveBrand(adminId, brandId));
    }

    @Operation(summary = "退件品牌", description = "只能退件待審核（PENDING）的品牌；退件後品牌無法登入。")
    @PostMapping("/brands/{brandId}/reject")
    public Result rejectBrand(@RequestAttribute("currentUserId") Long adminId, @PathVariable Long brandId) {
        return Result.success(adminService.rejectBrand(adminId, brandId));
    }
}
