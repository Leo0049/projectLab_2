# 案例研究：從審查到修補的十個工程問題

這份文件整理 JoinDrink 在 2026-10-11 複審與 2026-10-12 文件整理中實際遇到的問題。每個案例都包含：**症狀**、**根因**、**修法**、**如何防止再發生**，以及對應的決策紀錄（ADR）與測試。

> **數字來源**：文中的實測數字都來自專案規則檔 [`CLAUDE.md`](../CLAUDE.md) 與 [`docs/REVIEW_2026-10-11.md`](REVIEW_2026-10-11.md)，是修補前在測試環境中重現的結果。修補後的數字則引用自 README「效能」一節的既有壓測紀錄，本次整理沒有重跑（見 [`docs/IMPROVEMENT_ASSESSMENT.md`](IMPROVEMENT_ASSESSMENT.md)）。本文件本身沒有新增任何效能宣稱。

## 案例 1：授權不能只靠「端點寫得對不對」

**症狀**

- 任一登入顧客可以讀取他人完整訂單歷史，包含金額、品項，以及揪團的 `shareToken`（拿到就能加入該團）。
- 攻擊者（餘額 0）把結帳請求的 `userId` 換成受害者，訂單建立成功，受害者餘額 19,255 → 19,220，攻擊者一毛沒付。
- 把 `finalPrice` 改成 1，一杯 35 元的飲料只扣了 1 元。
- 未登入即可 `POST /api/products` 新增商品；帶入既有 `id` 還能竄改他人商品售價。

**根因**

- `requestMatchers(String...)` 不分 HTTP method，`/api/stores/*` 與 `/api/products/**` 整段 permitAll。
- 身分來自用戶端：查詢參數 `authUserId`（不帶就跳過檢查）、路徑上的 `userId`、請求 body 的 `userId`。
- 金額來自用戶端：`totalAmount` 與 `item.finalPrice` 直接拿去扣款。

**修法**

- 預設拒絕：`anyRequest().authenticated()`；permitAll 只放行具體的 `HttpMethod.GET` 路徑。
- 身分一律取自 JWT 寫入的 request attribute `currentUserId`；路徑上的 id 必須通過 `requireSelf` 或 `requireOwnedOrder`。
- 金額一律由 `PricingService` 與 `OrderService.repriceItems()` 依資料庫重算。

**如何防止再發生**

- `AuthorizationTest`（19）守住所有已知繞過。
- `AuthorizationRouteInventoryTest`（3）列舉每一條路由：未列入白名單的路由必須對匿名請求回 401 或 403。新增路由而忘了授權，CI 會紅。
- 決策紀錄：[ADR-0003](adr/0003-authorization-route-allowlist.md)。

**這個案例展示的能力**：把一次性的修補提升為可被機器檢查的規則。

## 案例 2：餘額是「讀、算、寫」，沒有鎖就會遺失更新

**症狀**

- 20 個併發儲值、每筆 10 元：只入帳 70 元。帳本總額為 120 元，與餘額 70 元對不起來。
- 團員同時送出結帳：帳本寫了 −175，餘額只掉 35。
- 團長同時送出結帳（8 個併發）：全部成功，扣了 8 次。
- 取消揪團：託管款退了 280 元，應該只退一次。

**根因**

- 所有金流都是 read-modify-write。併發請求讀到同一個舊值，最後寫入者獲勝。
- 即使加了列鎖，只要呼叫端先讀過同一個 `User`，Hibernate 的一級快取就會回傳「上鎖之前」的實例，列鎖等於被架空。

**修法**

- 餘額異動只經過 `TransactionRecordService.updateStoreCredit` 與 `UserProfileService.topUp`，兩者都以 `findByIdForUpdate`（`SELECT ... FOR UPDATE`）取得列鎖。
- 鎖定後以 `entityManager.refresh(user, PESSIMISTIC_WRITE)` 重讀。普通的 `refresh()` 不夠：REPEATABLE READ 下，普通 SELECT 讀的是交易快照。
- 揪團流程「檢查狀態、扣款、改狀態」一律先鎖列，並在鎖定後做狀態守衛。
- 優惠券以條件式 UPDATE（`markUsedIfUnused`）原子消耗，且在修改品項之前執行。

**如何防止再發生**

