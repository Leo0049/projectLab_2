package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Location / 定位", description = "地址搜尋、縣市區域選單。")
@RestController
public class LocationController {

    @Autowired private LocationService locationService;

    @Operation(summary = "地址關鍵字搜尋",
            description = "輸入地址關鍵字，回傳對應的地址與經緯度。\n\n⚠️ 生產環境請整合 Google Maps Geocoding API。\n\nQuery: ?keyword=台中市西區")
    @GetMapping("/api/location/search")
    public Result searchAddress(@RequestParam String keyword) {
        return Result.success(locationService.searchAddress(keyword));
    }

    @Operation(summary = "取得縣市列表", description = "回傳台灣所有縣市代碼與名稱，用於地址選單。")
    @GetMapping("/api/location/cities")
    public Result getCities() {
        return Result.success(locationService.getCities());
    }

    @Operation(summary = "取得區域列表", description = "依縣市代碼回傳對應區域列表。Query: ?cityCode=TXG")
    @GetMapping("/api/location/districts")
    public Result getDistricts(@RequestParam String cityCode) {
        return Result.success(locationService.getDistricts(cityCode));
    }
}
