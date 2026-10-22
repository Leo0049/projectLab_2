# 優化建議實作評估（2026-10-12）

本文件逐條回應 2026-10-12 提出的 20 項優化建議（編號與當時的清單一致），記錄每一項的**決策、理由與實際落地狀態**。
目的有兩個：一是讓每個決定都有可追溯的依據；二是把「做了什麼、沒做什麼、為什麼」說清楚，而不是只列出完成項。

## 評估準則

| 準則 | 說明 |
|------|------|
| 風險 | 是否會動到金流、授權、序列化或交易邊界。這類改動必須有測試守住，且要先能在 CI 上跑通。 |
| 可驗證性 | 本次開發環境**無法連到 Maven Central（403）、沒有 Docker daemon、沒有 MySQL**，所以編譯、單元測試、整合測試與 UI 流程都只能在 CI 上驗證。凡是無法在 CI 驗證、只能靠推論的改動，一律延後或只做可驗證的部分。 |
| 價值 | 對使用者、維運或後續接手的人的可見價值。重要的是「可被重跑的證據」，而不是數字包裝。 |
| 範圍 | 能否在一個 PR 內完整做完（含測試與文件）。做不完的部分寫明下一步，不留半成品。 |

決策分三類：**實作**（本 PR 完成並附測試）、**部分實作**（列出做了的與沒做的）、**延後**（寫入 [ADR-0007](adr/0007-deferred-decisions.md) 路線圖，並說明前置條件）。

## 事實更正

初版回覆與後續文件中有數字需要更正或重新量測：

- `Map<String, Object>` 以 `grep -rhoE 'Map<String,\s*Object>' src/main/java | wc -l` 計次，目前為 **311 處**。初版寫的 314 是更早的計數；本次以目前的工作樹重新量測。
- 直接引用 `JOIN_TOKEN` 的前端檔案為 **11 個**，HTML 中的 inline `on*=` 事件屬性約 **100 處**。這兩個數字是評估 JWT 遷移與 CSP 收緊範圍的依據，見第 3 項。
- 受版控的 JavaScript 為 **79 個**（不含 `frontend/vendor/`，其中 `frontend/` 為 71 個、`scripts/` 為 8 個；CI 的 `node --check` 涵蓋全部 79 個）；HTML 內嵌 script 為 **69 個**，分布在 **39 個** HTML 檔案中。初版寫的 78 個 JS 少算了一個。

## 逐項決策