- `WalletConcurrencyTest`（3）、`GroupCheckoutConcurrencyTest`（6）以多執行緒重現上述情境，並斷言帳本與餘額相符、重複觸發只發生一次。
- 決策紀錄：[ADR-0001](adr/0001-pessimistic-locking-for-money.md)。

**取捨**：列鎖讓同一使用者或同一團的交易序列化，熱門團的吞吐量會受限。目前規模可接受；若要更高吞吐量需另行設計（見 ADR-0007 的延後項目）。

## 案例 3：評分彙總的「鎖順序」與「快照讀取」

**症狀**

- 12 個併發評分：只有 2 筆成功，其餘 10 筆死鎖。
- 修掉死鎖後，改用 Java 端的 `count` 再寫回，12 筆評分只算出 4 筆。

**根因**

- 寫評分的順序是「先 insert `order_ratings`，後 update `stores`」。外鍵讓 insert 在門市列上取共享鎖，之後的 update 要升級為排他鎖，兩個交易互相等待。
- REPEATABLE READ 下，`count` 讀的是交易開始時的快照，看不到別人剛提交的評分，每個交易都用自己的舊數字覆蓋回去。

**修法**

- 先以 `StoreRepository.findByIdForUpdate` 鎖門市列，再 insert 評分。
- 彙總改用單一 `UPDATE ... (SELECT COUNT/AVG ...)`（`refreshRatingAggregate`），子查詢讀的是最新已提交版本。

**如何防止再發生**：`RatingConcurrencyTest`（2）守住「不得死鎖」與「計數對得起來」兩個性質。

**這個案例的教訓**：鎖的順序是程式的一部分，必須寫在註解與測試裡，否則下一個人會無意中把順序反過來。

## 案例 4：訂單列表在資料累積後才爆炸

**症狀**

- `GET /api/stores/orders` 一次回傳門市的全部歷史訂單。壓測時門市有 6,666 筆，回應 2.1 MB。
- 50 併發下，p50 從 0.14 秒惡化到 2.7 秒，p99 超過 10 秒，比其他端點慢 50 到 60 倍。
- 查詢本身不慢、索引也有生效；慢在序列化與傳輸整份歷史。

**修法**：改為 `Pageable`（`page` 預設 0、`size` 預設 50、上限 200），回傳 `{ orders, page, size, total, totalPages, hasNext }`。

**如何防止再發生**：規則檔要求所有會隨營運時間累積的列表端點一律分頁。

**誠實的註記**：修補後的數字（回應 2,239,488 bytes → 16,955 bytes、p50 2,715 ms → 429 ms）引用自 README「效能」一節，是修補當時在同一台機器、同一組條件下的壓測紀錄；本次整理沒有重跑，因此這裡只引用。`/api/stores/search` 與 `/api/stores/nearby/v2` 仍未分頁，列於 IMPROVEMENT_ASSESSMENT 的「仍未做」。

**這個案例的教訓**：功能測試看不出這類問題，只有把資料量放大才會現形。

## 案例 5：一個隨機碼的長度，決定了碰撞機率

**症狀**：訂單編號 `generateOrderNo()` 的隨機部分只有 4 個十六進位字元，共 65,536 種組合。同一天累積 300 張單時，碰撞機率已接近 50%。`orders.order_no` 有唯一索引，碰撞就是 500。

**修法**：隨機碼改為 10 個字元，規則檔明確禁止改回。

**這個案例的教訓**：碰撞機率應該在設計時估算，不要等到上線後才遇到。

## 案例 6：只有實際走流程才會現形的 LAZY 錯誤

**症狀**：`open-in-view: false` 之下，Service 回傳後任何 LAZY 存取都會拋出 `LazyInitializationException`，固定回 500。規則檔記錄這類錯誤至今已重演九次。其中兩次（下單完成頁、揪團成員清單）是「單獨 curl 端點會過，照使用者流程點才炸」。

**症狀的特徵**：當資料剛好沒有那個關聯時，端點看起來正常；只有特定資料組合才會 500。

**修法**：在交易內把 entity 轉成純 DTO 或 Map（例如 `StoreService.toMap()`、`getGroupOrderDTOByOrderId`），或以 `@EntityGraph` 一次載入。決策紀錄：[ADR-0002](adr/0002-open-session-in-view-off.md)。

