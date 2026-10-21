package com.example.demo.config;

import com.example.demo.common.AccountRef;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目前連線中的 STOMP 工作階段，依帳號（角色加 id）建立索引（stage 6）。
 *
 * <p>權杖失效只能擋住新的 CONNECT 與 SUBSCRIBE；既有的訂閱會持續收到推播，直到連線結束。
 * 帳號停用時必須靠這裡找出該帳號的工作階段並切斷它們，{@link CutSessionOutboundInterceptor} 會丟棄送往這些工作階段的訊息。
 *
 * <p>只存在單一實例的記憶體中，與 SimpleBroker 的限制相同：多實例之間不共享。
 * 工作階段在 DISCONNECT 時移除，所以記憶體用量隨目前連線數成長，不會無限累積。
 */
@Component
public class StompSessionRegistry {

    private final Map<String, AccountRef> accountBySession = new ConcurrentHashMap<>();
    private final Set<String> cutSessions = ConcurrentHashMap.newKeySet();

    /** 登錄一個已認證的工作階段。 */
    public void register(String sessionId, AccountRef account) {
        if (sessionId == null || account == null) {
            return;
        }
        accountBySession.put(sessionId, account);
    }

    /** 工作階段結束時移除（DISCONNECT 或傳輸層關閉）。 */
    public void unregister(String sessionId) {
        if (sessionId == null) {
            return;
        }
        accountBySession.remove(sessionId);
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
}
