package com.example.demo;

import com.example.demo.common.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

        @Autowired
        private JwtAuthenticationFilter jwtAuthenticationFilter;

        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                http
                                .csrf(AbstractHttpConfigurer::disable)
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                                .formLogin(AbstractHttpConfigurer::disable)
                                .httpBasic(AbstractHttpConfigurer::disable)
                                .authorizeHttpRequests(auth -> auth
                                                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                                                // ⚠️ permitAll 一律指定 HTTP method（見 CLAUDE.md「授權規則」）。
                                                //    下列路徑目前只有 GET 端點；若未來誤加寫入端點，POST/PUT/DELETE
                                                //    會落到後面的角色規則，不會因為整段萬用字元而被放行。
                                                .requestMatchers(HttpMethod.GET,
                                                                "/Brand/**",
                                                                "/Customer/**",
                                                                "/Store/**",
                                                                "/api/menu/**",
                                                                "/api/home/**",
                                                                "/api/public/**",
                                                                "/api/location/**",
                                                                "/api/game-wheel/brands",
                                                                "/api/game-wheel/menu/**",
                                                                "/api/group-orders/join/**",
                                                                "/swagger-ui/**",
                                                                "/swagger-ui.html",
                                                                "/v3/api-docs/**",
                                                                // 未設定 Cloudinary 憑證時，圖片存在本機並以此路徑提供。
                                                                // 內容等同 Cloudinary 上的公開圖片（頭像／logo／封面），
                                                                // 且 <img src> 不會帶 Authorization，必須可公開讀取。
                                                                "/uploads/**")
                                                .permitAll()
                                                // 店家登入只開放 POST
                                                .requestMatchers(HttpMethod.POST, "/api/stores/auth/**")
                                                .permitAll()
                                                // WebSocket 握手：SockJS 的 fallback 傳輸會用到 POST 等多種方法，
                                                // 真正的身分驗證由 WebSocketConfig 的 STOMP CONNECT 攔截器負責。
                                                .requestMatchers("/ws/**", "/ws-cart/**")
                                                .permitAll()
                                                .requestMatchers(HttpMethod.POST,
                                                                "/api/auth/register",
                                                                "/api/auth/login",
                                                                "/api/auth/social-login",
                                                                "/api/auth/corporate-login",
                                                                "/api/auth/firebase-verify",
                                                                "/api/auth/reset-password-firebase",
                                                                "/api/auth/merge/set-password",
                                                                "/api/auth/merge/complete",
                                                                "/api/auth/merge/bind-social-phone",
                                                                "/api/auth/social-signup",
                                                                "/api/brand-auth/register",
                                                                "/api/brand-auth/login")
                                                .permitAll()
                                                .requestMatchers(HttpMethod.GET, "/api/auth/check-phone").permitAll()
                                                .requestMatchers("/api/auth/update", "/api/auth/merge/bind-social")
                                                .hasAuthority("CUSTOMER")
                                                .requestMatchers(
                                                                "/api/brand-auth/create-store",
                                                                "/api/brand-auth/create-store-with-images")
                                                .hasAuthority("BRAND")
                                                .requestMatchers("/api/brand/**", "/api/finance/brand/**")
                                                .hasAnyAuthority("BRAND")

                                                // ── 商品/品牌公開資訊 ──────────────────────────────
                                                // ⚠️ 同 /api/stores 的教訓：requestMatchers(String...) 不分 HTTP method。
                                                // 舊版把 "/api/products/**" 與 "/api/brands/**" 整段 permitAll，
                                                // 導致 POST /api/products（直接 save 原始 entity）未認證即可新增，
                                                // 且因 save() 的 merge 語意可帶 id 竄改他人商品售價。
                                                // 這裡只放行 GET，任何寫入端點都會落到最後的 authenticated()。
                                                .requestMatchers(HttpMethod.GET,
                                                                "/api/brands",
                                                                "/api/brands/**",
                                                                "/api/products/*",
                                                                "/api/products/*/specs",
                                                                "/api/products/*/customization",
                                                                "/api/products/*/customization/v2",
                                                                // 原本是 "/api/products/store/**"。參數化路徑在 Spring Security 的
                                                                // matcher 裡是 pattern 比對，`**` 會連 /store/{id}/all 之類的多層路徑
                                                                // 一併放行；剩下的公開端點只有單層一個，收窄成 `*`（2026-10-11 複審）。
                                                                "/api/products/store/*")
                                                .permitAll()

                                                // ── 分店 API ────────────────────────────────────────
                                                // ⚠️ 這一段的順序有意義，改動前請先讀完。
                                                // 舊版把 "/api/stores/*" 整段 permitAll，而 requestMatchers
                                                // 不分 HTTP method，導致 PUT /api/stores/update 等寫入端點全部對外開放。
                                                // 現在只逐一放行真正公開的讀取端點，其餘一律落到最後的 STORE 規則。
                                                .requestMatchers(HttpMethod.GET,
                                                                "/api/stores/map",
                                                                "/api/stores/nearby",
                                                                "/api/stores/nearby/**",
                                                                "/api/stores/search",
                                                                "/api/stores/brand/**",
                                                                "/api/stores/*/info",
                                                                "/api/stores/*/v2")
                                                .permitAll()
                                                // 分店公開資訊 GET /api/stores/{id}；限定數字才不會誤放行
                                                // /api/stores/orders 這類同層級的管理端點
                                                .requestMatchers(HttpMethod.GET, "/api/stores/{storeId:[0-9]+}")
                                                .permitAll()
                                                // 其餘 /api/stores/** 一律需要 STORE 身分（含所有寫入端點）
                                                .requestMatchers("/api/stores/**").hasAnyAuthority("STORE")
                                                .requestMatchers(
                                                                "/api/orders/**",
                                                                "/api/users/**",
                                                                "/api/wallet/**",
                                                                "/api/game-wheel/**",
                                                                "/api/coupons/**",
                                                                "/api/cart/**",
                                                                "/api/group-orders/**",
                                                                "/api/daily-spin/**",
                                                                "/api/payment-methods/**",
                                                                "/api/user-favorites/**")
                                                .hasAnyAuthority("CUSTOMER")
                                                .anyRequest().authenticated())
                                // ── 安全標頭 ──────────────────────────────────────────
                                // ⚠️ C-3 修復：原本完全沒有設定（連預設值都没人知道有没有生效）。
                                //    這裡明確寫出每一項；Content-Security-Policy 因為需要視環境設定
                                //    來源白名單，改由 SecurityHeadersFilter 依 app.security.csp.* 決定
                                //    （預設關閉，理由見該類別的說明）。
                                .headers(headers -> headers
                                                // 避免瀏覽器把上傳的內容當成別的型別執行
                                                .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults())
                                                // 點擊劫持：同時涵蓋舊瀏覽器的 X-Frame-Options
                                                .frameOptions(frame -> frame.deny())
                                                .referrerPolicy(referrer -> referrer.policy(
                                                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                                                // HSTS 只在 HTTPS 請求送出（Spring 的預設行為），本機 HTTP 開發不受影響
                                                .httpStrictTransportSecurity(hsts -> hsts
                                                                .includeSubDomains(true)
                                                                .maxAgeInSeconds(31536000))
                                                // ⚠️ permissionsPolicy() 回傳的是 PermissionsPolicyConfig，不是
                                                //    HeadersConfigurer，寫在它後面的任何一個 header 都會編不過
                                                //    （實測 javac：cannot find symbol，location: PermissionsPolicyConfig）。
                                                //    它必須是這個 lambda 的最後一個呼叫。
                                                // 這個應用不需要鏡頭／麥克風／付款 API
                                                .permissionsPolicy(permissions -> permissions.policy(
                                                                "geolocation=(self), camera=(), microphone=(), payment=()")))
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .addFilterBefore(
                                                jwtAuthenticationFilter,
                                                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
                return http.build();
        }

        // 與 WebSocketConfig 共用同一份白名單（app.cors.allowed-origins）
        @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins}")
        private String[] allowedOrigins;

        @Bean
        public UrlBasedCorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration config = new CorsConfiguration();
                config.setAllowCredentials(true);
                config.setAllowedOriginPatterns(List.of(allowedOrigins));
                config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                config.setAllowedHeaders(List.of("*"));
                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", config);
                return source;
        }
}