| # | 建議 | 決策 | 本 PR 實際做了什麼 | 延後或未做的部分與理由 |
|---|------|------|-------------------|----------------------|
| 1 | 登入／OTP 限流可被 `X-Forwarded-For` 繞過 | **實作** | `LoginAttemptLimiter.clientIp()` 不再自己解析標頭，只用 `getRemoteAddr()`；`server.forward-headers-strategy` 預設 `native`，由容器只信任可信代理送來的標頭（直連時 XFF 被忽略，經代理時取代理鏈上的真實來源）。另修正一個既有 bug：Redis 路徑與本地退避路徑都忽略呼叫端傳入的 `windowSeconds`，一律用 300 秒。限流的 Javadoc 原本寫「滑動視窗」，實際是固定視窗，已更正註解（行為未變）。 | 「以帳號為 key 的寬視窗」未做：它會讓攻擊者能鎖住他人帳號，需要產品決定視窗長度。 |
| 2 | 前端快取不依使用者隔離，登出不清 | **實作** | `fetchAPI` 的快取鍵加入登入者 userId（未登入為 `anon`）；`logout()` 清除所有 `cache_*`；`order_details.html` 改用同一個 `cacheKeyFor()`，避免兩處格式漂移。 | — |
| 3 | JWT 放 localStorage、CSRF、CSP 預設關閉 | **延後**（ADR-0007） | — | 改為 httpOnly cookie 需要同時處理：11 個直接讀 token 的檔案、`nav-auth.js` 的集中處理、STOMP CONNECT 的 header 驗證、CORS credentials 與 CSRF token。CSP 收緊的前置條件是約 **100 處 inline `on*=` 事件屬性**要先外部化。這兩件事都是跨頁面的結構改動，無法在沒有瀏覽器與後端的環境中安全完成，應分階段做。 |
| 4 | 下單與結帳沒有冪等鍵 | **實作**（opt-in） | 新增 `Idempotency-Key` 標頭（格式 8–64 字元英數、底線、連字號）。以 Redis `SETNX` 佔位，成功結果以 24 小時保存並於重送時原樣重播（標頭 `Idempotent-Replayed: true`）；佔位中的重送回 409；失敗（4xx 或例外）釋放佔位，允許重試；Redis 不可用時退回本地計數（與 `RedisLockService` 相同策略）。結帳頁自動送出同一把鍵直到成功。**另修正**：結帳扣款成功之後的地址儲存、通知等步驟若失敗，原本會回 400，前端重送就會重複建單；現在只記 log，仍回成功。 | 團購結帳（`/api/group-orders/{token}/checkout`）已有列鎖與狀態守衛，本次未加冪等鍵，避免擴大範圍。請求內容比對已於 2026-10-17 補上（見「仍未做」第 3 項）。 |
| 5 | 沒有 schema migration（Flyway） | **延後**（ADR-0008） | — | Flyway 的 baseline 必須從實際資料庫匯出，這個環境取不到。`ddl-auto: update` 目前仍是建表的唯一來源。另已確認的風險：`user_coupons.obtained_date` 的 generated column 在既有資料庫不會被 `ddl-auto` 改寫（entity 註解已載明），已記錄在 DATABASE.md。 |
| 6 | 餘額扣款改條件式 UPDATE、加 `@Version` | **延後**（ADR-0001 記錄取捨） | — | 這是金流路徑。現行的列鎖做法由 `WalletConcurrencyTest` 與 `GroupCheckoutConcurrencyTest` 守住（已驗證改回舊寫法會轉紅）。改寫後必須在 CI 上跑完這兩支並確認結果不變；在無法執行的環境裡改，風險高於收益。 |
| 7 | 附近查詢全表 Haversine、無分頁、無索引 | **部分實作** | (a) 先以 bounding box（`GeoBounds`）做 `BETWEEN` 預篩，再對候選列算 Haversine；`GeoBounds` 有「矩形必為圓的超集合」的測試。(b) `stores(latitude, longitude)` 加上索引 `idx_stores_lat_lng`。(c) 無定位時改為分頁（**原本會忽略 `size`/`page` 回傳全部店家**）。(d) `/api/stores/nearby/v2` 補上與 v1 相同的座標範圍檢查（超出回 400）。 | 地圖端點 `/api/stores/map` 仍是全量查詢：改成視窗查詢需要前端送出地圖邊界，屬於下一個 PR。`/api/stores/nearby/v2` 已於 2026-10-17 移除（見「仍未做」第 2 項）。**本次未量測前後差異**，不宣稱任何效能數字。 |
| 8 | 缺 batch fetch，N+1 無全域防線 | **部分實作** | `spring.jpa.properties.hibernate.default_batch_fetch_size: 50`。 | 以 Hibernate Statistics 斷言各端點 SQL 次數的測試尚未建立，列為下一步。 |
| 9 | 沒有伺服器端快取 | **延後** | — | 目前沒有量測到熱點。快取需要先設計後台編輯時的失效策略與多實例一致性；在沒有量測資料前引入，只會增加一致性風險。 |
| 10 | 前端資產偏重 | **延後** | — | 本次量測：`frontend/vendor/tailwind.js` 511,356 位元組（執行期 CDN 編譯版）、`images/coupons.jpg` 939,337 位元組、`group_order.html` 181,814 位元組，其中內嵌 script 129,146 位元組（約 71%）。Tailwind 預編譯需要建置步驟；內嵌 script 外移與第 3 項的 CSP 有關。 |
| 11 | `Map<String, Object>` 散落，缺契約 | **部分實作** | `PublicService.getBrands()` 改回傳 `BrandSummary` record（JSON 欄位名稱不變）。`BrandSummaryJsonTest` 守住 `brandId`／`brandName`／`logoUrl` 三個欄位。e2e 檢查轉盤品牌的欄位型別。這正是轉盤品牌清單 bug 的成因。 | 其餘 311 處 `Map<String, Object>` 逐步遷移，不在單一 PR 內完成。 |
| 12 | 超大類別 | **延後**（ADR-0007） | — | `GroupOrderService` 1,610 行、`OrderService` 925 行、`UserController` 758 行（已量測）。拆分必須以完整的 `mvn test` 為安全網，且一次只拆一個類別；在本環境無法執行測試，本次不做。 |
| 13 | 推播散落在 Controller | **延後**（ADR-0007） | — | 目前 18 處 `convertAndSend` 都位於交易提交之後，沒有現行錯誤。改用 `@TransactionalEventListener(AFTER_COMMIT)` 需要調整所有呼叫點與測試，屬於結構性重構。 |
| 14 | `DailySpinController` 自行 catch，回 HTTP 200 | **實作** | 業務錯誤交給 `GlobalExceptionHandler`：「今日已抽過」回 **HTTP 409**（原為 200 + body code）。`DailySpinService` 的使用者訊息由 `RuntimeException` 改為 `CustomException`，否則移除 catch 後會變成通用 500。移除 `catch (RuntimeException) → Result.error(e.getMessage())`，不再把內部訊息回給前端。前端 `fetchAPI` 對非 2xx 會拋出 `msg`，提示文字不變。另外把「該品牌沒有可抽獎的飲品」的檢查移到寫入當日標記之前（422 不再消耗當日次數）。 | 當日標記在資料庫寫入之前寫入的問題仍在（見「仍未做」）。 |
| 15 | 沒有全路由授權檢查 | **實作** | `AuthorizationRouteInventoryTest` 列舉所有 handler mapping，對**未列入白名單**的路由送出未登入請求，必須得到 401／403；白名單中有不存在的路由也會失敗。新增公開路由時，必須同步更新白名單與 `SecurityConfig`。 | CI 已執行並通過（見「未能在本環境驗證」）。 |
| 16 | E2E 固定等待、失敗難讀 | **部分實作** | (a) `run-all.js` 寫入 `GITHUB_STEP_SUMMARY` 的表格，失敗項以 `::error` 註解；API e2e 同樣寫摘要。(b) 轉盤流程的 9 秒固定等待改為條件等待。(c) CI 新增靜態檢查：79 個受版控 JS 以 `node --check` 逐一檢查，39 個 HTML 中的 69 個 inline script 以 `scripts/ui/check-inline-scripts.js` 檢查。 | 其餘 34 處 `waitForTimeout` 於改寫相關流程時逐步替換，不一次全改。`run-all.js` 的失敗名稱是攔截 `console.log` 取得的，屬於脆弱做法（見「仍未做」）。 |
| 17 | 文件與現況不符 | **部分實作** | (a) 更正 CLAUDE.md 的 TestNG 錯誤（`src/test` 內 0 處，全部是 JUnit 5）；依實際檔案重列測試表（217 個 `@Test`、42 個類別）。(b) API.md 移除不存在的 SSE 段落，改寫 WebSocket／STOMP；補上冪等、轉盤狀態碼、附近店家分頁與座標規則、健康檢查與 `X-Request-Id`。(c) 新增 ADR（`docs/adr/`）與問題紀錄（`docs/ISSUE_LOG.md`），README 增加文件導覽。(d) DEPLOYMENT.md 改寫「套用 migration」的說法（repo 沒有 migration），補上反向代理與 Docker 段落。 | CLAUDE.md 拆分為多份文件延後：它同時是 AI 協作的規則檔，拆分前要先決定載入方式，否則規則可能失效。 |
| 18 | 沒有 Actuator、沒有 request id | **部分實作** | (a) `spring-boot-starter-actuator`，只暴露 `health`（不顯示細節），GET `/actuator/health` 加入白名單。(b) `RequestIdFilter`：驗證 `X-Request-Id` 格式、不合規則產生新 id、回寫響應標頭、放入 MDC，日誌樣式顯示 requestId。 | metrics 端點未暴露：暴露前要先決定監控後端與存取控制。 |
| 19 | 多實例：WebSocket 與排程重複執行 | **部分實作** | 兩支 `@Scheduled` 任務改由 `ExclusiveJobRunner` 執行，沿用 `RedisLockService` 的 token 鎖；只有取得鎖的實例會真的執行，其餘記 log 後略過（`ExclusiveJobRunnerTest`）。 | WebSocket 仍是 `enableSimpleBroker`（單實例）。改為 STOMP relay 需要 RabbitMQ 或類似基礎設施，列入 ADR-0007。 |
| 20 | 工程基礎與安全小項 | **部分實作** | (a) JaCoCo 報告，**無覆蓋率門檻**，CI 摘要顯示行覆蓋率。(b) CI 與 `docker-compose.yml` 的映像鎖版：`mysql:8.0.42`、`redis:7.4-alpine`。(c) 多階段 `Dockerfile`（`maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jre`，非 root 執行，映像內含 `frontend/`），CI 新增建置工作（只建置、不推送）。(d) CORS 預設清單改為 `localhost:5173`、`127.0.0.1:5500`、`localhost:5500`、`localhost:8082`、`127.0.0.1:8082`，移除 `60687`、`54376`、`63342` 等 IDE 臨時埠，並移除 5501／5502／3000／8080，改列在 local 範例檔。 | `mvnw`：需要從 Maven Central 下載 wrapper，此環境無法產生。Testcontainers 延後，現行測試仰賴 `docker compose` 與 CI service，遷移需要另案。密碼政策（8–16 字元 → 較長上限與黑名單）屬產品決策，且需處理 BCrypt 的 72 bytes 上限，另案處理。 |

