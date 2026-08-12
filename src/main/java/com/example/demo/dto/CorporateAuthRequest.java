package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 企業登入（STORE / BRAND）專用 DTO
 * 帳號格式不限手機號碼，可為 email 或任意字串
 */
@Data
public class CorporateAuthRequest {

    @NotBlank(message = "帳號不能為空")
    private String account;   // 企業帳號（對應 users.phone 欄位）

    @NotBlank(message = "密碼不能為空")
    private String password;
}
