# JoinDrink

多租戶飲料訂購與**揪團**平台。顧客可以自己下單，也可以開一個揪團連結讓同事朋友各自加點、由團長統一結帳；
品牌總部管理菜單與跨店財務，門市端處理接單與庫存。

**Spring Boot 3.4.3 / Java 17 · MySQL 8 · Redis 7 · WebSocket(STOMP) · JWT**

---

## 30 秒把它跑起來

```bash
docker compose up -d      # MySQL 8 + Redis 7
mvn spring-boot:run       # 服務起在 :8082
```

不需要任何額外設定：`application.yml` 每一項都有對應本機 Docker 的預設值，
首次啟動會自動建表並植入**示範資料**（2 個品牌、3 間門市、6 款飲品、1 個顧客帳號，
以及 7 筆橫跨待處理／製作中／配送中／待取餐／已完成的訂單，讓門市後台一開就有東西可看）。

開 http://localhost:8082/swagger-ui.html 看 API，或用下列帳號登入前端頁面
（`frontend/` 為靜態頁，用 VS Code Live Server 之類的工具開啟即可）：

| 角色 | 帳號 | 密碼 |
|------|------|------|
| 顧客 | `0912000000` | `demo1234` |
| 品牌總部 | `demo_brand` | `demo1234` |
| 門市 | `demo_store` | `demo1234` |

> 示範資料只在資料表為空時植入，不會覆蓋既有資料；正式環境請設 `DEMO_DATA_ENABLED=false`。

---

## 功能範圍

| 模組 | 內容 |
|------|------|
| **揪團訂單** | 建立揪團 → 產生分享 token → 成員各自加點 → 團長統一送出、補款/退款 |
| **錢包與帳本** | 儲值、下單託管（ESCROW）、完成實扣（FINAL_PAY）、取消退款（REFUND）、補款（REPAYMENT） |
| **多租戶菜單** | 品牌層級的飲品模板、甜度/冰量/杯型規格、加料設定，門市可覆蓋供應狀態 |
| **區域定價** | 同品牌不同地區可設定各自的分類加價 |
| **即時推播** | WebSocket/STOMP 推送訂單狀態與揪團品項異動 |
| **優惠券轉盤** | 每日抽獎，優惠券圖片由 Java 2D 動態生成後上傳 Cloudinary |
| **商品快照** | 下單時保存當下品名與價格，日後改價不影響歷史訂單 |

規模：後端 138 個檔案 / 約 14.4k 行，26 張資料表，14 個 REST controller；前端 39 個頁面、約 17.8k 行 JS（Vanilla JS + Tailwind）。

---

## 畫面

以下都是照著「30 秒把它跑起來」啟動後的實際畫面，資料來自內建示範資料，沒有另外做假圖。

| 顧客首頁 | 附近店家 | 店家與菜單 |
|---|---|---|
| ![顧客首頁](docs/screenshots/customer-home.png) | ![附近店家](docs/screenshots/customer-nearby.png) | ![店家與菜單](docs/screenshots/customer-store.png) |
| 進行中訂單、活動輪播、每日轉盤入口 | 依距離排序，含評分、外送門檻與營業時間 | 店家資訊與分類菜單 |

| 品牌總部 — 菜單管理 | 門市後台 — 訂單管理 |
|---|---|
| ![品牌菜單](docs/screenshots/brand-menu.png) | ![門市訂單](docs/screenshots/store-orders.png) |
| 分類與飲品排序、上下架、售價維護 | 依狀態分區，接單／拒單／製作完成的完整流轉 |

> 店家與飲品圖片是專案內建的 SVG 示意圖，不依賴任何外部圖床，離線也能完整展示。

---

## 架構

```
Customer / Brand / Store 三種前台 (Vanilla JS)
                │  Bearer JWT
                ▼
      ┌──────────────────────┐
      │  Spring Security 6   │  JwtAuthenticationFilter → currentUserId
      │  + STOMP 攔截器       │  CONNECT 驗 JWT、SUBSCRIBE 驗訂單關係人
      └──────────┬───────────┘
                 ▼
   14 Controllers → 24 Services → 29 Repositories
                 │                      │
                 ▼                      ▼
          Redis 7                   MySQL 8
   揪團購物車 / 分散式鎖 / 轉盤狀態      26 張表（JPA Entity 為 schema 唯一事實來源）
```

