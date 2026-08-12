package com.example.demo.common;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;

@Slf4j
@Configuration
public class FirebaseConfig {

    private final ResourceLoader resourceLoader;

    /**
     * 金鑰位置，支援 classpath: 與 file: 前綴。
     * 舊版寫死 classpath:serviceAccountKey.json，讓 application.yml 的設定形同虛設。
     */
    @Value("${firebase.config-path:classpath:serviceAccountKey.json}")
    private String configPath;

    public FirebaseConfig(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() {
        Resource resource = resourceLoader.getResource(configPath);

        // 本機開發（sms.mode=mock）不需要金鑰，缺檔時只停用社群登入，不擋啟動
        if (!resource.exists()) {
            log.warn("⚠️ 找不到 Firebase 金鑰 [{}]，社群登入與手機驗證將無法使用。"
                    + "若只做本機開發，請確認 sms.mode=mock。", configPath);
            return;
        }

        if (!FirebaseApp.getApps().isEmpty()) {
            log.debug("Firebase 已初始化，略過。");
            return;
        }

        try (InputStream serviceAccount = resource.getInputStream()) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("✅ Firebase Admin SDK 初始化成功（{}）", configPath);
        } catch (Exception e) {
            // 金鑰存在卻讀取失敗代表設定有誤，要讓它明顯可見，但仍不擋其他功能啟動
            log.error("❌ Firebase 初始化失敗（{}）：{}", configPath, e.getMessage(), e);
        }
    }
}
