package com.example.demo.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * 路由清單的授權盤點。
 *
 * <p>{@code AuthorizationTest} 守住的是已知漏洞；這支測試守住的是「沒有人記得的路由」：
 * 從 Spring 的 handler mapping 列舉本專案的每一條路由，未列入下方白名單者，未登入送出請求必須得到 401／403。
 * 因此新增一個 controller 方法卻忘了在 SecurityConfig 放行（或忘了加白名單），都會在這裡失敗，
 * 而不是等到有人在正式環境發現。
 *
 * <p>白名單與 {@code SecurityConfig} 的 permitAll 規則一一對應（路徑寫成 handler 的樣式，例如 {@code {storeId}}）。
 * 新增公開路由時，必須同時更新 SecurityConfig 與這裡的白名單；只改其中一處會讓測試失敗。
 *
 * <p>框架自身的端點（{@code /error}、springdoc 的 {@code /v3/api-docs}）不在範圍內，只檢查 {@code com.example.demo} 下的 handler。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationRouteInventoryTest {

    private static final String PROJECT_PACKAGE = "com.example.demo.";

    /** 未登入即可存取的 GET 路由。 */
    private static final Set<String> PUBLIC_GET = Set.of(
            "/api/auth/check-phone",
            "/api/home",
            "/api/menu/store/{storeId}",
            "/api/stores/map",
            "/api/stores/nearby",
            "/api/stores/nearby/v2",
            "/api/stores/search",
            "/api/stores/brand/{brandId}",
            "/api/stores/{id}",                 // SecurityConfig 只放行數字 id（{storeId:[0-9]+}）
            "/api/stores/{storeId}/info",
            "/api/stores/{storeId}/v2",
            "/api/brands",
            "/api/brands/{brandId}",
            "/api/products/store/{storeId}",
            "/api/products/{productId}",
            "/api/products/{productId}/specs",
            "/api/products/{productId}/customization",
            "/api/products/{productId}/customization/v2",
            "/api/location/search",
            "/api/location/cities",
            "/api/location/districts",
            "/api/game-wheel/brands",
            "/api/game-wheel/menu/{brandId}",
            "/api/group-orders/join/{token}");

    /** 未登入即可存取的 POST 路由（登入、註冊、社群登入等）。 */
    private static final Set<String> PUBLIC_POST = Set.of(
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
            "/api/stores/auth/login",
            "/api/brand-auth/register",
            "/api/brand-auth/login");

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private MockMvc mockMvc;

    /** 一條路由 = 一個 HTTP 方法 + 一個 handler 路徑樣式。 */
    private record Route(RequestMethod method, String pattern) {

        boolean isPublic() {
            if (method == RequestMethod.GET) {
                return PUBLIC_GET.contains(pattern);
            }
            return method == RequestMethod.POST && PUBLIC_POST.contains(pattern);
        }

        /** 把路徑變數換成 1，得到可以實際送出的路徑（對 {@code {storeId:[0-9]+}} 這類限定也成立）。 */
        String samplePath() {
            return pattern.replaceAll("\\{[^}]*\\}", "1");
        }

        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    @Test
    @DisplayName("列舉到的專案路由數量不為零（否則下面的檢查會假性通過）")
    void inventoryIsNotEmpty() {
        assertFalse(projectRoutes().isEmpty(), "沒有列舉到任何專案路由，handler mapping 的掃描條件可能錯誤");
    }

    @Test
    @DisplayName("除白名單外，所有專案路由未登入都必須被擋下（401／403）")
    void everyOtherRouteRequiresAuthentication() {
        List<String> open = new ArrayList<>();
        for (Route route : projectRoutes()) {
            if (route.isPublic()) {
                continue;
            }
            int status = anonymousStatus(route);
            if (status != 401 && status != 403) {
                open.add(route + " → " + status);
            }
        }
        assertTrue(open.isEmpty(),
                () -> "以下路由未列入公開白名單，卻可以未登入存取（若是刻意公開，請同步更新 SecurityConfig 與本測試）：" + open);
    }

    @Test
    @DisplayName("白名單中的每一條路由都真實存在，且 GET 公開路由確實可匿名存取")
    void everyPublicRouteExistsAndIsOpen() {
        Set<Route> routes = new LinkedHashSet<>(projectRoutes());

        List<String> missing = new ArrayList<>();
        List<String> closed = new ArrayList<>();

        for (String pattern : PUBLIC_GET) {
            Route route = new Route(RequestMethod.GET, pattern);
            if (!routes.contains(route)) {
                missing.add(route.toString());
                continue;
            }
            int status = anonymousStatus(route);
            if (status == 401 || status == 403) {
                closed.add(route + " → " + status);
            }
        }
        for (String pattern : PUBLIC_POST) {
            Route route = new Route(RequestMethod.POST, pattern);
            if (!routes.contains(route)) {
                missing.add(route.toString());
            }
        }

        assertTrue(missing.isEmpty(), () -> "白名單中有不存在的路由（已改名或刪除？請同步更新白名單）：" + missing);
        assertTrue(closed.isEmpty(), () -> "白名單中的路由實際被擋下，表示 SecurityConfig 與白名單不一致：" + closed);
    }

    // ── 列舉與送出請求 ─────────────────────────────────────────────

    private List<Route> projectRoutes() {
        Set<Route> routes = new LinkedHashSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().getName().startsWith(PROJECT_PACKAGE)) {
                continue;
            }
            // 一個 handler 可能掛多個路徑（例如 @GetMapping({"/join/{token}", "/{token}"})），每個路徑各自判斷
            for (String pattern : entry.getKey().getPatternValues()) {
                for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
                    routes.add(new Route(method, pattern));
                }
            }
        }
        return new ArrayList<>(routes);
    }

    /** 以未登入身分送出請求，回傳 HTTP 狀態碼。例外視為 500（非 401／403，在公開路由的檢查中算作「可存取」）。 */
    private int anonymousStatus(Route route) {
        try {
            return mockMvc.perform(request(HttpMethod.valueOf(route.method().name()), route.samplePath()))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        } catch (Exception e) {
            return 500;
        }
    }
}
