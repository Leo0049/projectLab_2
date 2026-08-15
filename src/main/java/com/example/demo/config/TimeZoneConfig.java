package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * 全專案的時區基準：Asia/Taipei。
 *
 * <p>⚠️ 這段原本寫在 {@code DemoApplication.main()} 裡，而**測試不會呼叫 main()**
 * （{@code @SpringBootTest} 是直接建 context），所以測試環境的 JVM 預設時區
 * 仍是主機時區。在 UTC 的機器上（GitHub Actions 就是）會疊出下面這個結果：
 *
 * <ol>
 *   <li>{@code DemoDataSeeder} 明確用 {@code LocalDateTime.now(ZoneId.of("Asia/Taipei"))} → ＋8 小時</li>
 *   <li>{@code spring.jpa.properties.hibernate.jdbc.time_zone: Asia/Taipei} 寫入時再換算一次 → 又 ＋8 小時</li>
 * </ol>
 *
 * <p>兩個 ＋8 疊起來，示範資料的 {@code created_at} 落在**16 小時後的未來**。
 * 後果是這些列會一直排在使用者錢包帳本的最上面，比之後真正發生的交易還「新」——
 * 實測 CI 上「最新一筆是剛才的儲值」與「扣款紀錄的說明帶得到訂單編號」兩項因此失敗，
 * 而本機因為主機時區剛好就是 Asia/Taipei，兩個換算互相抵銷，完全看不出來。
 *
 * <p>改成 {@code @PostConstruct} 之後，任何啟動方式（jar、mvn spring-boot:run、
 * {@code @SpringBootTest}）都會套用同一個時區，Hibernate 的換算也就不再疊加。
 */
@Slf4j
@Configuration
public class TimeZoneConfig {

    public static final String ZONE = "Asia/Taipei";

    @PostConstruct
    public void applyDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
        log.info("預設時區設定為 {}", ZONE);
    }
}
