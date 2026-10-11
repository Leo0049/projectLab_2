# 2026-10-11 審查修復實作計畫

> **For agentic workers:** 依本計畫逐項實作；每項完成後跑一次離線驗證（見「驗證方式」）。

**Goal:** 修掉 `docs/REVIEW_2026-10-11.md` 所列的 P0／P1／P2 問題：未轉義的店家／顧客資料（儲存型 XSS）、團員取消品項的退款缺陷與列鎖、`sms.mode` 預設 mock 的帳號接管路徑、訂單狀態無白名單、無速率限制、無安全標頭、公開的下架商品端點，以及隨之而來的文件漂移。

**Architecture:** 後端把「規則」抽成可單獨測試的純類別（沿用 `service/order/` 的既有慣例：`ItemHash`、`ItemSpecResolver`、`CouponEligibility`），Service 只負責鎖列與套用結果；所有來自使用者、之後會被前端插進 DOM 的字串，在寫入端做白名單，在輸出端做轉義（縱深防禦）。前端沿用各檔既有的區域 `escapeHtml` 慣例，不新增共用模組以免破壞離線載入順序。

**Tech Stack:** Java 17 / Spring Boot 3.4.3 / Spring Security 6 / JPA / Redis（StringRedisTemplate，不可用時退本地）／Vanilla JS。

## Global Constraints

- 不改動既有 API 的回應格式（`{ code, msg, data }`）與 HTTP 路徑，除本計畫明列的變更。
- 前端一律維持可離線運作（不新增任何 CDN 依賴）。
- 金額相關邏輯不得繞過 `PricingService` / `TransactionRecordService.updateStoreCredit`。
- 註解沿用專案風格：說明「為什麼」與過去的踩雷經驗。

## 驗證方式（本機無 Maven、無 Docker，見審查報告的環境限制）

```bash
# 1) 全量編譯（離線，classpath 取自 ~/.m2）
javac -nowarn -encoding UTF-8 --release 17 -d <out> @<argfile> @<sources>
# 2) 純邏輯測試（JUnit Platform launcher，不需 Spring/DB）
java -cp "<classpath>" RunTests <test classes>
# 3) 前端語法
node --check <each changed js>   # HTML 內的 inline script 另行抽出檢查
```

CI（`mvn test` + e2e-verify + ui/run-all）仍需在有 Maven／Docker 的環境補跑。

---

### Task 1: 圖片網址白名單（後端）

**Files:**
- Create: `src/main/java/com/example/demo/common/SafeImageUrl.java`
- Test: `src/test/java/com/example/demo/common/SafeImageUrlTest.java`
- Modify: `service/StoreService.java`（3 處 `setCoverUrl`）、`service/BrandService.java`（`updateBrandLogo`、`createProduct`、`updateProduct` 的 `logoUrl`）、`service/UserProfileService.java`（`updateMe` 的 `picUrl`）

**Interfaces:**
- Produces: `SafeImageUrl.normalize(String raw)` → 允許 `null`／空字串（回 `null`）；允許站內相對路徑（單一 `/` 開頭、不得 `//`）與 `http(s)://` 絕對網址；含 `"`、`'`、`<`、`>`、空白或控制字元一律丟 `CustomException("400", "圖片網址格式不正確")`。

- [x] 先寫測試：接受 `/uploads/a.png`、`https://res.cloudinary.com/x.png`、`null`；拒絕 `x" onerror="…`、`javascript:alert(1)`、`/uploads/../etc`、`http://a b`.
- [x] 實作 `normalize`。
- [x] 接到三個 Service 的寫入點（覆蓋店家封面、品牌 logo、商品 logo、會員頭像）。
- [x] 跑測試（見「驗證方式」第 2 步）。

### Task 2: 顧客端輸出轉義

**Files:** `frontend/Customer/index.html`、`search_result.html`、`orders.html`、`order_confirm.html`、`profile.html`

- [x] 每頁的 inline script 開頭加區域 `escapeHtml`（沿用 `Brand/js/hq-menu-api.js` 的實作）。
- [x] 轉義店家可控欄位：`coverUrl`／`cover_url`、`storeName`／`store_name`、`brandLogoUrl`、`brandName`、`name`。
- [x] `orders.html` 的 `setAttribute('onclick', …)` 走 JS 字串 context：`escapeHtml` 後再 `replace(/'/g, "\\'")`。
- [x] `order_confirm.html` 的 `logoMap[pid]`、`profile.html` 的優惠券名稱 `c.name` 與 `s.address` 一併轉義。
- [x] `node --check`（含抽出 inline script）確認語法。

