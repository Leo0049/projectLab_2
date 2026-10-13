package com.example.demo.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 為每個請求指定 request id：沿用可信的 {@code X-Request-Id}，否則產生新的。
 *
 * <p>用途：客服或前端回報問題時附上 id，就能在日誌中找到同一個請求的所有紀錄。
 * 寫入 MDC 的 {@code requestId}，日誌樣式會顯示它；同時回寫到響應標頭。
 *
 * <p>⚠️ 用戶端送來的值只接受 {@code [A-Za-z0-9_-]{8,64}}：這同時擋掉換行（日誌注入）與超長字串。
 * 不合規的值不會被截斷後沿用，而是整個丟棄、換成新的 id。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 純函式：合規則沿用，否則產生新的 32 位十六進位 id。 */
    static String resolve(String incoming) {
        if (incoming != null && VALID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
