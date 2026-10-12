package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.DailySpinService;
import com.example.demo.service.PublicService;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class DailySpinController {

    private final DailySpinService dailySpinService;
    private final PublicService publicService;

    @GetMapping("/game-wheel/brands")
    public Result getBrands() {
        return Result.success(publicService.getBrands());
    }

    @GetMapping("/game-wheel/menu/{brandId}")
    public Result getMenu(@PathVariable Long brandId) {
        List<String> categories = dailySpinService.getCategoriesByBrand(brandId);
        // 符合前端結構: { wheelOptions: [{categoryName: '...'}] }
        List<Map<String, String>> wheelOptions = categories.stream()
                .map(cat -> Map.of("categoryName", cat))
                .toList();
        return Result.success(Map.of("wheelOptions", wheelOptions));
    }

    @GetMapping("/coupons/spin-status")
    public Result getSpinStatus(@RequestAttribute("currentUserId") Long userId) {
        boolean hasSpun = dailySpinService.hasSpunToday(userId);
        return Result.success(Map.of("remainSpins", hasSpun ? 0 : 1));
    }

    /**
     * 執行轉盤。業務錯誤一律以 {@link com.example.demo.exception.CustomException} 拋出，
     * 由 GlobalExceptionHandler 轉成對應的 HTTP 狀態（今日已轉 → 409、鎖忙碌 → 503、品牌沒有可抽內容 → 422）。
     *
     * <p>這裡不再自行 catch：原本 catch RuntimeException 會把例外訊息放進 body 回傳，
     * 而且是 HTTP 200，前端只能靠 body 的 code 判斷失敗。
     */
    @PostMapping("/coupons/spin")
    public Result spin(@RequestAttribute("currentUserId") Long userId,
                       @RequestBody SpinRequest request) {
        return Result.success(dailySpinService.spin(userId, request.getBrandId()));
    }

    @Data
    public static class SpinRequest {
        private Long brandId;
    }
}
