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
| 即時推播 | WebSocket/STOMP（`/ws-cart`, SockJS）|
| 圖片上傳 | Cloudinary v1.36.0（無憑證時自動改存本機 `uploads/`）|
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
│   ├── common/              # JWT, Security, Cloudinary, Firebase, DataSeeder, DemoDataSeeder
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

## 測試

`mvn test`（需先 `docker compose up -d`，測試會連本機 MySQL）：

| 測試 | 守住的東西 |
|------|-----------|
| `AuthorizationTest`（13） | 未認證商品寫入、跨帳號讀寫個資／錢包／訂單、偽造參數與「不帶參數」兩種繞過、debug 與門市傾印端點已移除 |
| `ItemSpecResolverTest`（7） | 固定規格防竄改（見下方「品項規則抽在 service/order/」）|
| `ItemHashTest`（6） | 品項識別碼：配料順序不影響合併、套券的那杯要拆開 |
| `CouponEligibilityTest`（6） | 優惠券適用範圍、已付款不可套券 |
| `ImageStorageServiceTest`（4） | 無 Cloudinary 憑證時改走本機儲存、同 publicId 覆寫、可疑副檔名正規化 |
| `WalletConcurrencyTest`（3） | 併發儲值不可短少、帳本與餘額必須相符、併發扣款不可透支、列鎖不被一級快取架空 |
| `GroupCheckoutConcurrencyTest`（6） | 揪團四條金流路徑重複觸發時只能發生一次；同一張券不可用兩次 |
| `DemoApplicationTests`（1） | Spring context 能否載入 |

`ItemSpecResolverTest` / `ItemHashTest` / `CouponEligibilityTest` 在 `service/order/` 底下，
是**不載入 Spring context** 的純邏輯測試（合計 0.05 秒）。
新增純規則時請放在那裡，不要為了測一條規則去啟整個 context。

> 以上每一支都已驗證「把修補改回舊寫法時會失敗」——`AuthorizationTest` 在還原 IDOR
> 時 3 個、還原 S-6 時 2 個轉紅，`WalletConcurrencyTest` 改回 `findById` 時 2 個轉紅，
> `ItemSpecResolverTest` 把防竄改改回「照單全收」時 4 個轉紅，
> `GroupCheckoutConcurrencyTest` 拿掉品項列鎖或結帳狀態守衛時各 1 個轉紅。
> 新增測試時請照做，在修補前後各跑一次，確認它真的抓得到回歸，否則只是裝飾。

### 另外兩層驗證（都在 CI 上跑）

`mvn test` 抓不到「服務跑起來才會現形」的問題，所以還有：

```bash
cd scripts && npm install
node e2e-verify.js      # API 端對端，67 項斷言
node ui/run-all.js      # 30 頁普掃 + 點餐／轉盤／揪團三條主線（Playwright）
```

`.github/workflows/ci.yml` 會依序跑完這三層。**改完動到序列化、交易邊界或前端流程的
程式碼後，請至少跑一次第二三層**——九次 `LazyInitializationException` 裡有兩次
（下單完成頁、揪團成員清單）是單獨 curl 端點會過、實際走流程才炸的。

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

`GroupCheckoutConcurrencyTest`（5 個）守住這四條。

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

已經在**五個地方**踩過同一顆雷，兩種型態：

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
   本機 `uploads/`（以 `/uploads/**` 提供），因此不需任何第三方帳號即可完整展示
5. 本機 `sms.mode=mock`，跳過 Firebase 驗證，不需要 `serviceAccountKey.json`
6. 啟動埠：`8082`；Swagger UI：`http://localhost:8082/swagger-ui.html`

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
`WebConfig` 也曾有第三份 `addCorsMappings("/**").allowedOrigins("*")`，被 Spring Security
的 CORS filter 遮蔽而未生效，但同樣自相矛盾，已移除。

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
- **即時推播**：WebSocket/STOMP 推送訂單狀態與揪團品項異動（非 SSE，見上方說明）
- **圖片儲存**：一律走 `ImageStorageService`，**不要直接呼叫 `cloudinary.uploader()`**。
  有憑證上傳 Cloudinary，沒有則存本機 `uploads/` 並以 `/uploads/**` 提供，
  讓專案不需任何第三方帳號即可完整展示
- **GroupOrder V2**：完整揪團流程（品項 CRUD、套用優惠券、結帳、補款）
- **常用地址管理**：使用者可儲存多筆常用地址（`user_addresses`），結帳時快速選用

## 重要注意事項

- `application.yml` 已全面改為 `${ENV_VAR:預設值}`，**不要把明文密鑰寫回去**
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
