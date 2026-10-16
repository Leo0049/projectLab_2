package com.example.demo.common;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 「只記一次」的儲存：本地記錄一定會做；有遠端（Redis）時也會問遠端，讓多實例共享同一份紀錄。
 *
 * <p>規則：本實例已記過 → 拒絕（遠端不必再問）；本實例沒有記過 → 再問遠端，遠端說已記過就拒絕。
 * 遠端拋例外（不可用）時退回本地記錄，並通知 {@code onRemoteFailure}。
 *
 * <p>⚠️ 為什麼本地一定要做：Redis 故障期間記下的權杖，在 Redis 恢復後仍必須擋得住同一實例的重放；
 * 只靠遠端的話，恢復後的第一次重放會被當成新的而被接受。
 *
 * <p>⚠️ 取捨：Redis 故障期間只保證單一實例內正確，多實例之間不共享，與 {@code LoginAttemptLimiter} 相同。
 * 遠端回傳 null（不應發生）視為「已記過」，即拒絕（失敗時關閉）。
 *
 * <p>純邏輯，不依賴 Spring：遠端、時鐘與失敗通知都由建構子注入，測試可以用假實作。
 */
public final class ClaimStore {

    /** 遠端寫入：key 不存在才寫入並設定有效期；回傳 true 代表本次寫入成功。 */
    @FunctionalInterface
    public interface Remote {
        Boolean setIfAbsent(String key, Duration ttl);
    }

    private final Remote remote;
    private final LongSupplier clockMillis;
    private final Consumer<RuntimeException> onRemoteFailure;
    private final LocalClaimStore local = new LocalClaimStore();

    /**
     * @param remote          遠端實作；null 代表只用本地記錄
     * @param clockMillis     現在的毫秒時間（測試可注入）
     * @param onRemoteFailure 遠端拋例外時的通知（例如寫一行警告）
     */
    public ClaimStore(Remote remote, LongSupplier clockMillis, Consumer<RuntimeException> onRemoteFailure) {
        this.remote = remote;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.onRemoteFailure = Objects.requireNonNull(onRemoteFailure, "onRemoteFailure");
    }

    /** 第一次記錄 key 回 true；有效期內再次記錄回 false。 */
    public boolean claimIfAbsent(String key, Duration ttl) {
        if (!local.claimIfAbsent(key, ttl.toMillis(), clockMillis.getAsLong())) {
            return false;
        }
        if (remote == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(remote.setIfAbsent(key, ttl));
        } catch (RuntimeException e) {
            onRemoteFailure.accept(e);
            return true;
        }
    }
}
