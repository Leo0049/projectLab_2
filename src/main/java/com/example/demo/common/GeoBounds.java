package com.example.demo.common;

/**
 * 以中心點與半徑（公里）換算出的經緯度矩形，用來在 SQL 裡先做可走索引的 {@code BETWEEN} 預篩。
 *
 * <p>⚠️ 矩形必須是圓的<b>超集合</b>（半徑內的每一間店都要落在矩形內），否則預篩會漏店。
 * 因此換算用的 km／度 常數取「偏小」的值，讓矩形略大一點；精確距離仍由 SQL 的 Haversine
 * 在 {@code HAVING} 中判斷，多出來的候選列不會被回傳。`GeoBoundsTest` 以球面公式反算圓周上的點來驗證這個性質。
 *
 * <p>適用範圍：台灣本島（緯度約 21–26°）。跨越 ±180° 經線、接近極點的情況未處理；
 * 緯度夾在 [-90, 90]、經度夾在 [-180, 180]。
 */
public record GeoBounds(double latMin, double latMax, double lngMin, double lngMax) {

    /**
     * 1° 經線弧長（km）。球面模型（R = 6371）的精確值為 2πR/360 ≈ 111.195；
     * 取 111.0 讓矩形略大，屬於安全方向。
     */
    static final double KM_PER_DEGREE = 111.0;

    public static GeoBounds around(double lat, double lng, double radiusKm) {
        if (radiusKm < 0) {
            throw new IllegalArgumentException("radiusKm must be >= 0");
        }
        double latDelta = radiusKm / KM_PER_DEGREE;
        double cosLat = Math.cos(Math.toRadians(lat));
        // 接近極點時經度範圍趨近整圈，直接給滿範圍（服務範圍不會走到這裡）
        double lngDelta = cosLat < 1e-6 ? 180.0 : radiusKm / (KM_PER_DEGREE * cosLat);
        return new GeoBounds(
                Math.max(-90.0, lat - latDelta),
                Math.min(90.0, lat + latDelta),
                Math.max(-180.0, lng - lngDelta),
                Math.min(180.0, lng + lngDelta));
    }

    public boolean contains(double lat, double lng) {
        return lat >= latMin && lat <= latMax && lng >= lngMin && lng <= lngMax;
    }
}