三種角色 `CUSTOMER` / `BRAND` / `STORE`，授權規則集中在 `SecurityConfig`。

---

## 安全稽核與修補

專案完成後對**執行中的系統**做了一輪黑箱與白箱並行的稽核，
所有問題都以實際請求重現、並用資料庫狀態確認，修補後再重跑同一組驗證。

| # | 問題 | 實測影響 | 修補 |
|---|------|---------|------|
| S-1 | `POST /api/products` 落在整段 `permitAll`，且直接綁定 JPA 實體 | 未登入即可新增商品；因 `save()` 的 merge 語意帶入既有 `id` 可竄改他人商品，**實測把售價 65.00 改成 0.01** | 移除該冗餘端點；`permitAll` 一律改為只放行 `HttpMethod.GET` 的具體路徑 |
| S-2 | 擁有權檢查寫成 `if (authUserId != null && ...)`，而 `authUserId` 是用戶端自送的選填參數 | 不帶該參數即可跳過檢查，**實測把他人餘額改成 99999** | 改用 filter 注入的 `currentUserId`，10 個端點統一走 `requireSelf()` |
| S-3 | 同一成因造成一批讀取端點缺少擁有權檢查 | 可讀取他人姓名、手機號碼與餘額 | 同上 |
| S-4 | 兩個 debug 端點位於 `permitAll` 路徑下 | 未認證即可取得他人手機號碼；另一支會回傳完整 stack trace | 移除 |
| S-5 | WebSocket 為 `allowedOriginPatterns("*")` 且無任何 STOMP 攔截器 | `orderId` 是連續整數可列舉，任何人可旁觀他人訂單狀態 | CONNECT 驗 JWT、SUBSCRIBE 驗訂單關係人；origin 收斂為與 HTTP 共用的白名單 |
| S-6 | `OrderController` 整支沒有任何擁有權檢查：訂單列表吃路徑上的 `userId`，狀態／取消端點則是 `if (userId != null) 檢查 else 放行` | 任一登入顧客可讀他人完整訂單歷史（金額、品項、揪團 `shareToken`）；**不帶 `userId` 參數即可把他人訂單從 READY 改成 COMPLETED**；`/api/orders/store/{id}` 還能撈出整間門市訂單，含其他顧客的外送地址 | 全部改用 filter 注入的 `currentUserId`，移除「不帶參數就跳過」的分支；門市傾印端點刪除（門市請用有分頁、有 STORE 權限的 `/api/stores/orders`） |
| D-1 | 餘額為「讀出→相加→寫回」且無列鎖 | 20 個併發各儲值 10 元，**最終只入帳 70 元，且帳本總額 120 與餘額 70 對不起來** | 改用 `SELECT ... FOR UPDATE`；所有金流都收斂在 `updateStoreCredit()` 一個進入點 |

另外修掉幾個會直接影響可用性的問題：

- **登入必定 500**：`signWith(alg, String)` 會將 secret 做 Base64 解碼，預設值解碼後只剩 416 bits，不符 HS512 要求。改用原始位元組建立金鑰，並把長度檢查移到啟動時，不足即啟動失敗。
- **`jwt.expiration` 完全不生效**：`JwtUtils` 寫死 7 天，設定檔的 24h 從未套用。
- **用戶端錯誤一律回 500**：路由不存在、缺參數、型別錯全部落入 catch-all；且原始例外訊息（含 DB 結構）會回傳給前端。現已分流為 404/400/405，內部細節只寫入 log。
- **`open-in-view: false` 之下的固定 500**：關閉 OSIV 後，交易外碰到 LAZY 關聯就會拋 `LazyInitializationException`。這類錯誤在單元測試看不到、要實際打端點才會現形，前後共出現在五個地方——其中 `GET /api/stores/settings/profile` 是門市後台**每一頁的頁首**都會呼叫的，壞掉時整個後台的營業狀態永遠停在「讀取中…」。另兩個公開端點直接回傳 JPA entity，Jackson 序列化 `brand` / `region` proxy 時必定 500。修法統一為「在交易內轉成純 Map 再回傳」，並用一支涵蓋 45 個 GET 端點的普掃確認歸零。

