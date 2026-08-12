package com.example.demo.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtils jwtUtils;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
//檢查有沒有token ,有token去security查看api訪問權限
        // 1. 取得 Header 裡的 Token
        String authHeader = request.getHeader("Authorization");
        String token = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            token = request.getHeader("token"); // 備案：支援你原本的 token header
        }

        // 2. 如果有 Token，就進行解析並注入權限
        if (token != null && !token.isEmpty()) {
            try {
                Long userId = jwtUtils.getUserIdFromToken(token);
                String role = jwtUtils.getRoleFromToken(token);

                if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    // 💡 核心：建立 Spring Security 認可的身分物件
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userId, null, Collections.singletonList(new SimpleGrantedAuthority(role))
                    );

                    // 💡 注入上下文，這樣後面的 .hasAnyAuthority(role) 就會通了
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    // 順便存入 request，讓 Controller 還是可以用 @RequestAttribute("currentUserId")
                    request.setAttribute("currentUserId", userId);
                    System.out.println("✅ Filter 解析成功 - UID: " + userId + ", Role: " + role);
                }
            } catch (Exception e) {
                System.out.println("❌ Filter 解析失敗: " + e.getMessage());
                // 這裡不拋異常，讓後面的 SecurityConfig 決定是否攔截
            }
        }

        // 3. 繼續往後走（傳遞給下一個 Filter 或 Controller）
        filterChain.doFilter(request, response);
    }
}