### Task 3: 門市後台輸出轉義

**Files:** `frontend/store/js/api-home.js`、`home-order-modals.js`、`api-analytics.js`、`api-home-metrics.js`

- [x] 各檔加區域 `escapeHtml`，套用到 `i.note`、`i.userName`、`p.productName`、`name`、規格組合字串等動態值。
- [x] `node --check`。

### Task 4: 團員取消品項的退款與列鎖（P0/P1）

**Files:**
- Create: `src/main/java/com/example/demo/service/order/MemberCancelSettlement.java`
- Test: `src/test/java/com/example/demo/service/order/MemberCancelSettlementTest.java`
- Modify: `service/GroupOrderService.java#cancelMemberItems`

**Interfaces:**
- Produces:
  ```java
  public final class MemberCancelSettlement {
      public record Item(boolean paid, boolean escrowed, BigDecimal finalPrice, BigDecimal discount) {}
      public record Result(BigDecimal memberRefund, BigDecimal hostRefund,
                           BigDecimal remainingEscrow, BigDecimal amountRemoved) {}
      public static Result settle(List<Item> items, BigDecimal escrowAmount);
  }
  ```
  規則：PAID → 退會員 `max(finalPrice - discount, 0)`；ESCROWED → 退團長 `max(finalPrice - discount, 0)`，但**總額以 `escrowAmount` 為上限**；UNPAID → 完全不動錢。`amountRemoved` = `memberRefund + hostRefund`。

- [x] 先寫測試：`OPEN` 團（escrow 0）的 UNPAID 品項 → 全部 0（現行實作會退錢，即 P0）；ESCROWED 且 escrow 不足 → 以 escrow 為上限；帶券品項 → 用淨額；混合情境。
- [x] 實作 `settle`。
- [x] `cancelMemberItems` 改用 `groupOrderRepository.findByIdForUpdate` ＋ `orderItemRepository.findByGroupOrderIdForUpdate`，再依 `settle` 的結果退錢、更新 `escrowAmount`／`totalAmount`（`max(0)`）、還原券、刪品項。
- [x] 跑測試。

### Task 5: 訂單狀態白名單（P2）

**Files:**
- Create: `src/main/java/com/example/demo/service/order/OrderStatusGuard.java` + Test
- Modify: `service/OrderService.java#updateOrderStatusWithUserCheck`

**Interfaces:** `OrderStatusGuard.requireCustomerTransition(String current, String next)`：只允許 `OPEN|SUBMITTED → CANCELLED` 與 `SUBMITTED → PAID`（舊前端通知用）；其餘丟 `CustomException("400", …)`；狀態 null／未知一律拒絕。

- [x] 先寫測試（含「READY → COMPLETED 必須被拒」「SUBMITTED → OPEN 必須被拒」）。
- [x] 實作並接到 Service。

### Task 6: 設定防呆（P1/P2）

**Files:** `service/AuthService.java`、`common/DemoDataSeeder.java`、`src/main/resources/application.yml`、`application-prod.yml.example`、`.env.prod.example`；Test: `AuthServiceSecurityTest.java` 增測、`common/DemoDataSeederProdGuardTest.java`（純）

- [x] `AuthService` 加 `Environment`，`@PostConstruct` 檢查 prod profile 下 `sms.mode` 必須為 `firebase`（比照 `JwtUtils.assertProductionSecret`，package-private static 以便測試）。
- [x] `verifyFirebaseToken` 的 `MOCK_TOKEN` 分支加上「非 prod profile」條件。
- [x] `DemoDataSeeder` 在 prod profile 下一律不植入（即使 `app.demo-data.enabled=true`），抽出 `shouldSeed(productionProfile, enabled)` 供測試。
- [x] 範例設定檔補上說明。

### Task 7: 登入端點速率限制（P2）

