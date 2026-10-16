package com.example.demo.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM 內的「只記一次」儲存（純邏輯，時間由呼叫端傳入，方便測試）。
 *
 * <p>Redis 不可用時的退路：單一實例內正確，多實例之間不共享。
 */
public final class LocalClaimStore {

    private final Map<String, Long> expiresAtMillis = new ConcurrentHashMap<>();

    /**
     * 若 key 尚未記錄，或記錄已過期，則記下並回傳 true；仍在有效期內則回傳 false。
     * 單一 key 的檢查與寫入是原子的（{@link ConcurrentHashMap#compute}）。
     */
    public boolean claimIfAbsent(String key, long ttlMillis, long nowMillis) {
        expiresAtMillis.entrySet().removeIf(entry -> entry.getValue() <= nowMillis);
        boolean[] claimed = {false};
        expiresAtMillis.compute(key, (k, until) -> {
            if (until != null && until > nowMillis) {
                return until;
            }
            claimed[0] = true;
            return nowMillis + ttlMillis;
        });
        return claimed[0];
    }

    /** 目前保留中的紀錄數（監控與測試用）。 */
    public int size() {
        return expiresAtMillis.size();
    }
}
