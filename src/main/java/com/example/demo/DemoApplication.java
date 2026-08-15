package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class
}) // 💡 徹底禁用「產生隨機密碼」的那個自動配置類別
@EnableScheduling
public class DemoApplication {

    public static void main(String[] args) {
        // 時區統一在 config/TimeZoneConfig 用 @PostConstruct 設定。
        // 放在這裡的話測試不會經過（@SpringBootTest 不呼叫 main），
        // 時區就會和正式啟動不一致——詳見該類別的說明。
        SpringApplication.run(DemoApplication.class, args);
    }

}
