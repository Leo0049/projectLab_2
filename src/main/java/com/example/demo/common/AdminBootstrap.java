package com.example.demo.common;

import com.example.demo.entity.AdminAccount;
import com.example.demo.repository.AdminAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 首次建立管理員帳號（N-28）。
 *
 * <p>管理員沒有公開的註冊端點。啟動時若設定了 {@code ADMIN_BOOTSTRAP_USERNAME} 與
 * {@code ADMIN_BOOTSTRAP_PASSWORD_HASH}（BCrypt 雜湊，不是明文），且該帳號尚不存在，就建立一筆。
 * 帳號已存在時不覆寫密碼，因此可以重複啟動；建立後應從環境變數移除這兩個設定。
 *
 * <p>設定格式錯誤時直接讓啟動失敗，避免以為已建立管理員、實際上卻登不進去。
 */
@Slf4j
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.-]{3,64}$");
    /** BCrypt：$2a$／$2b$／$2y$、兩位成本，接 53 個字元（整段 60 字元） */
    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    @Value("${admin.bootstrap.username:}")
    private String configuredUsername;

    @Value("${admin.bootstrap.password-hash:}")
    private String configuredPasswordHash;

    @Autowired
    private AdminAccountRepository adminAccountRepository;

    @Override
    public void run(ApplicationArguments args) {
        ensureAdmin(configuredUsername, configuredPasswordHash);
    }

    /**
     * 帳號不存在且設定有效時建立，回傳是否新建。設定格式錯誤拋出 {@link IllegalStateException}。
     * package-private 以利單元測試。
     */
    boolean ensureAdmin(String username, String passwordHash) {
        if (username == null || username.isBlank()) {
            return false;
        }
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalStateException("ADMIN_BOOTSTRAP_USERNAME 格式不正確（3–64 個英數字、底線、點或連字號）");
        }
        if (passwordHash == null || !BCRYPT.matcher(passwordHash).matches()) {
            throw new IllegalStateException("ADMIN_BOOTSTRAP_PASSWORD_HASH 必須是 BCrypt 雜湊（以 $2a$、$2b$ 或 $2y$ 開頭）");
        }
        if (adminAccountRepository.existsByUsername(username)) {
            return false;
        }
        AdminAccount admin = new AdminAccount();
        admin.setUsername(username);
        admin.setPasswordHash(passwordHash);
        adminAccountRepository.save(admin);
        log.info("已建立管理員帳號 username={}；建立後請從環境變數移除 ADMIN_BOOTSTRAP_* 設定", username);
        return true;
    }
}
