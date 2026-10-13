package com.example.demo.dto;

/**
 * 品牌清單的回應格式（轉盤與首頁篩選共用）。
 *
 * <p>⚠️ 這個 record 是前後端的契約：欄位名稱會直接成為 JSON 的 key。
 * 轉盤前端讀的是 {@code brandId}／{@code brandName}。原本後端回傳 {@code Map}、前端讀 {@code id}／{@code name}，
 * 兩邊對不上就會讓轉盤無法選取品牌，而且編譯與後端測試都不會發現。
 * 要改這裡的欄位名稱，必須同步修改前端並更新 {@code BrandSummaryJsonTest}。
 */
public record BrandSummary(Long brandId, String brandName, String logoUrl) {
}