> 這輪普掃也順手抓到前端的一個對稱問題：門市訂單頁用 `''` 當「上次資料快照」的初始值，
> 而空清單算出來的快照剛好也是 `''`，於是**零筆訂單時第一次載入會被判定成「資料沒變」而不重繪**，
> 六個區塊永遠停在「載入中…」——剛 clone 下來、還沒有任何訂單的人看到的就是這個畫面。

> 稽核與修補過程使用 Claude Code 協助進行。

---

## 測試

```bash
docker compose up -d && mvn test
```

| 測試 | 守住的東西 |
|------|-----------|
| `AuthorizationTest`（13） | 未認證寫商品、跨帳號讀寫個資／錢包／訂單、偽造參數與「不帶參數」兩種繞過、debug 與傾印端點已移除、本人存取仍正常 |
| `WalletConcurrencyTest`（2） | 併發儲值不短少、帳本與餘額相符、併發扣款不透支 |
| `ImageStorageServiceTest`（4） | 無 Cloudinary 憑證時改走本機儲存、同 id 覆寫、可疑副檔名正規化 |
| `DemoApplicationTests`（1） | Spring context 載入 |

測試不多，但都對準真正會出事的地方（金流與授權），而且**每一支都驗證過「把修補改回舊寫法時會失敗」**——
授權測試在還原 IDOR 邏輯時 3 個失敗、還原 S-6 時 2 個失敗，錢包測試在改回 `findById` 時 2 個失敗。
只在修補後跑一次通過的測試，證明不了它擋得住回歸。

另外有一支端對端驗證腳本，直接打真實服務走完每條主要流程：

```bash
# 服務跑起來之後
node scripts/e2e-verify.js
```

涵蓋 12 個面向、67 項斷言：三種角色認證、公開瀏覽、錢包與帳本、購物車、
訂單全生命週期（下單→接單→製作→完成，含金流驗證）、拒單退款、揪團、轉盤、
收藏／地址、授權防護、WebSocket 授權、三種後台端點與分頁行為。
（WebSocket 那一節需要 `npm i ws`，沒裝會自動略過，其餘照跑。）

**S-6 與另外三個固定 500 都是跑這支時發現的**——它們有個共同點：單元測試看不到，
要真的把服務跑起來、照著使用者的路徑走一遍才會現形。

再往下一層，用 Playwright 實際操作前端（點按鈕、選規格、兩個瀏覽器分飾團長與團員）
跑通三條主線後，又抓到六個只有真的點下去才會出現的問題：

| 問題 | 症狀 |
|------|------|
| `OrderService.getOrderByIdAndUserId` 用 `findById` 取單 | 下單成功後跳到訂單完成頁，**該頁固定 500** |
| `GroupOrderService` 把 entity 回給 Controller 才轉 DTO | 揪團的訂單完成頁固定 500 |
| `cart.js` 在 `nav-auth.js` 之前就發第一次同步 | API 位址組錯，打到靜態伺服器（404），購物車要等 3 秒輪詢才補正 |
| `checkout.html` 兩處寫成相對路徑 `fetch('/api/...')` | 結帳頁的姓名／電話與門市設定**永遠讀不到** |
| `lastLocalManualActionTime` 從未宣告 | 結帳頁每次背景同步都丟 ReferenceError，處理器中斷 |
| QR Code 元件載入失敗會讓整個 try 中斷 | 揪團**其實已建立成功**，畫面卻顯示「建立揪團失敗」 |

順手把首頁三張輪播橫幅重新壓縮（同解析度、mozjpeg q82）：**2,237 KB → 334 KB，減少 85%**。

---

## 效能

### 索引

依實際查詢補了 8 個複合索引（原本 26 張表的 `@Index` 定義為 0，只有 FK 自動索引）。
以 5000 筆訂單實測 `EXPLAIN`：

| 查詢 | 修補前 | 修補後 |
|------|--------|--------|
| 門市依狀態查訂單 | 全表掃描 5043 列 | `idx_orders_store_status`，500 列 |
| 顧客訂單歷史（含排序） | 全表掃描 + filesort | 索引倒序掃描，**filesort 消除** |

### 壓測發現的問題：訂單列表沒有分頁

