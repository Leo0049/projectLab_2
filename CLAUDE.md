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
| 社群登入 | Firebase Admin SDK 9.2.0 (Google, Facebook, Phone) |
| 資料庫 | MySQL 8（本機 Docker，`docker-compose.yml`）|
| 快取 / 鎖 | Redis 7（本機 Docker）— 揪團購物車、分散式鎖、每日轉盤狀態 |
| 即時推播 | SSE + WebSocket/STOMP（`/ws-cart`, SockJS）|
| 圖片上傳 | Cloudinary v1.36.0 |
| 圖片生成 | Java 2D (優惠券動態圖片生成) |
| API 文件 | SpringDoc OpenAPI / Swagger UI v2.8.5 |
| 建置工具 | Maven（**注意：repo 內沒有 `mvnw` 腳本，請用系統 `mvn`**）|
| 其他 | Lombok, TestNG |
| 前端 | Vanilla JavaScript + Tailwind CSS (無框架) |
| 前端 HTTP | Fetch API + Bearer JWT from localStorage |

## 專案結構

```
join_drink/
├── src/main/java/com/example/demo/
│   ├── controller/          # 14 REST controllers
│   ├── service/             # 24 業務邏輯 services
│   ├── entity/              # 26 JPA entities + 6 EmbeddedId 類別
│   ├── repository/          # 29 JPA repositories
│   ├── dto/                 # 24 Data Transfer Objects
│   ├── common/              # JWT, Security, Cloudinary, Firebase, DataSeeder
│   ├── config/              # RedisConfig, WebSocketConfig
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

> **沒有 `SseController`**。SSE 端點（`SseEmitter`）直接寫在 `GroupOrderController`、
> `StoreController`、`OrderController` 內，找即時推播相關程式碼請往這三支看。

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

## 錢包/支付邏輯

- `TOPUP`：儲值
- `ESCROW`：下單時凍結金額
- `FINAL_PAY`：完成後實扣
- `REFUND`：取消/拒絕後退款
- `REPAYMENT`：補款（揪團補差額）
- 支付方式：`WALLET`（餘額）或 `CASH`

### ⚠️ 餘額異動必須鎖列

所有金流都經由 `TransactionRecordService.updateStoreCredit()`（GroupOrderService 有 10 處
呼叫）與 `UserProfileService.topUp()`，兩者都必須用
`UserRepository.findByIdForUpdate()`（`SELECT ... FOR UPDATE`），**不可改回 `findById`**。

餘額是「讀出 → 相加 → 寫回」，沒有列鎖時併發請求會互相覆蓋。實測 20 個併發各儲值 10 元，
最終只入帳 70 元，且 `transaction_records` 總額（120）與 `users.balance`（70）對不起來——
對含金流的系統代表無法對帳。

`WalletConcurrencyTest` 會擋住這個回歸（已驗證：改回 `findById` 時兩個測試都會失敗）。
新增任何動到 `balance` 的流程時，一律走上述兩個方法，不要自己讀寫餘額。

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
3. Cloudinary 憑證需自行填入 `.env` 或 `application-local.yml`（參考各自的 `.example`）
4. 本機 `sms.mode=mock`，跳過 Firebase 驗證，不需要 `serviceAccountKey.json`
5. 啟動埠：`8082`；Swagger UI：`http://localhost:8082/swagger-ui.html`

設定值一律走 `${ENV_VAR:本機預設值}`，`application.yml` 內不再有任何明文密鑰。

## CORS 白名單

由 `app.cors.allowed-origins`（`application.yml`）單一來源提供，
`SecurityConfig`（HTTP）與 `WebSocketConfig`（STOMP handshake）**共用同一份**，
可用環境變數 `CORS_ALLOWED_ORIGINS` 覆寫：

- `localhost:5173`
- `localhost:5500`
- `127.0.0.1:5500`
- `localhost:60687`
- `localhost:54376`
- `localhost:63342`
- `localhost:8082`

⚠️ 曾因 WebSocket 端獨立寫成 `setAllowedOriginPatterns("*")`，
與 HTTP 端白名單自相矛盾而完全對外放行。新增來源請只改設定檔，不要在程式碼中另寫一份。

## WebSocket / STOMP 安全

`/ws-cart` 的 SockJS handshake 走 CORS 白名單；STOMP 層由
`WebSocketConfig.configureClientInboundChannel` 攔截：

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
- **SSE 即時推播**：分店接單畫面即時收到新訂單通知；揪團成員可即時看到品項更新
- **GroupOrder V2**：完整揪團流程（品項 CRUD、套用優惠券、結帳、補款）
- **常用地址管理**：使用者可儲存多筆常用地址（`user_addresses`），結帳時快速選用

## 重要注意事項

- `application.yml` 已全面改為 `${ENV_VAR:預設值}`，**不要把明文密鑰寫回去**
- 舊版設定檔曾以明文寫入 DB 密碼、JWT secret 與 Cloudinary api-secret，應視為已外洩，需 rotate
- `serviceAccountKey.json` 與 `application-local.yml` 已在 `.gitignore`
- DB DDL：`spring.jpa.hibernate.ddl-auto`（本機 `update`，production 需改 `validate`/`none`）
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
