# ADR-0013：Redis 鍵加上環境命名空間，跨環境的單次使用紀錄例外

- **狀態**：Accepted
- **日期**：2026-10-22
- **相關**：IMPROVEMENT_ASSESSMENT 第 8 項（Redis 沒有環境隔離）、ADR-0004（限流）、ADR-0005（冪等）、ADR-0006（Redis 承載共享狀態與排程互斥）、ADR-0009（手機驗證權杖只能使用一次）

## 背景

- 多個環境（正式、測試、本機）若指向同一台 Redis，鍵會互相干擾。鍵的內容多半只有使用者 id、排程名稱或門市 id，而這些值在每個環境的資料庫中各自獨立，因此會撞在一起：
  - `lock:job:order-auto-reject`：測試環境持有後，正式環境的自動拒單會被略過。ADR-0006 的排程互斥以這把鍵為準，鍵上沒有環境標記。
  - `spin:done:<userId>:<date>`：測試環境的使用者 5 抽過轉盤，正式環境的使用者 5 當天就不能抽。
  - `lock:checkout:<userId>` 與 `idem:<scope>:<userId>:<key>`：另一環境的同一個 userId 會互相排隊，或拿到另一環境的冪等回放。
  - `ratelimit:<scope>:<identity>`：另一環境的失敗登入會佔用本環境的額度。
- 既有的做法是「正式環境使用獨立的 Redis 實例」（見 `docs/DEPLOYMENT.md`）。這是正確的做法，但只存在於文件中，沒有程式碼檢查，本機與測試環境很容易共用。
- 有一個鍵的情況相反：`fbtoken:<sha256>`（ADR-0009）記錄手機驗證權杖是否已使用。這張權杖由同一個 Firebase 專案簽發，在每個環境都有效。目前因為環境共用 Redis，A 環境用過的權杖在 B 環境也會被拒絕。如果把它也加上前綴，這個保證就會消失。

## 決策

1. **鍵加上命名空間**：環境變數 `REDIS_KEY_NAMESPACE`（對應 `app.redis.key-namespace`）。所有 Redis 服務（`fbtoken` 除外）注入的主要 `StringRedisTemplate`（`@Primary`），以及通用 `RedisTemplate`，鍵序列化器都改為 `NamespacedRedisKeySerializer`：寫入時加上 `<命名空間>:`，讀回時去掉。Lua 解鎖腳本的 KEYS 也經由同一個序列化器，因此加鎖與解鎖使用同一把鍵。
2. **只包鍵**：hash 欄位（購物車品項的 id）、zset 成員（購物車 token）與 value 都是資料，不加前綴。
3. **格式**：`^[a-z][a-z0-9-]{0,31}$`。不含冒號，避免與鍵的分隔符號混淆。非空值格式不合規時，不論 profile 都拒絕啟動。
4. **prod 必須設定**：`prod` profile 未設定即拒絕啟動（fail-closed，與 `JwtUtils` 的密鑰檢查及 `sms.mode` 相同的做法）。非 prod 留空即不加前綴，本機與 CI 的鍵名不變。
5. **跨環境的例外**：`fbtoken:` 使用不加前綴的 `globalStringRedisTemplate`（bean 名稱為 `RedisConfig.GLOBAL_STRING_TEMPLATE`）。`FirebaseTokenReplayGuard` 以 `@Qualifier` 指定它，`RedisConfigWiringTest` 會檢查這個接線。
6. **不強制改基礎設施**：程式不偵測兩個環境是否共用 Redis，也不要求獨立實例。`docs/DEPLOYMENT.md` 仍建議獨立實例，並要求每個環境設定不同的命名空間。

## 後果

取得的：

- 共用 Redis 時，各環境的排程互斥、轉盤標記、結帳鎖、冪等紀錄與限流計數彼此獨立。
- `fbtoken:` 的單次使用保證沒有因為這次變更而減弱。

付出的代價：

- **首次啟用的一次性影響**：部署後既有的鍵全部改用新的名稱，舊鍵不再被讀取，會依各自的 TTL 過期。影響如下：
  - 部署當天已抽過轉盤的使用者，可以再抽一次（最多一次）。
  - 部署前 24 小時內的結帳冪等紀錄不再回放，同一把鍵重送會被當成新請求。團購結帳另有列鎖與狀態守衛，不會重複扣款；個人結帳的重複處理本次未另外驗證。
  - 進行中的結帳鎖與排程鎖失效，部署瞬間可能短暫重疊。排程任務本身是冪等的（ADR-0006）。
  - 登入與 OTP 的限流計數歸零（一個視窗）。
  - `fbtoken:` 不受影響。
  - 建議在低峰時段部署，並確認沒有進行中的結帳。
- **命名空間不是隔離**：共用同一台 Redis 時，記憶體上限（`maxmemory`）、淘汰策略、持久化與故障範圍仍然共用。獨立實例仍是建議做法；命名空間只防止鍵衝突。
- **命名空間只能靠部署者設對**：兩個 prod 環境若設成同一個值，仍會互相干擾，程式無法偵測這件事。
- **跨環境的權杖保證依賴共用的 Redis**：若兩個環境使用不同的 Redis，`fbtoken:` 也不會跨環境共用，這與改動前相同，不是新的退步。
- 新增的鍵若需要跨環境共用，必須明確使用 `globalStringRedisTemplate`，並在本文件追加說明。

## 替代方案

- **只靠獨立實例（改動前的做法）**：正確，但沒有程式碼檢查，本機與測試環境很容易共用。保留為建議做法，但不是唯一防線。
- **所有鍵都加前綴（含 `fbtoken:`）**：統一且簡單，但 `fbtoken:` 的跨環境保證會消失。未採用。
- **在每個鍵的字串中寫死環境前綴**：會散落在各服務的多處呼叫點，漏寫一處就出錯。未採用。
- **啟動時偵測共用**：在 Redis 寫入擁有者標記，發現不同擁有者就拒絕啟動。能抓到設定錯誤，但兩個環境刻意使用不同命名空間時仍可能誤判，且讓啟動依賴 Redis 的寫入狀態。未採用。

## 如何驗證

- `RedisKeyNamespaceTest`（6）、`NamespacedRedisKeySerializerTest`（7）：格式、前綴、往返、null、冒號、另一命名空間的鍵。
- `RedisConfigWiringTest`（8，不連線）：主要模板與通用模板只包鍵、hash 欄位與 value 不加、prod 未設定或格式錯誤時拒絕啟動、fbtoken 守衛使用全域模板。
- `RedisKeyNamespaceIntegrationTest`（6，真實 Redis）：伺服器上的實際鍵名、Lua 解鎖對同一把帶前綴的鍵生效、兩個命名空間的同名鍵互不干擾、fbtoken 不加前綴。使用隨機命名空間，不呼叫 FLUSH。
- 還原驗證：見 README「2026-10-22 Redis 鍵命名空間還原驗證（CI）」。
