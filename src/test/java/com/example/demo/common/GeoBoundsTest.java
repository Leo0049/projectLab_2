package com.example.demo.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附近查詢的矩形預篩（不載入 Spring context）。
 *
 * <p>守住的性質只有一條：矩形必須包含半徑內的每一點。若這條不成立，SQL 預篩會把合格的店家過濾掉，
 * 而且不會有任何錯誤訊息——所以用球面公式反算圓周上的點來逐一驗證，而不是只測幾個固定座標。
 */
class GeoBoundsTest {

    private static final double EARTH_RADIUS_KM = 6371.0;   // 與 SQL 內 Haversine 使用的半徑相同

    /** 由中心點出發、沿方位角走 distanceKm 公里後的座標（球面三角公式）。 */
    static double[] destination(double lat, double lng, double distanceKm, double bearingRad) {
        double delta = distanceKm / EARTH_RADIUS_KM;
        double phi1 = Math.toRadians(lat);
        double lambda1 = Math.toRadians(lng);
        double phi2 = Math.asin(Math.sin(phi1) * Math.cos(delta)
                + Math.cos(phi1) * Math.sin(delta) * Math.cos(bearingRad));
        double lambda2 = lambda1 + Math.atan2(
                Math.sin(bearingRad) * Math.sin(delta) * Math.cos(phi1),
                Math.cos(delta) - Math.sin(phi1) * Math.sin(phi2));
        return new double[] {Math.toDegrees(phi2), Math.toDegrees(lambda2)};
    }

    @Test
    @DisplayName("矩形是圓的超集合：半徑內（含圓周）的任一點都落在矩形內")
    void boundsContainEveryPointInsideTheRadius() {
        double radius = 50.0;
        Random random = new Random(20261012L);   // 固定種子，失敗時可重現
        for (int i = 0; i < 5_000; i++) {
            double lat = 21.5 + random.nextDouble() * 4.5;      // 台灣本島緯度範圍
            double lng = 119.5 + random.nextDouble() * 2.5;     // 台灣本島經度範圍
            GeoBounds box = GeoBounds.around(lat, lng, radius);

            double bearing = random.nextDouble() * 2 * Math.PI;
            // 一半的樣本取圓周上的點（最容易踩到邊界），其餘取圓內任意距離
            double distance = (i % 2 == 0) ? radius : radius * random.nextDouble();
            double[] p = destination(lat, lng, distance, bearing);

            assertTrue(box.contains(p[0], p[1]),
                    () -> "中心 " + lat + "," + lng + " 的半徑內點 " + p[0] + "," + p[1] + " 不在矩形內");
        }
    }

    @Test
    @DisplayName("矩形不會大到失去預篩意義（50 公里約為 0.45 度緯度）")
    void boundsStayTight() {
        GeoBounds box = GeoBounds.around(25.0, 121.0, 50.0);
        double latSpan = box.latMax() - box.latMin();
        assertTrue(latSpan > 0.88 && latSpan < 0.95, "緯度跨距 " + latSpan);
    }

    @Test
    @DisplayName("半徑 0 時退化為中心點本身")
    void zeroRadiusCollapsesToTheCentre() {
        GeoBounds box = GeoBounds.around(25.0, 121.0, 0);
        assertEquals(25.0, box.latMin(), 1e-9);
        assertEquals(25.0, box.latMax(), 1e-9);
        assertTrue(box.contains(25.0, 121.0));
    }

    @Test
    @DisplayName("經緯度夾在合法範圍內")
    void clampsToValidCoordinateRanges() {
        GeoBounds box = GeoBounds.around(89.9, 179.9, 50);
        assertTrue(box.latMax() <= 90.0);
        assertTrue(box.lngMax() <= 180.0);
    }

    @Test
    @DisplayName("負的半徑直接拒絕")
    void rejectsNegativeRadius() {
        assertThrows(IllegalArgumentException.class, () -> GeoBounds.around(25, 121, -1));
    }
}
