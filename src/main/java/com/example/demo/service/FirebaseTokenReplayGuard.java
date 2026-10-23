package com.example.demo.service;

import com.example.demo.common.ClaimStore;
import com.example.demo.common.TokenFingerprint;
import com.example.demo.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Firebase 手機驗證 ID token 的「只能成功使用一次」檢查。
 *
 * <p>⚠️ 為什麼需要：Firebase ID token 在到期（1 小時）前可以被無限次驗證通過。原本
 * {@code verifyFirebasePhone} 只比對號碼，同一張手機驗證 token 只要還沒過期，就能一再用來
 * 重設同一帳號的密碼、建立帳號或綁定第三方——洩漏一張 token 等於保有重設權。
 *
 * <p>做法：號碼比對通過、真正要執行動作時記下權杖的 SHA-256 指紋（{@link TokenFingerprint}），
 * 保留 2 小時——超過 token 的 1 小時有效期加上時鐘誤差，之後 verifyIdToken 本身就會拒絕。
 * 只存指紋：Redis 與記憶體都不保存權杖本身。
 *
 * <p>只用於手機驗證權杖。社群登入權杖（Google／Facebook）在多步驟的整合流程中會被重複使用，
 * 不在這裡記錄，這是已知的取捨（見 CLAUDE.md）。
 */
@Slf4j
@Service
public class FirebaseTokenReplayGuard {

    /** 保留時間：Firebase ID token 有效期 1 小時，加上時鐘誤差的餘裕。 */
    static final Duration RETENTION = Duration.ofHours(2);

    /** Redis 鍵前綴；鍵後面接的是權杖指紋，不是權杖本身。 */
    private static final String KEY_PREFIX = "fbtoken:";

    private final ClaimStore claims;

    /**
     * ⚠️ 必須標 {@code @Autowired}：類別另有一個套件內建構子（測試用），Spring 不標註就不知道該用哪一個。
     * 與 {@code LoginAttemptLimiter} 同樣的理由。
     *
     * <p>⚠️ 必須使用 {@code globalStringRedisTemplate}（不加命名空間）：單次使用紀錄跨環境共用，見 ADR-0013。
     * 改用主要模板會讓同一張權杖在另一個環境再被接受一次。
     */
    @Autowired
    public FirebaseTokenReplayGuard(
            @Qualifier(RedisConfig.GLOBAL_STRING_TEMPLATE) ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        ClaimStore.Remote remote = redisTemplate == null
                ? null
                : (key, ttl) -> redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
        this.claims = new ClaimStore(remote, System::currentTimeMillis,
                e -> log.warn("Redis 不可用，驗證權杖的使用紀錄改用本地記錄：{}", e.getMessage()));
    }

    /** 測試用：直接注入 ClaimStore（可用假的遠端與時鐘）。 */
    FirebaseTokenReplayGuard(ClaimStore claims) {
        this.claims = claims;
    }

    /**
     * 第一次使用這張 ID token 回 true（並記下）；保留期間內再次使用回 false。
     *
     * @throws IllegalArgumentException 權杖為空白或 null（呼叫端已先經過 Firebase 驗證，正常不會發生）
     */
    public boolean claimOnce(String idToken) {
        return claims.claimIfAbsent(KEY_PREFIX + TokenFingerprint.of(idToken), RETENTION);
    }
}
