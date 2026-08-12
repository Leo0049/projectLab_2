package com.example.demo.dto;

import lombok.Data;

@Data
public class SocialAuthRequest {
    private String idToken;     // Firebase 傳回的身份憑證
    private String phone; // 三方驗證後綁定的手機
    private String name;        // 從三方平台抓取的名稱
    // ✅ 新增：登入平台，例如 GOOGLE / FACEBOOK（不傳預設為 GOOGLE）
    private String provider;
}