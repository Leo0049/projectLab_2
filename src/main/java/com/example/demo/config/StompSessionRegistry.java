package com.example.demo.config;

import com.example.demo.common.AccountRef;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目前連線中的 STOMP 工作階段，依帳號（角色加 id）建立索引（stage 6），並記下每個工作階段的權杖到期時間（stage 26）。
 *
 * <p>權杖失效擋住的是新的 CONNECT 與 SUBSCRIBE；既有的訂閱要靠出站端擋下：
 * 帳號停用與撤銷時，這裡找出該帳號的工作階段並切斷它們；權杖到期時，{@link #mayDeliver} 依登錄時記下的到期時間拒絕送達。
 * {@link CutSessionOutboundInterceptor} 會在每則訊息送出前詢問這裡，被拒絕的訊息一律丟棄。
 *
 * <p>只存在單一實例的記憶體中，與 SimpleBroker 的限制相同：多實例之間不共享。
 * 工作階段在 DISCONNECT 時移除，所以記憶體用量隨目前連線數成長，不會無限累積。
 */
@Component
public class StompSessionRegistry {

    private final Map<String, AccountRef> accountBySession = new ConcurrentHashMap<>();
    private final Map<String, Instant> expiresAtBySession = new ConcurrentHashMap<>();
    private final Set<String> cutSessions = ConcurrentHashMap.newKeySet();

    /**
     * 登錄一個已認證的工作階段，並記下權杖的到期時間。
     *
     * <p>沒有到期時間就無法執行到期規則，因此直接切斷（fail-closed）；所有呼叫端都必須傳入到期時間。
     */
    public void register(String sessionId, AccountRef account, Instant expiresAt) {
        if (sessionId == null || account == null) {
            return;
        }
        accountBySession.put(sessionId, account);
        if (expiresAt == null) {
            cutSessions.add(sessionId);
        } else {
            expiresAtBySession.put(sessionId, expiresAt);
        }
    }

    /** 工作階段結束時移除（DISCONNECT 或傳輸層關閉）。 */
    public void unregister(String sessionId) {
        if (sessionId == null) {
            return;
        }
        accountBySession.remove(sessionId);
        expiresAtBySession.remove(sessionId);
        cutSessions.remove(sessionId);
    }

    /** 切斷某帳號目前所有的工作階段。之後送往這些工作階段的訊息一律丟棄。 */
    public void cut(AccountRef account) {
        accountBySession.forEach((sessionId, ref) -> {
            if (ref.equals(account)) {
                cutSessions.add(sessionId);
            }
        });
    }

    /** 切斷單一工作階段（例如登錄時發現帳號已被停用）。 */
    public void cutSession(String sessionId) {
        if (sessionId != null) {
            cutSessions.add(sessionId);
        }
    }

    public boolean isCut(String sessionId) {
        return sessionId != null && cutSessions.contains(sessionId);
    }

    /**
     * 此刻是否還能把訊息送到該工作階段；出站端每則訊息送出前都會呼叫（stage 26）。
     *
     * <p>已切斷的一律不可送達。權杖已到期的也不可送達，判斷與 SUBSCRIBE 相同：到期時間當下即視為到期。
     * 沒有工作階段 id 的訊息（系統訊息），以及尚未登錄的工作階段，維持原行為可送達，因為 CONNECTED 訊框可能早於登錄送出；
     * 認證失敗的連線本來就不會被登錄，也不會有訂閱。
     *
     * @param sessionId 訊息所屬的工作階段，可為 null
     * @param now       目前時間
     */
    public boolean mayDeliver(String sessionId, Instant now) {
        if (sessionId == null) {
            return true;
        }
        if (cutSessions.contains(sessionId)) {
            return false;
        }
        Instant expiresAt = expiresAtBySession.get(sessionId);
        return expiresAt == null || now.isBefore(expiresAt);
    }
}
