package com.example.demo.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * request id 的沿用與產生規則（不載入 Spring context）。
 *
 * <p>守住的性質：合規的 id 原樣沿用；不合規的（含換行、過短、過長）一律換新，不會把攻擊字串寫進日誌；
 * 響應標頭與 MDC 內的值一致；請求結束後 MDC 會清空，避免執行緒池把上一個請求的 id 帶到下一個。
 */
class RequestIdFilterTest {

    @Test
    @DisplayName("合規的 X-Request-Id 原樣沿用，並在請求處理期間放入 MDC")
    void keepsAValidIncomingId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-20261012-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        new RequestIdFilter().doFilter(request, response, chainCapturingMdc(seenInChain));

        assertEquals("req-20261012-abc", response.getHeader("X-Request-Id"));
        assertEquals("req-20261012-abc", seenInChain.get());
    }

    @Test
    @DisplayName("含換行或空白等非法字元的 id 整個丟棄，換成新產生的")
    void replacesAnIdWithControlCharacters() {
        String id = RequestIdFilter.resolve("abc\r\nINFO forged log line");
        assertTrue(id.matches("[0-9a-f]{32}"), id);
    }

    @Test
    @DisplayName("過短（小於 8 字元）與過長（超過 64 字元）的 id 都不沿用")
    void replacesTooShortAndTooLongIds() {
        assertTrue(RequestIdFilter.resolve("short").matches("[0-9a-f]{32}"));
        assertTrue(RequestIdFilter.resolve("x".repeat(65)).matches("[0-9a-f]{32}"));
    }

    @Test
    @DisplayName("沒有標頭時產生新的 id，且每個請求不同")
    void generatesADistinctIdWhenMissing() throws ServletException, IOException {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();
        RequestIdFilter filter = new RequestIdFilter();

        filter.doFilter(new MockHttpServletRequest(), first, (req, res) -> { });
        filter.doFilter(new MockHttpServletRequest(), second, (req, res) -> { });

        assertTrue(first.getHeader("X-Request-Id") != null);
        assertTrue(!first.getHeader("X-Request-Id").equals(second.getHeader("X-Request-Id")));
    }

    @Test
    @DisplayName("請求結束後 MDC 已清空（即使下游拋出例外）")
    void clearsTheMdcAfterTheRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("downstream failure");
        };

        try {
            new RequestIdFilter().doFilter(request, response, failing);
        } catch (Exception expected) {
            // 例外照常往外拋，這裡只關心 MDC
        }

        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    private static FilterChain chainCapturingMdc(AtomicReference<String> sink) {
        // 參數型別必須隱含（FilterChain 的參數是 ServletRequest／ServletResponse，顯式寫成子型別會編譯失敗）
        return (req, res) -> sink.set(MDC.get(RequestIdFilter.MDC_KEY));
    }
}
