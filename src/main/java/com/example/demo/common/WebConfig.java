package com.example.demo.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.upload.local-dir:uploads}")
    private String localUploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/Brand/**")
                .addResourceLocations("file:frontend/Brand/")
                .setCachePeriod(0);

        registry.addResourceHandler("/Customer/**")
                .addResourceLocations("file:frontend/Customer/")
                .setCachePeriod(0);

        registry.addResourceHandler("/Store/**")
                .addResourceLocations("file:frontend/Store/")
                .setCachePeriod(0);

        registry.addResourceHandler("/auth/**")
                .addResourceLocations("file:frontend/")
                .setCachePeriod(0);

        // 未設定 Cloudinary 憑證時，ImageStorageService 會把圖片存到這個目錄
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + localUploadDir + "/")
                .setCachePeriod(3600);
    }

    // ⚠️ 這裡原本還有一份 addCorsMappings("/**").allowedOrigins("*")，已移除。
    //    CORS 白名單的唯一來源是 app.cors.allowed-origins，由 SecurityConfig（HTTP）
    //    與 WebSocketConfig（STOMP）共用。那份 "*" 目前被 Spring Security 的 CORS
    //    filter 遮蔽（未授權來源會先被擋成 403）而不生效，但它與白名單自相矛盾，
    //    一旦日後調整 SecurityConfig 就可能變成真的全域放行。
}
