package com.example.demo.controller;

import com.example.demo.service.DailySpinService;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class DailySpinController {

    private final DailySpinService dailySpinService;
    private final com.example.demo.repository.BrandRepository brandRepository;

    @GetMapping("/game-wheel/brands")
    public com.example.demo.common.Result getBrands() {
        return com.example.demo.common.Result.success(brandRepository.findAll());
    }

    @GetMapping("/game-wheel/menu/{brandId}")
    public com.example.demo.common.Result getMenu(@PathVariable Long brandId) {
        List<String> categories = dailySpinService.getCategoriesByBrand(brandId);
        // 符合前端結構: { wheelOptions: [{categoryName: '...'}] }
        List<Map<String, String>> wheelOptions = categories.stream()
                .map(cat -> Map.of("categoryName", cat))
                .toList();
        return com.example.demo.common.Result.success(Map.of("wheelOptions", wheelOptions));
    }

    @GetMapping("/coupons/spin-status")
    public com.example.demo.common.Result getSpinStatus(@RequestAttribute("currentUserId") Long userId) {
        boolean hasSpun = dailySpinService.hasSpunToday(userId);
        return com.example.demo.common.Result.success(Map.of("remainSpins", hasSpun ? 0 : 1));
    }

    @PostMapping("/coupons/spin")
    public com.example.demo.common.Result spin(
            @RequestAttribute("currentUserId") Long userId,
            @RequestBody SpinRequest request) {
        try {
            return com.example.demo.common.Result.success(dailySpinService.spin(userId, request.getBrandId()));
        } catch (com.example.demo.exception.CustomException e) {
            // 「今天已經抽過」是正常的使用者情境，不是系統錯誤。
            // 原本整段 catch RuntimeException 再回 Result.error()（固定 code=500），
            // 演示時第二次點轉盤，devtools 會看到一個紅色的 500。
            return com.example.demo.common.Result.error(e.getCode(), e.getMsg());
        } catch (RuntimeException e) {
            return com.example.demo.common.Result.error(e.getMessage());
        }
    }

    @Data
    public static class SpinRequest {
        private Long brandId;
    }
}
