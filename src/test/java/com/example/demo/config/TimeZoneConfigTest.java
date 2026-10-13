package com.example.demo.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 時區基準（不載入 Spring context）。這個測試會改動 JVM 的預設時區，因此前後都要還原。
 */
class TimeZoneConfigTest {

    private TimeZone originalDefault;

    @BeforeEach
    void startFromADifferentZone() {
        originalDefault = TimeZone.getDefault();
        // 先換成別的時區，測試才不會因為主機本來就是 Asia/Taipei 而假性通過（CI 是 UTC）
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restoreDefault() {
        TimeZone.setDefault(originalDefault);
    }

    @Test
    @DisplayName("啟動時把 JVM 預設時區設為 Asia/Taipei（測試也走這條路，不能只靠 main()）")
    void appliesAsiaTaipeiAsTheJvmDefault() {
        new TimeZoneConfig().applyDefaultTimeZone();

        assertEquals("Asia/Taipei", TimeZone.getDefault().getID());
    }
}
