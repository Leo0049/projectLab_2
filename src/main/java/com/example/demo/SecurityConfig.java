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
                                                .requestMatchers(
                                                                "/Brand/**",
                                                                "/Customer/**",
                                                                "/Store/**",
                                                                "/auth/**",
                                                                "/api/auth/**",
                                                                "/api/auth/merge/**",
                                                                "/api/brand-auth/**",
                                                                "/api/stores/auth/**",
                                                                "/api/menu/**",
                                                                "/api/home/**",
                                                                "/api/brands/**",
                                                                "/api/products/**",
                                                                "/api/public/**",
                                                                "/api/location/**",
                                                                "/api/game-wheel/brands",
                                                                "/api/game-wheel/menu/**",
                                                                "/api/group-orders/join/**",
                                                                "/swagger-ui/**",
                                                                "/swagger-ui.html",
                                                                "/v3/api-docs/**",
                                                                "/ws/**",
                                                                "/ws-cart/**")
                                                .permitAll()
                                                .requestMatchers("/api/brand/**", "/api/finance/brand/**")
                                                .hasAnyAuthority("BRAND")

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
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .addFilterBefore(
                                                jwtAuthenticationFilter,
                                                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
                return http.build();
        }

        @Bean
        public UrlBasedCorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration config = new CorsConfiguration();
                config.setAllowCredentials(true);
                config.setAllowedOriginPatterns(
                                List.of(
                                                "http://localhost:5173",
                                                "http://127.0.0.1:5500",
                                                "http://localhost:5500",
                                                "http://localhost:60687", // 3/23中平
                                                "http://localhost:54376", // 3/24中平
                                                "http://localhost:63342", // IntelliJ 內建伺服器
                                                "http://localhost:8082"));
                config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                config.setAllowedHeaders(List.of("*"));
                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", config);
                return source;
        }
}
