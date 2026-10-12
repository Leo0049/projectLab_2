# ADR-0004：用戶端 IP 只取 `getRemoteAddr()`，登入與 OTP 以 Redis 固定視窗限流

- **狀態**：Accepted
- **日期**：2026-10-12 補錄（限流於 2026-10-11 複審修補時加入）
- **相關**：ADR-0003、ADR-0006；`application.yml` 的 `server.forward-headers-strategy`；`docs/DEPLOYMENT.md`

## 背景

修補前專案沒有任何速率限制。密碼規則只有 8–16 字元，`/api/auth/login`、`/api/brand-auth/login`、`/api/stores/auth/login` 都是公開且可無限次呼叫，線上爆破與撞庫沒有成本。`/api/auth/check-phone` 直接回答「這個號碼是否已註冊」，可以做帳號列舉。

若要依 IP 限流，最直覺的做法是讀 `X-Forwarded-For`。但這個標頭由用戶端自行送出，攻擊者每次換一個值就能繞過限制。因此「信任哪一個 IP」是本決策的核心問題。

## 決策

1. **應用程式不自行解析 `X-Forwarded-For`**。`LoginAttemptLimiter.clientIp(request)` 只取 `request.getRemoteAddr()`。
2. **反向代理的真實 IP 交給容器處理**。`server.forward-headers-strategy` 由環境變數 `SERVER_FORWARD_HEADERS_STRATEGY` 控制，預設 `native`。Spring Boot 的三個值：
   - `native`：由 Servlet 容器（Tomcat 的 RemoteIpValve）處理，只信任來自內部代理（私有網段與 127.0.0.1）送來的 `X-Forwarded-*`。
   - `framework`：由 Spring 的 ForwardedHeaderFilter 處理。
   - `none`：完全忽略這些標頭。
   直連時標頭一律忽略，偽造的 `X-Forwarded-For` 因此無效。
3. **限流鍵**為 `ratelimit:<scope>:<identity>`。登入的 identity 是 `IP|帳號`（帳號轉小寫），避免同一個 NAT 後面的正常使用者互相牽連；沒有帳號可比對的端點（例如簡訊驗證碼的檢查）只用 IP。預設為同一個鍵 300 秒內最多 10 次，超過回傳 429。
4. **計數器是固定視窗**：Redis 上以 `INCR` 計數，第一次計數時以 `EXPIRE` 設定視窗秒數。視窗自第一次嘗試起算，到期後歸零。（原本的 Javadoc 寫成「滑動視窗」，已更正。）
5. **Redis 不可用時退回本地計數**：每個實例各自計數，只在單實例下正確，與 ADR-0006 的退避策略一致。

## 後果

**取得的**：偽造標頭無法改變識別；Redis 故障時登入功能不會整個壞掉，只是限流變弱。

**付出的代價與限制**：
- 固定視窗在視窗交界處，短時間內的嘗試上限可達約兩倍。對爆破防禦已足夠，但它不是精確的速率控制。
- 多實例且 Redis 不可用時，實際上限會乘以實例數。
- 正式環境若前面有反向代理，代理必須位於容器信任的網段內，並且覆寫 `X-Forwarded-For`。否則 `getRemoteAddr()` 永遠是代理的位址，所有使用者會共用同一個計數。這是部署設定問題，見 `docs/DEPLOYMENT.md`。
- 較成熟的做法是在閘道層（API Gateway、WAF）限流。本專案目前只做應用層的最後一道防線。

## 替代方案

- **信任 `X-Forwarded-For`**：不採用，理由見上。
- **引入 Bucket4j 或 Resilience4j**：功能較完整，但會增加依賴與設定面。目前的需求用 Redis 計數即可滿足。

## 如何驗證

`LoginAttemptLimiterTest`（9）：
- `blocksOnlyAfterTheLimitIsExceeded`、`countsEachIdentitySeparately`、`countsEachScopeSeparately`：限流的基本行為。
- `usesRedisWhenAvailable`、`fallsBackToLocalCounterWhenRedisIsUnavailable`：兩條儲存路徑。
- `forgedForwardedForDoesNotChangeTheIdentity`：偽造的 `X-Forwarded-For` 不改變識別。
- `redisExpiryUsesTheRequestedWindow`：Redis 的逾時使用呼叫端指定的視窗（修補前寫死為 300 秒，任何其他視窗都會失效）。
- `clientIpFallsBackToRemoteAddr`：沒有可用資訊時回到 `getRemoteAddr()`。