**Files:**
- Create: `src/main/java/com/example/demo/service/LoginAttemptLimiter.java` + Test（`LoginAttemptLimiterTest.java`）
- Modify: `controller/UserController.java`（`/api/auth/login`、`/api/auth/corporate-login`、`/api/auth/reset-password-firebase`、`/api/auth/check-phone`）、`controller/BrandController.java`（`/api/brand-auth/login`）、`controller/StoreController.java`（`/api/stores/auth/login`）

**Interfaces:** `void check(String scope, String identityKey)`；`LoginAttemptLimiter.Result decide(long attempts, long limit, long windowSeconds)` 為純函式（供測試）。以 `ObjectProvider<StringRedisTemplate>` ＋ 本地 Map 退避（比照 `RedisLockService`）。

- [x] 先寫測試：超過門檻擲 `CustomException("429", …)`、視窗過後恢復、不同身分互不影響、Redis 不可用時走本地。
- [x] 實作並接到登入／重設密碼端點。

### Task 8: 安全標頭與 CSP 能力（P2）

**Files:** `SecurityConfig.java`、create `common/SecurityHeadersFilter.java`、`application.yml`、`application-prod.yml.example`、`docs/DEPLOYMENT.md`

- [x] Spring Security 加上 `X-Content-Type-Options: nosniff`、`frame-ancestors 'none'`（＋`X-Frame-Options: DENY`）、`Referrer-Policy`、`Permissions-Policy`、HSTS（僅 HTTPS 回應）。
- [x] CSP 以 `app.security.csp.enabled`（預設 **false**）＋ `app.security.csp.policy` 提供，由小型 filter 加上；預設政策寫在 `application.yml` 註解與 DEPLOYMENT.md。
- [x] DEPLOYMENT.md 補上：頁面若由 nginx／Caddy 直接提供，需在該層設同樣標頭；並說明「要收緊 `script-src` 必須先把 inline event handler 外部化」。

### Task 9: 移除公開的下架商品端點（P3）

**Files:** `controller/ProductController.java`（移除 `GET /api/products/store/{storeId}/all`）、`SecurityConfig.java`（收窄 matcher）、`API.md`

- [x] 移除端點（前端無任何呼叫者；`getAllProductsByStoreId` 僅此端點使用，一併移除）。

**與原報告不同的地方（以程式碼為準）**：`ProductRepository.findByStoreId` 的 JPQL 已固定 `AND p.isEnabled = true`，
兩個端點回的是同一份「上架中」清單，**並沒有回傳下架商品**——`API.md` 原本寫的「含下架（分店所有飲品）」才是錯的。
因此這裡修的是「同義的公開端點重複暴露」而不是資訊洩漏；`SecurityConfig` 的 matcher 是共用的 `"/api/products/store/**"`（不能整條刪），
改收窄為 `"/api/products/store/*"`，只剩下那一個公開端點。

### Task 10: 文件同步

**Files:** `API.md`（移除已刪除的 debug 端點列、狀態值改 `active/closed`）、`CLAUDE.md`（測試數不一致、新增本輪修復註記）、`controller/StoreController.java`（javadoc 狀態值）、本計畫的驗證結果段

- [x] 逐項更新。

---

## 實際執行結果（2026-10-11）

### 超出原計畫、但同一類風險而順手補的

| 項目 | 位置 | 為什麼在原計畫之外 |
|------|------|--------------------|
| 顧客端再補 2 頁轉義 | `Customer/group_order.html`、`Customer/order_details.html` | 原報告只列了 5 頁，但這兩頁同樣把店名、品名、加料、成員姓名插進 `innerHTML`；`group_order.html` 更是把其他團員的資料顯示給你看（跨帳號儲存型 XSS），不補等於 X-1 只補一半 |
| 門市後台再補 1 支 | `store/js/order-all-sync.js`（品名、訂單號） | 原報告只列 4 支；這支已有 `esc()`，但訂單摘要的 `firstName` 與訂單號還是原字串 |
| 首頁其他 sink | `Customer/index.html` 的進行中訂單、已完成訂單、優惠券、轉盤品牌列表 | 原報告只標了店家卡（865/868/894），同頁其他區塊也是同一條路徑 |
| `PUT /api/stores/status` 白名單 | `service/StoreService.updateStatus` | 原報告把「不檢查狀態值」列在 X-1 的攻擊鏈裡（店家自行改成 `active` 就上首頁），但沒有對應的修復項 |
| `onclick="…"` 的 JS 字串改事件代理 | `Customer/orders.html`、`Customer/index.html` | 原計畫寫「`escapeHtml` 後再把單引號換成跳脫寫法」，但那只處理單引號：值結尾是反斜線時跳脫字元會被吃掉而脫出字串。改用 `addEventListener` / `data-*`，資料完全不進程式碼字串 |
| `scripts/ui/check-inline-scripts.js` | 新檔 | 本專案主邏輯都寫在 HTML 的 inline script 裡，而原本只有 `node --check` 掃 `.js`，改了 HTML 裡的程式碼沒有任何工具能驗語法 |