灌入 2 萬筆訂單後壓測，`GET /api/stores/orders` 比其他端點慢 50～60 倍。
原因不是查詢慢（單次請求僅 0.14 秒），而是它**一次回傳該門市的全部歷史訂單**——
實測單一回應 6,666 筆、2.1 MB，50 併發下等於同時序列化上百 MB JSON。
這是功能測試看不出來、只有壓測才會浮現的問題：門市營運一年後此端點會直接不可用。

修補：查詢改為 `Pageable`，端點加上 `page` / `size`（預設 50、上限 200）並回傳
`total` / `totalPages` / `hasNext`。同一組壓測條件下（50 併發，60 秒暖身至穩態）：

| 指標 | 修補前 | 修補後 | 改善 |
|------|--------|--------|------|
| 回應大小 | 2,239,488 bytes | 16,955 bytes | **132×** |
| 吞吐量 | 16.9 req/s | 116.8 req/s | **6.9×** |
| p50 延遲 | 2,715 ms | 429 ms | **6.3×** |
| p99 延遲 | 10,375 ms | 907 ms | **11.4×** |

### 併發正確性

以 JMeter 5.6.3（200 執行緒、持續 60 秒）驗證錢包的列鎖修補（見上方 D-1）：

| 情境 | 請求數 | 錯誤率 | 一致性 |
|------|--------|--------|--------|
| 全部打**同一列**（鎖競爭最大） | 12,005 | 0% | 成功數 = 帳本筆數 = 帳本總額 = 餘額 |
| 分散到 **50 列** | 27,770 | 0% | 同上，且 50 個帳號分佈均勻（554～557 筆） |

四方數字完全吻合，**零遺失更新**。分散 50 列的吞吐是同一列的 **2.33 倍**，
印證 `SELECT ... FOR UPDATE` 只序列化同一列、不是全域鎖。

> 絕對吞吐量數字未列出：壓測程式與應用、MySQL、Redis 共用同一台 4 核機器，
> 同一設定連跑三次的偏離達 60%（以 60 秒暖身讓 JIT 收斂後才降到 14%）。
> 這種條件下的絕對值沒有參考價值，上表只取**同條件前後對照**與**相對關係**。

---

## 已知技術債

刻意未處理，列在這裡是因為知道它們存在、也知道代價：

- `GroupOrderService` 1384 行、`BrandService` 1165 行，單一方法最長 147 行——難以單元測試，是補測試的實務障礙
- 40 處以 `Map<String, Object>` 作為 API 回傳型別，失去編譯期型別檢查，Swagger 也無法描述結構
- V1／V2 端點並存，同一資源有多套格式
- 輸入驗證目前只覆蓋金流與訂單路徑，其餘 DTO 尚未補齊

---

## 設定與部署

所有設定走 `${ENV_VAR:本機預設值}`，`application.yml` 不含任何明文密鑰。

| 檔案 | 用途 | 進 git？ |
|------|------|---------|
| `.env` / `application-local.yml` | 本機憑證 | ✗（已 gitignore） |
| `src/main/resources/serviceAccountKey.json` | Firebase 金鑰 | ✗（已 gitignore） |
| `.env.example` / `application-local.yml.example` | 範本 | ✓ |

本機預設 `SMS_MODE=mock`，跳過 Firebase 手機驗證，**不需要金鑰即可註冊登入**。

圖片上傳同理：有設定 Cloudinary 憑證就上傳雲端，沒有就自動存到本機 `uploads/`
並以 `/uploads/**` 提供。**整個專案不需要任何第三方帳號即可完整展示。**

**上線前務必調整：**

- `JWT_SECRET` 換成自己的隨機字串（**至少 64 字元**，不足會啟動失敗）
- `JPA_DDL_AUTO` 改為 `validate` 或 `none`
- `DEMO_DATA_ENABLED=false`
- `CORS_ALLOWED_ORIGINS` 換成正式網域；Redis 以 `REDIS_PASSWORD` 加上密碼

---

## 其他文件

- [API.md](./API.md) — REST API 端點文件
- [DATABASE.md](./DATABASE.md) — 26 張表 schema 說明
- [CLAUDE.md](./CLAUDE.md) — 開發約定與踩過的坑（授權規則、餘額鎖列、STOMP 授權等）

> Schema 的唯一事實來源是 `src/main/java/com/example/demo/entity/` 下的 JPA Entity。
