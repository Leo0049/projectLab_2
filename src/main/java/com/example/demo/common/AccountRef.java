package com.example.demo.common;

import java.util.Locale;

/**
 * 一個帳號的身分（角色加 id）。角色一律轉為大寫，確保比對時不受大小寫影響。
 *
 * <p>門市與顧客的 id 會撞號，因此身分一律以「角色 + id」成對表示（見 N13）。
 */
public record AccountRef(String role, long id) {

    public AccountRef {
        role = role == null ? "" : role.toUpperCase(Locale.ROOT);
    }
}