## 評估過程中另外發現的問題

以下不在原本 20 項內，但在評估時發現。有些已在本 PR 處理，有些記錄為待辦：

- **結帳的 catch-all 會把「已扣款之後」的失敗回報為 400**（已於第 4 項修正）。這會讓前端重送、造成重複建單。
- **`/api/stores/nearby` 無定位時忽略 `size`／`page`**（已於第 7 項修正）。目前前端都會帶座標，實際影響限於 `/api/home`。
- **`LoginAttemptLimiter` 的 `windowSeconds` 被忽略**（已於第 1 項修正）。目前唯一非 300 秒的呼叫端是 `check-phone`（60／300），其窗口實際上仍是 300 秒。
- **`ScheduledTasks` 與 `CouponExpiryScheduler` 做同一件事**（00:00 與 00:01 兩次標記過期券）。本次只以互斥鎖保護，合併留待下一步。
- **`DailySpinService` 的使用者訊息是 `RuntimeException`**，若不先改，移除 catch 後訊息會消失（已於第 14 項處理）。
- **`order_details.html` 的快取鍵是手動拼的**，與 `fetchAPI` 格式可能漂移（已於第 2 項改為共用 helper）。
- **`PublicService.searchStores` 的 `page`／`size` 被忽略**：回傳全部符合者。已於 2026-10-18 改為分頁（見「仍未做」第 1 點）。
- **文件數字不一致（已統一）**：`LazyInitializationException` 的重演次數統一以 `CLAUDE.md` 與問題紀錄的「九次」為準，README 原本的「十個地方」已改寫。
- **文件與程式不符，已修正**：CLAUDE.md 寫 `AuthorizationTest` 為 18 個、`LoginAttemptLimiterTest` 為 8 個，實際為 19 與 9，且測試表缺 10 個既有類別（另有 6 個為本次新增），已依實際檔案重列；API.md 寫 `/api/upload` 不需認證（實際需登入）、列出不存在的 `SseController`，已修正；DEPLOYMENT.md 寫「部署前套用 migration」，但 repo 沒有 migration，已改為人工建立結構的說明。