### 離線驗證（實跑）

| 項目 | 指令 | 結果 |
|------|------|------|
| 主程式全量編譯 | `javac -nowarn -encoding UTF-8 --release 17 -d <out> @args @srcs`（classpath 來自 `~/.m2`）| ✅ 151 檔 → 189 個 class，`exit=0` |
| 測試全量編譯 | 同上，加 `src/test`（27 檔）| ✅ `exit=0` |
| 純邏輯測試 | JUnit Platform launcher（`RunTests`）跑 11 支測試類別 | ✅ **FOUND=64 PASS=64 FAIL=0**（新增：`MemberCancelSettlementTest` 9、`OrderStatusGuardTest` 5、`SafeImageUrlTest` 7、`AuthServiceSmsModeGuardTest` 6、`DemoDataSeederProdGuardTest` 2、`LoginAttemptLimiterTest` 8）|
| 前端 JS 語法 | 逐檔 `node --check`（不含 `vendor/`）| ✅ 71 檔全通過 |
| inline script 語法 | `node scripts/ui/check-inline-scripts.js` | ✅ 39 個 HTML / 69 段 inline script 全通過 |

### 驗證過程中抓到並修掉的 3 個真問題

這些都是「不編譯／不打測試就看不到」的那一類，寫在這裡提醒後續不要拆掉：

1. **`SecurityConfig` 的 `permissionsPolicy()` 不能串在 header 鏈中間**——它回傳的是 `PermissionsPolicyConfig`
   而不是 `HeadersConfigurer`，後面接 `httpStrictTransportSecurity()` 會直接編譯失敗（`javac`：cannot find symbol）。
   現在它是 lambda 的最後一個呼叫，註解也寫在上面。
2. **`ObjectProvider` 的匿名實作要覆寫 `getObject(Object...)`**（Spring 6.2 的介面抽象方法），否則 `LoginAttemptLimiterTest` 編不過。
3. **`LoginAttemptLimiterTest` 的假計數器一開始全體共用一個 `AtomicLong`**，於是「不同身分／不同 scope 互不影響」兩個測試必紅——
   那時壞的是假物件（沒照 key 分開算），改了假物件才能真的驗到 key 有分開。

### 仍需在有 Maven／Docker／瀏覽器的環境補跑

- `mvn test`（含 `@SpringBootTest`：`AuthorizationTest`、`WalletConcurrencyTest`、`GroupCheckoutConcurrencyTest`、`RatingConcurrencyTest`），以及 `scripts/e2e-verify.js`、`scripts/ui/run-all.js`。
- **X-1／X-2 沒有在真瀏覽器裡重現**：本機沒有可跑的瀏覽器與乾淨的 MySQL；目前依靠「後端寫入端白名單（`SafeImageUrlTest`）+ 輸出端轉義（靜態掃描）+ 語法檢查」三層，
  建議在 CI 以 Playwright 補一個「把 `storeName` 設成 `<img src=x onerror=…>`，斷言頁面沒有執行」的回歸測試。

---

## 不做（附理由）

- **頁面層 CSP（`<meta http-equiv>`）**：本專案所有互動都靠 inline event handler，`script-src` 必須含 `'unsafe-inline'`，對 XSS 幾乎沒有攔阻效果，卻有實際破壞頁面的風險（地圖圖磚、Cloudinary 圖片、Firebase、WS 都要逐一加白名單），且本機沒有可跑的瀏覽器能驗證。改以「伺服器標頭硬化 ＋ 可設定的 CSP 開關 ＋ 文件說明」處理。
- **`/uploads/**` 的 CSP／nosniff 之外的內容驗證**：已在既有上傳流程處理（magic bytes）。
