package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
//前端註冊傳回的類型
@Data
//傳統註冊 登入
public class ClassicAuthRequest {
    @NotBlank(message = "手機號碼不能為空")
    @Size(min = 10, max = 10, message = "手機號碼必須剛好 10 碼")
    @Pattern(regexp = "^09\\d{8}$", message = "手機號碼必須以 09 開頭")
    private String phone; // 手機號碼 (帳號)
    private String password;    // 加密用的原始密碼
    private String name;        // 註冊時填寫的暱稱
    private String idToken;     // 註冊時用於驗證手機真實性的 Firebase Token
//    idToken 驗證完就丟了，不需要存進資料庫，所以不該出現在 User 實體裡。

}