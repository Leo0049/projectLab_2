package com.example.demo.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 依設定加上 {@code Content-Security-Policy}。
 *
 * <p>⚠️ 為什麼預設是<strong>關閉</strong>：
 * <ul>
 *   <li>本專案的頁面大量使用 inline {@code onclick}／{@code onerror} 與 inline {@code <script>}。
 *       要讓它們繼續動，{@code script-src} 就必須含 {@code 'unsafe-inline'}——
 *       而 {@code 'unsafe-inline'} 對「注入 inline handler」這類 XSS（本專案最主要的風險）
 *       幾乎沒有攔阻效果。</li>
 *   <li>頁面合法載入的第三方資源不少：地圖圖磚、Leaflet、Cloudinary 圖片、Nominatim 位址搜尋、
 *       Firebase 社群登入、WebSocket。白名單寫錯就會弄壞頁面，而**本機沒有可跑的瀏覽器**
 *       能驗證（見 docs/REVIEW_2026-10-11.md 的環境限制）。</li>
 * </ul>
 *
 * <p>因此本輪的做法是：其他標頭（nosniff／frame-ancestors／referrer／permissions／HSTS）
 * 一律由 {@code SecurityConfig} 開著，CSP 則做成可設定的開關，讓有辦法驗證的環境
 * 依照 {@code docs/DEPLOYMENT.md} 的政策範例啟用。真正要收緊 {@code script-src} 的前提是
 * 先把 inline event handler 外部化成檔案。
 *
 * <p>設定：
 * <pre>
 * app.security.csp.enabled: true
 * app.security.csp.policy: "default-src 'self'; script-src 'self' 'unsafe-inline'; …"
 * </pre>
 */
@Slf4j
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Value("${app.security.csp.enabled:false}")
    private boolean cspEnabled;

    @Value("${app.security.csp.policy:}")
    private String cspPolicy;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (cspEnabled && cspPolicy != null && !cspPolicy.isBlank()
                && !response.containsHeader("Content-Security-Policy")) {
            response.setHeader("Content-Security-Policy", cspPolicy);
        } else if (cspEnabled && (cspPolicy == null || cspPolicy.isBlank())) {
            // 開了卻沒給政策是設定錯誤，講一次就好，不要讓它靜默失效
            log.warn("app.security.csp.enabled=true 但 app.security.csp.policy 是空的，未送出 CSP");
        }
        filterChain.doFilter(request, response);
    }
}