## 仍未做（已知缺口）

這些是評估後確認存在、但本 PR 沒有處理的缺口。每一項都寫明影響，方便之後排序：

1. [已處理 2026-10-18] **`GET /api/stores/search` 未分頁**：`page`／`size` 被忽略，回傳全部符合者。修正會改變既有前端拿到的筆數，需要先決定預設上限，因此不在本 PR 內。處理：預設每頁 50 筆、上限 200，回應含真實的 `total`、`totalPages`、`hasNext`；前端改為每頁 12 筆並以「展開更多」載入，過期的回應以序號丟棄。
2. [已處理 2026-10-17] **`GET /api/stores/nearby/v2` 未分頁**：回傳全部接單中店家。違反「列表端點一律分頁」的規則（見 `CLAUDE.md`）。處理：端點已移除（controller、`SecurityConfig` 白名單、路由清單測試，以及只有它使用的服務程式碼），前端沒有呼叫者。
3. [已處理 2026-10-17] **冪等結帳不比對請求內容**：同一把鍵搭配不同 body 會回放第一次的結果（ADR-0005）。金額已由伺服器重算，風險被限縮，但應補上 body 指紋。處理：請求主體的 SHA-256 指紋已納入佔位與完成紀錄，內容不同回 422；舊格式紀錄在過期前仍照舊重播。
4. **轉盤的當日標記在資料庫寫入之前寫入**：寫入失敗時標記不回收，使用者會失去當天的機會（ADR-0007）。修正需要把標記與寫入放進同一個可回滾的單位。
5. [已處理 2026-10-17] **結帳的使用者鎖忙碌時回 400，不是 503**：`OrderService.createOrder` 拋出的是 `RuntimeException`，`OrderController` 的 catch 把它轉成 400。應改為 `CustomException("503", …)`，並讓 `OrderController` 不把它吞成 400。處理：忙碌時拋出 `CustomException("503", …)`，`OrderController` 原樣拋出；業務拒絕仍為 400（判斷見 `service/order/CheckoutFailure`）。
6. [已處理 2026-10-22] **購物車的 Redis 鍵沒有過期時間**：`cart:items:*` 與 `carts:active` 沒有清理排程，`carts:active` 目前沒有消費者（見 DATABASE.md 的 Redis 鍵一覽）。處理：每次寫入刷新 30 天的保留期（`RedisCartService.RETENTION`），`carts:active` 寫入時修剪超過保留期的成員；沒有讀取端，過期不影響功能。
7. **團購結帳沒有冪等鍵**：重送不會重複扣款（列鎖與狀態守衛保證），但也不會回放第一次的成功結果，前端需要自行處理重送後的回應。
8. **Redis 沒有環境隔離**：多個環境共用同一台 Redis 時，鍵會互相干擾。正式環境應使用獨立的實例（見 DEPLOYMENT.md）。
9. [已處理 2026-10-17] **`run-all.js` 的失敗名稱靠攔截 `console.log` 取得**：這是脆弱的做法，之後應改為由 harness 直接記錄檢查結果。處理：各套件改由 `scripts/ui/report.js` 登記，`run-all.js` 不再攔截 `console.log`。
10. **上傳目錄 `/uploads/**` 維持 `permitAll`**：上傳端已限制為 png／jpg／jpeg／gif／webp 並以 magic bytes 驗證（M-1 修補），圖片可直接顯示。若將來允許 SVG 或 HTML，必須重新評估儲存型 XSS 風險。