**如何防止再發生**：CI 的第二、三層（API 端對端的 `scripts/e2e-verify.js`，以及 UI 流程的 `scripts/ui/run-all.js`）實際走流程。改動序列化相關程式後，對所有 GET 端點做一次 5xx 普掃。

**這個案例的教訓**：單元測試守不住「服務跑起來才會現形」的問題，需要分層的驗證。

## 案例 7：WebSocket 訂閱沒有授權，而訂單 id 是連續整數

**症狀**：任何人都能連上 `/ws-cart` 並訂閱 `/topic/order/{orderId}`。`orderId` 是連續整數，可以列舉他人的訂單狀態。

**修法**：CONNECT 必須帶 JWT；SUBSCRIBE 依角色與參與關係授權（`WebSocketConfig.authorizeSubscribe`）。principal 必須帶 `role`，因為顧客與門市的 id 會撞號，只比對 id 會被繞過。

**如何防止再發生**：`StompSubscribeRulesTest`（9）守住規則，包括「角色必須相符」與「超大數字 id 一律拒絕」。

## 案例 8：多實例部署下，排程任務被執行了 N 次

**根因**：`@Scheduled` 在每個實例上各自觸發。優惠券到期任務在兩支排程中做同一件事（00:00 與 00:01），且沒有任何互斥。

**修法**：`ExclusiveJobRunner` 以 Redis token 鎖做互斥，取不到鎖的實例記 log 後略過。決策紀錄：[ADR-0006](adr/0006-redis-shared-state-and-job-exclusion.md)。

**誠實的限制**：這是互斥，不是「每天只跑一次」；任務很快結束時，另一個實例稍後觸發仍可能再跑一次，因此任務本身必須冪等。Redis 不可用時會退回單實例語意。兩支排程的合併留待下一步。

## 案例 9：文件與程式不一致，本身就是一種缺陷

**症狀**（2026-10-12 文件整理時發現）

- CLAUDE.md 寫測試框架是 TestNG，實際 `src/test` 內 0 處 TestNG，全部是 JUnit 5。
- 測試表的數量過時：`AuthorizationTest` 寫 18，實際 19；`LoginAttemptLimiterTest` 寫 8，實際 9；表中缺 10 個既有測試類別。
- API.md 記錄了一組不存在的 SSE 端點，並列出不存在的 `SseController`；`/api/upload` 寫不需認證，實際需要登入。
- DEPLOYMENT.md 要求部署前「套用 migration」，但 repo 沒有任何 migration。

**修法**：以程式為準逐項修正，數字一律以指令重新計算（例如 `grep -E '^\s*@Test'` 得到 189 個測試、分布在 36 個類別）。

**這個案例的教訓**：給 AI 協作者的規則檔若與程式不一致，會把錯誤的假設直接帶進後續修改。因此規則檔中的數字應該能被重新計算，而不是憑記憶寫下。

## 案例 10：只在 CI 才會重現的時區錯誤

**症狀**：在 UTC 的機器上（CI 就是），示範資料的 `created_at` 落在 16 小時後的未來，永遠排在錢包帳本最上面。本機的時區剛好是 Asia/Taipei，兩次換算互相抵銷，問題不會出現。

**根因**：`TimeZone.setDefault` 放在 `main()`，而測試不會呼叫 `main()`；同時 `DemoDataSeeder` 與 Hibernate 的 `jdbc.time_zone` 各換算一次 +8。

**修法**：時區設定移到 `@PostConstruct`（`config/TimeZoneConfig`），只設定一次。

**這個案例的教訓**：環境相依的錯誤必須在與 CI 相同的環境中重現。本機通過不代表正確。

---

## 這些案例共用的方法

1. **先量測再修**：每個案例都有可重現的數字或失敗的測試，而不是印象。
2. **把規則變成可執行的檢查**：授權白名單、併發測試、靜態檢查、文件的計數指令。
3. **寫下取捨**：每個修法都對應一份 ADR，包含替代方案與代價。
4. **誠實標註未驗證的部分**：本環境無法編譯與執行 CI，尚未驗證的改動列在 [`docs/IMPROVEMENT_ASSESSMENT.md`](IMPROVEMENT_ASSESSMENT.md) 的「未能在本環境驗證的項目」。