2026-10-16 起，所有未完成事項的狀態（包括本文件以外的項目）統一記在 [`REVIEW_2026-10-13.md`](REVIEW_2026-10-13.md) 的「未完成事項」。

## 未能在本環境驗證的項目

本環境沒有 Maven Central，也沒有 Docker daemon，因此 Java 編譯、`mvn test`、Docker 建置與 UI 流程都只能由 CI 驗證。
程式與測試最後一次變更（commit `cbd7ff0`）；PR #8 頭 `cbd7ff0` 上的完整 CI 執行是 [run 39066084888](https://github.com/Leo0049/projectLab_2/actions/runs/39066084888)：`mvn test`（359 個測試、64 個類別、略過 0）、API 端對端、UI 普掃與四條主線、Docker 建置全部步驟成功，摘要見 README「程式與測試的最後一次 CI 驗證」。之後只改文件與註解的 commit，由 PR 頁面上的最新 run 驗證。CI 失敗的原因與修正歷程仍寫在 PR 說明中。

CI 已執行並通過的項目（不再列為未驗證）：

- Java 編譯與 `mvn test`，包括 `AuthorizationRouteInventoryTest`：白名單與 `SecurityConfig` 的比對在 CI 中一致。
- API 端對端驗證，以及 UI 普掃與四條主線（點餐、轉盤、揪團、搜尋分頁）。
- Docker 映像建置（只建置，不推送、不啟動容器）。
- 還原驗證（2026-10-13）：39 組故意破壞，每一組都讓對應的測試轉紅，對照表見 README「還原驗證」。2026-10-16 另有四組（M1–M4），見 README「2026-10-16 還原驗證（CI）」；2026-10-17 另有六組（M5–M10），見 README「2026-10-17 還原驗證（CI）」；2026-10-18 另有四組（M11–M14），見 README「2026-10-18 還原驗證（CI）」。

仍然只能在 CI 或線上環境驗證，目前沒有覆蓋的項目：

- `IdempotencyService` 的多實例行為：單元測試以 mock 驗證 Redis 路徑與本地退避路徑，沒有跨實例的整合測試。
- `server.forward-headers-strategy: native` 搭配真實反向代理的行為：CI 沒有代理，只能依 Tomcat 文件推論。
- `/api/stores/nearby/v2` 已於 2026-10-17 移除，本項不再適用（`PublicService.requireValidCoordinates` 仍由 `/api/stores/nearby` 使用）。
- 索引是否被 MySQL 實際採用：需要 `EXPLAIN`，CI 目前沒有這個步驟。
- 映像的實際執行（容器內的 `java -jar`、非 root 權限、`frontend/` 路徑）：CI 只確認建置成功。
**效能數字**：本 PR 沒有重新量測。README「效能」一節的數字是修補當時留下的既有紀錄，本文件只引用、不另行宣稱。第 7 項的效果（含新增的 `idx_stores_lat_lng`）需要在 CI 或可重現的環境中以 `EXPLAIN` 與壓測重新量測後，才能視為本次驗證的結果。

## 與既有 PR 的關係

PR #2（`fix/security-review-2026-10-11`）已於 2026-10-12 04:48 UTC 合併到 `main`。本分支原本疊在該分支上，合併後 base 已失效，因此 rebase 到目前的 `main` 上，功能差異只剩 `a870bbb` 這一個 commit；PR #3 的 base 為 `main`。之後的 commit 只處理 CI 的失敗診斷與一個測試注入錯誤。
