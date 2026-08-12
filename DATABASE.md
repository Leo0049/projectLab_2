# JoinDrink 資料庫設計文件 (v4.2)

> **最後更新**：2026-08-12
> **資料庫**：MySQL 8.0（本機 Docker，見 `docker-compose.yml`）
> **Schema 來源**：`ddl-auto: update` 由 `entity/` 下的 JPA Entity 自動建立
> **命名規範**：`snake_case`（DB）/ `camelCase`（Java Entity）
> **ORM**：Spring Data JPA（Hibernate）

---

## 📑 資料表總覽

| # | 資料表 | Entity 類別 | 說明 |
|---|--------|------------|------|
| 1 | `regions` | Region | 地區主檔（北部/中部/南部） |
| 2 | `spec_master` | SpecMaster | 全平台規格主檔（甜度/冰塊/杯型）|
| 3 | `topping_master` | ToppingMaster | 全平台配料主檔 |
| 4 | `brands` | Brand | 品牌帳號 |
| 5 | `brand_spec_setting` | BrandSpecSetting | 品牌啟用的規格（含排序、自訂名稱）|
| 6 | `brand_topping_setting` | BrandToppingSetting | 品牌啟用的配料（含自訂名稱與價格）|
| 7 | `brand_region_category_pricing` | BrandRegionCategoryPricing | 品牌各地區分類加價設定 |
| 8 | `product_categories` | MenuCategory | 飲品分類（對應 Entity 名 MenuCategory）|
| 9 | `product_templates` | ProductTemplate | 飲品模板（品牌層級）|
| 10 | `product_spec_relations` | ProductSpecRelation | 飲品支援的規格關聯（含單杯規格加價）|
| 11 | `product_topping_rule` | ProductToppingRule | 飲品可加的配料規則 |
| 12 | `stores` | Store | 分店帳號與基本資料 |
| 13 | `store_product_settings` | StoreProductStatus | 分店飲品供應狀態（isEnabled）|
| 14 | `store_spec_settings` | StoreSpecSetting | 分店規格啟用覆蓋設定 |
| 15 | `store_topping_settings` | StoreToppingStatus | 分店配料售罄狀態 |
| 16 | `users` | User | 使用者帳號 |
| 17 | `user_auth_providers` | UserAuthProvider | 第三方登入綁定 |
| 18 | `user_addresses` | UserAddress | 使用者常用地址（可多筆，支援預設）|
| 19 | `user_favorites` | UserFavorite | 收藏店家 |
| 20 | `user_coupons` | UserCoupon | 使用者持有的優惠券（完整紀錄）|
| 21 | `transaction_records` | TransactionRecord | 錢包交易紀錄 |
| 22 | `orders` | GroupOrder | 訂單主表（個人 SOLO / 揪團 GROUP）|
| 23 | `order_items` | OrderItem | 訂單品項明細 |
| 24 | `order_item_toppings` | OrderItemTopping | 訂單品項配料快照 |
| 25 | `order_ratings` | OrderRating | 訂單星級評分 |
| 26 | `cart_items` | CartItem | 購物車暫存 |

> ⚠️ **EmbeddedId 類別**（非獨立表格，為複合主鍵輔助類）：
> `OrderItemToppingId`、`ProductSpecRelationId`、`ProductToppingRuleId`、
> `StoreProductStatusId`、`StoreSpecSettingId`、`StoreToppingStatusId`

---

## 📂 資料表詳細定義

### 1. regions 地區
> 平台地區主檔，用於區域定價與分店所在地分類。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | 識別碼 |
| name | VARCHAR(20) NOT NULL | 北部 / 中部 / 南部 |

---

### 2. spec_master 規格主檔
> 平台統一定義，品牌透過 `brand_spec_setting` 啟用後才對顧客顯示。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| type | VARCHAR(20) | SWEETNESS（甜度）/ ICE（冰量）/ SIZE（杯型）|
| name | VARCHAR(50) NOT NULL | 平台預設名稱（如：微糖、去冰）|

---

### 3. topping_master 配料主檔
> 平台統一配料庫，品牌透過 `brand_topping_setting` 選用並可自訂名稱與價格。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| name | VARCHAR(50) NOT NULL | 配料名稱 |
| default_price | DECIMAL(10,2) DEFAULT 0 | 平台建議售價 |

---

### 4. brands 品牌
> 品牌總部帳號。一個品牌下可建立多間分店。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| name | VARCHAR NOT NULL | 品牌名稱 |
| account | VARCHAR UNIQUE NOT NULL | 品牌登入帳號 |
| password_hash | VARCHAR NOT NULL | BCrypt 密碼雜湊 |
| role | VARCHAR DEFAULT 'BRAND' | 固定為 BRAND |
| logo_url | VARCHAR(255) | 品牌 Logo URL（Cloudinary）|
| created_at | DATETIME | 建立時間 |

---

### 5. brand_spec_setting 品牌規格設定
> 品牌從 `spec_master` 中選用規格，可自訂名稱、排序及啟用狀態。

⚠️ 含 `spec_type`、`is_enabled`、`sort_order` 欄位。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| brand_id | BIGINT FK → brands | |
| master_id | BIGINT FK → spec_master | 對應平台規格（可為 null，表示品牌自建規格）|
| spec_type | VARCHAR(20) | ICE / SWEETNESS / SIZE |
| custom_name | VARCHAR(50) | 品牌自訂顯示名稱 |
| is_enabled | BOOLEAN DEFAULT false | 是否對顧客顯示 |
| sort_order | INT DEFAULT 0 | 同類型規格的顯示排序 |

---

### 6. brand_topping_setting 品牌配料設定
> 品牌選用或自建配料，設定自訂名稱與售價。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| brand_id | BIGINT FK → brands | |
| master_topping_id | BIGINT FK → topping_master | 可為 null（品牌完全自訂配料）|
| custom_name | VARCHAR(50) | 品牌自訂配料名稱 |
| brand_price | DECIMAL(10,2) | 品牌自訂售價 |
| is_enabled | BOOLEAN DEFAULT false | 是否啟用 |

---

### 7. brand_region_category_pricing 品牌區域加價
> 支援同品牌依地區（北/中/南）對特定分類收取不同加價。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| brand_id | BIGINT FK → brands | |
| region_id | BIGINT FK → regions | |
| category_id | BIGINT FK → product_categories | |
| price_offset | DECIMAL(10,2) NOT NULL DEFAULT 0 | 加價金額（正值代表加收）|

> UNIQUE 約束：`(brand_id, region_id, category_id)`

---

### 8. product_categories 飲品分類
> 品牌的飲品分類（如：奶茶、果茶）。

⚠️ DB 表名為 `product_categories`，Entity 類別名為 `MenuCategory`。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| brand_id | BIGINT FK → brands | |
| name | VARCHAR(50) NOT NULL | 分類名稱 |
| sort_order | INT DEFAULT 0 | 前台顯示排序 |
| is_enabled | BOOLEAN DEFAULT true | 是否對顧客顯示 |

---

### 9. product_templates 飲品模板
> 品牌層級的飲品定義，各分店共用同一模板，由 `store_product_settings` 控制上下架。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| brand_id | BIGINT FK → brands | |
| category_id | BIGINT FK → product_categories | |
| name | VARCHAR(100) NOT NULL | 飲品名稱 |
| description | VARCHAR(255) | 飲品描述 |
| base_price | DECIMAL(10,2) NOT NULL | 基本售價（不含加料與規格加價）|
| max_toppings | INT DEFAULT 3 | 最多可加配料數 |
| logo_url | VARCHAR(255) | 飲品圖片 URL（Cloudinary）|
| coupon_image_url | VARCHAR(255) | 優惠券合成圖片 URL（Java 2D 動態生成後上傳 Cloudinary）|
| is_enabled | BOOLEAN DEFAULT true | 是否啟用（品牌層級）|
| sort_order | INT NOT NULL DEFAULT 0 | 菜單顯示排序 |

---

### 10. product_spec_relations 飲品規格關聯
> 定義某飲品支援哪些規格，並可對特定規格（如 SIZE L）額外加價。

⚠️ DB 表名為 `product_spec_relations`（複數）。`price` 欄位支援規格加價（SIZE 類型）。

| 欄位 | 型別 | 說明 |
|------|------|------|
| product_id | BIGINT FK → product_templates | 複合 PK |
| brand_spec_id | BIGINT FK → brand_spec_setting | 複合 PK |
| price | DECIMAL(10,2) | 此規格的加價金額（0 表示不加價）|

---

### 11. product_topping_rule 飲品配料規則
> 定義某飲品允許加哪些配料（多對多關係）。

| 欄位 | 型別 | 說明 |
|------|------|------|
| product_id | BIGINT FK → product_templates | 複合 PK |
| brand_topping_id | BIGINT FK → brand_topping_setting | 複合 PK |

---

### 12. stores 分店
> 分店帳號與完整基本資料，含座標、外送設定、營業時間等。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| brand_id | BIGINT FK → brands | |
| region_id | BIGINT FK → regions | 所在地區（北/中/南）|
| store_name | VARCHAR(255) NOT NULL | 分店名稱 |
| account | VARCHAR UNIQUE | 登入帳號 |
| password_hash | VARCHAR | BCrypt 密碼雜湊 |
| role | VARCHAR(20) DEFAULT 'STORE' | 固定為 STORE |
| address | VARCHAR | 門市地址 |
| latitude / longitude | DECIMAL(10,8) / DECIMAL(11,8) | 地理座標（用於地圖與附近搜尋）|
| is_delivery_available | BOOLEAN DEFAULT true | 是否提供外送服務 |
| max_delivery_km | DECIMAL(5,2) DEFAULT 0 | 最大外送距離（公里）|
| min_delivery_amount | DECIMAL(10,2) DEFAULT 0 | 外送最低消費金額 |
| delivery_start_time | VARCHAR(5) | 外送開始時間（HH:mm 格式）|
| delivery_end_time | VARCHAR(5) | 外送結束時間（HH:mm 格式）|
| opening_hours | JSON | 每週各日營業時段 |
| is_accepting_orders | BOOLEAN DEFAULT true | 是否接單（可獨立關閉，不影響 status）|
| avg_rating | DECIMAL(2,1) DEFAULT 0 | 平均評分（訂單完成後更新）|
| review_count | INT DEFAULT 0 | 評論（評分）總數 |
| status | VARCHAR(20) DEFAULT 'active' | active（營業中）/ closed（休息）|
| manager_name / manager_phone / store_phone | VARCHAR | 管理員姓名、手機、門市電話 |
| cover_url | VARCHAR(255) | 店家封面圖（Cloudinary）|

---

### 13. store_product_settings 分店飲品供應狀態
> 分店層級控制各飲品是否供應，覆蓋品牌層級的 `is_enabled`。

⚠️ DB 表名為 `store_product_settings`（非 `store_product_status`）。欄位為 `is_enabled`（**true = 供應中，false = 下架/售完**）。

| 欄位 | 型別 | 說明 |
|------|------|------|
| store_id | BIGINT FK → stores | 複合 PK |
| product_id | BIGINT FK → product_templates | 複合 PK |
| is_enabled | BOOLEAN DEFAULT true | true=供應中，false=下架/售完 |

---

### 14. store_spec_settings 分店規格設定
> 分店可覆蓋品牌規格的啟用狀態（例如某分店不提供某種甜度）。

| 欄位 | 型別 | 說明 |
|------|------|------|
| store_id | BIGINT FK → stores | 複合 PK |
| brand_spec_id | BIGINT FK → brand_spec_setting | 複合 PK |
| is_enabled | BOOLEAN DEFAULT true | 分店層級啟用狀態 |

---

### 15. store_topping_settings 分店配料售罄
> 分店標記特定配料是否售完。

⚠️ DB 表名為 `store_topping_settings`（非 `store_topping_status`）。

| 欄位 | 型別 | 說明 |
|------|------|------|
| store_id | BIGINT FK → stores | 複合 PK |
| brand_topping_id | BIGINT FK → brand_topping_setting | 複合 PK |
| is_out_of_stock | BOOLEAN DEFAULT false | true = 售完中 |

---

### 16. users 使用者
> 顧客帳號，支援傳統電話/密碼登入與第三方社群登入。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| phone | VARCHAR UNIQUE NOT NULL | 手機號碼（主要登入帳號）|
| password_hash | VARCHAR | BCrypt 密碼雜湊（社群登入可為 null）|
| name | VARCHAR(50) NOT NULL | 顯示名稱 |
| pic_url | VARCHAR | 頭像 URL |
| role | VARCHAR DEFAULT 'CUSTOMER' | 固定為 CUSTOMER |
| balance | DECIMAL(12,2) NOT NULL DEFAULT 0.00 | 錢包餘額 |
| created_at | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | 建立時間 |
| is_deleted | BOOLEAN NOT NULL DEFAULT false | 邏輯刪除（不實際刪除資料）|
| deleted_at | DATETIME(6) DEFAULT NULL | 邏輯刪除時間 |

---

### 17. user_auth_providers 第三方登入
> 使用者綁定的社群帳號，支援多個提供者（一個使用者可同時綁定 Google 與 Facebook）。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| user_id | BIGINT FK → users | |
| provider | VARCHAR(20) | PHONE / GOOGLE / FACEBOOK |
| provider_uid | VARCHAR NOT NULL | 對應社群平台的唯一識別碼 |

> UNIQUE 約束：`(provider, provider_uid)`

---

### 18. user_addresses 使用者常用地址
> 使用者儲存的多筆常用地址（如：家、公司），結帳時可快速選用。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| user_id | BIGINT FK → users | |
| label | VARCHAR(50) | 地址標籤（如：家、公司、學校）|
| address_name | VARCHAR(255) NOT NULL | 完整地址字串 |
| latitude | DECIMAL(10,8) | 緯度（可選）|
| longitude | DECIMAL(11,8) | 經度（可選）|
| is_default | BOOLEAN DEFAULT false | 是否為預設地址 |
| created_at | DATETIME | 建立時間 |

---

### 19. user_favorites 收藏店家
> 使用者收藏的分店清單，支援新增/移除切換。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| user_id | BIGINT FK → users | |
| store_id | BIGINT FK → stores | |
| created_at | DATETIME | 收藏時間 |

> UNIQUE 約束：`(user_id, store_id)`

---

### 20. user_coupons 使用者優惠券
> 使用者透過轉盤抽獎或管理員發放所獲得的優惠券，直接記錄品牌與飲品資訊。

⚠️ 不再關聯獨立 `coupons` 表，優惠券資訊直接存於此表。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| user_id | BIGINT FK → users | |
| brand_id | BIGINT FK → brands | 優惠券所屬品牌 |
| product_id | BIGINT FK → product_templates | 轉盤抽中的具體飲品 |
| coupon_type | VARCHAR(20) NOT NULL | 優惠券來源：WHEEL_GAME（轉盤）/ ADMIN_GIFT（管理員贈送）|
| discount_amount | DECIMAL(10,2) NOT NULL DEFAULT 5.00 | 折扣金額（固定金額折抵）|
| status | VARCHAR(10) NOT NULL DEFAULT 'unused' | unused / used / expired（**小寫**）|
| obtained_at | DATETIME NOT NULL | 獲得時間 |
| obtained_date | DATE **GENERATED STORED** | `CAST(obtained_at AS DATE)`，防同日重複抽取 |
| expired_at | DATETIME **GENERATED STORED** | `obtained_at + INTERVAL 7 DAY`，7 天後過期 |
| used_at | DATETIME | 使用時間 |
| order_item_id | BIGINT | 使用時對應的訂單品項 ID |

> ⚠️ **`obtained_date` 與 `expired_at` 必須是 MySQL GENERATED COLUMN。**
> Entity 標記 `insertable=false, updatable=false`，程式端**完全不寫入**這兩欄
> （`DailySpinService` 的註解也明講「由資料庫 Generated Column 自動生成」）。
> 若資料表建成普通欄位，兩欄會永遠是 NULL，連帶造成：
> - `CouponExpiryScheduler.findByStatusAndExpiredAtBefore(...)` 查不到任何券 → **優惠券永不過期**
> - `DailySpinService` 的 DB 層防重複（`existsByUserIdAndObtainedDateAndCouponType`）永遠回 false
>   → Redis 故障時失去第二道防線
>
> Entity 已用 `columnDefinition` 帶出 generated 定義，新建資料庫會正確產生。
> 但 `ddl-auto=update` **不會**把已存在的普通欄位改成 generated —— 若曾在修正前啟動過，
> 請 `docker compose down -v` 重建，或手動 `ALTER TABLE` 修正。

---

### 21. transaction_records 錢包交易
> 錢包所有金流明細，採帳本設計（正值=入帳，負值=出帳）。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| user_id | BIGINT FK → users | |
| amount | DECIMAL(12,2) NOT NULL | 正值=增加，負值=扣除 |
| type | VARCHAR | TOPUP（儲值）/ ESCROW（下單凍結）/ REFUND（退款）/ FINAL_PAY（實扣）/ REPAYMENT（補款）|
| created_at | DATETIME | 交易時間 |

---

### 22. orders 訂單主表
> 個人訂單（SOLO）與揪團訂單（GROUP）共用同一張表。

⚠️ DB 表名為 `orders`，Entity 類別名為 `GroupOrder`。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| initiator_id | BIGINT FK → users | 訂單發起人（揪團中為團長）|
| store_id | BIGINT FK → stores | 下單的分店 |
| type | VARCHAR NOT NULL DEFAULT 'SOLO' | SOLO（個人）/ GROUP（揪團）|
| status | VARCHAR NOT NULL DEFAULT 'OPEN' | OPEN / SUBMITTED / **PREPARING** / READY / COMPLETED / CANCELLED / REJECTED |
| is_rejected | BOOLEAN DEFAULT false | 是否被分店拒單 |
| order_no | VARCHAR UNIQUE **NOT NULL** | 訂單編號（人類可讀格式，`@PrePersist` 自動產生）|
| share_token | VARCHAR(16) UNIQUE | 揪團分享 Token（16 位隨機字串）|
| total_amount | DECIMAL(12,2) DEFAULT 0 | 訂單總金額 |
| address | VARCHAR(255) | 外送地址（null 表示自取）|
| note | VARCHAR(255) | 訂單全域備註 |
| escrow_amount | DECIMAL(12,2) DEFAULT 0 | 凍結於錢包的金額 |
| created_at | DATETIME | 建立時間 |
| submitted_at | DATETIME | 送出時間（OPEN → SUBMITTED）|
| preparing_at | DATETIME(6) | 店家接單時間 |
| ready_at | DATETIME | 製作完成時間（SUBMITTED → READY）|
| completed_at | DATETIME | 完成取餐時間（READY → COMPLETED）|
| cancelled_or_rejected_at | DATETIME | 取消或拒單時間 |

**訂單狀態流程：**
```
OPEN（待處理）→ SUBMITTED（已送出）→ PREPARING（製作中）→ READY（待取餐）→ COMPLETED（已完成）
     │                 │                    │
     └─────────────────┴────────────────────┴→ REJECTED（分店拒單）
     └→ CANCELLED（顧客取消）
```

實際轉換皆在 `AnalyticsService`：
- `acceptOrder`：OPEN **或** SUBMITTED → PREPARING（可從 OPEN 直接接單），寫入 `preparing_at`
- `completeProduction`：**僅** PREPARING → READY（已是 READY 則直接返回）
- `finalizeOrder`：僅 READY → COMPLETED
- `rejectOrder`：OPEN / SUBMITTED / PREPARING → REJECTED，同時 `is_rejected=true` 並執行退款

---

### 23. order_items 訂單品項
> 儲存完整下單當下的快照。相同客製化的品項以 `qty` 合併，不是一杯一筆。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| group_order_id | BIGINT FK → orders | 所屬訂單 |
| user_id | BIGINT FK → users | 點這杯的使用者（揪團中可能是不同團員）|
| product_id | BIGINT FK → product_templates | 對應飲品模板 |
| product_name_snapshot | VARCHAR | **下單時**的飲品名稱快照 |
| unit_price_snapshot | DECIMAL(10,2) | **下單時**的飲品單價快照 |
| final_price | DECIMAL(10,2) | 實際結帳金額（含配料加價與折扣後）|
| sugar_snapshot | VARCHAR | 甜度選項快照 |
| ice_snapshot | VARCHAR | 冰量選項快照 |
| size_snapshot | VARCHAR | 杯型快照（M / L）|
| item_hash | VARCHAR(32) | 客製化組合的雜湊，用於判斷「同品項」可否合併數量 |
| qty | INT NOT NULL DEFAULT 1 | 數量（Java 欄位名為 `quantity`，DB 欄位名為 `qty`）|
| payment_status | VARCHAR DEFAULT 'WAITING_SUBMIT' | WAITING_SUBMIT / PENDING / PAID / REFUNDED |
| payment_type | VARCHAR | WALLET（錢包）/ CASH（現金）|
| coupon_id | BIGINT | 套用的優惠券 ID |
| discount_amount_snapshot | DECIMAL(10,2) DEFAULT 0 | 優惠券折扣金額快照 |

> ⚠️ **沒有 `note` 欄位**。舊版文件曾列出「單杯備註 VARCHAR(10)」，但 `OrderItem` Entity
> 並無此欄位；訂單備註只存在於 `orders.note`（全域備註）。

---

### 24. order_item_toppings 配料快照
> 訂單品項所加的配料，以快照方式儲存，避免日後配料名稱或價格變動影響歷史訂單。

| 欄位 | 型別 | 說明 |
|------|------|------|
| order_item_id | BIGINT FK → order_items | 複合 PK |
| topping_name_snapshot | VARCHAR(50) NOT NULL | 複合 PK，**下單時**的配料名稱快照 |
| topping_price_snapshot | DECIMAL(10,2) | **下單時**的配料價格快照 |

---

### 25. order_ratings 訂單星級評分
> 訂單完成後顧客給予的星級評分，支援即時更新（可改分）。

⚠️ 獨立評分表，`order_reviews`（文字評論）表已**停用並從程式碼移除**。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| order_id | BIGINT FK → orders | 對應訂單 |
| user_id | BIGINT FK → users | 評分者 |
| store_id | BIGINT FK → stores | 被評分的分店（方便查詢分店平均分）|
| rating | INT NOT NULL | 1～5 星 |
| created_at | DATETIME NOT NULL | 初次評分時間 |

> UNIQUE 約束：`uk_order_ratings_order_user (order_id, user_id)`（每筆訂單只能評一次，但可修改）
>
> ⚠️ **沒有 `updated_at` 欄位**。舊版文件列了此欄，但 `OrderRating` Entity 只有
> `created_at`；改分時不會留下更新時間。

---

### 26. cart_items 購物車
> 使用者的購物車暫存，同一門市的品項才能共存（跨門市會回 409 要求清空）。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT PK | |
| user_id | BIGINT FK → users | |
| store_id | BIGINT FK → stores | 購物車綁定的門市（混店會被拒絕）|
| product_id | BIGINT FK → product_templates | |
| sugar_snapshot | VARCHAR(20) | 甜度選項 |
| ice_snapshot | VARCHAR(20) | 冰量選項 |
| size_snapshot | VARCHAR(10) | 杯型（M / L）|
| topping_names | VARCHAR(255) | 加料名稱（逗號分隔字串）|
| topping_extra | DECIMAL(10,2) DEFAULT 0 | 加料總加價 |
| unit_price | DECIMAL(10,2) | 飲品本身單價 |
| final_price | DECIMAL(10,2) | 飲品 + 加料 + 規格加價後的單杯總價 |
| qty | INT NOT NULL DEFAULT 1 | 數量（相同品項可合併，默認 1）|
| created_at | DATETIME | 加入購物車時間 |

---

## 💡 關鍵業務邏輯備忘

1. **快照機制**：訂單明細儲存名稱與價格快照，品牌修改菜單後歷史訂單不受影響。
2. **Entity 與 DB 表名對應注意**：
   - `GroupOrder` → `orders`
   - `MenuCategory` → `product_categories`
   - `StoreProductStatus` → `store_product_settings`
   - `StoreToppingStatus` → `store_topping_settings`
3. **優惠券重新設計**：`user_coupons` 直接記錄品牌+飲品，不再透過獨立 `coupons` 表。`product_templates.coupon_image_url` 儲存 Java 2D 動態合成的優惠券圖片（品牌名+飲品名+折扣）。
4. **評分管理**：`order_ratings` 記錄星級（可即時更新）。`order_reviews`（文字評論）已從程式碼中完全移除。
5. **分店供應狀態**：`store_product_settings.is_enabled=false` 表示**下架/售完**，與舊版 `is_out_of_stock` 邏輯相反，使用時務必注意語意。
6. **購物車與訂單數量**：`cart_items.qty` 與 `order_items.qty` 皆支援同品項合併（DEFAULT 1）。
   兩者的 Java 欄位名都是 `quantity`（`OrderItem` 為 `qty`），DB 欄位名統一為 `qty`。
   `order_items.item_hash` 是客製化組合的雜湊，用來判定兩筆是否為「同品項」。
7. **常用地址**：`user_addresses` 支援每位使用者儲存多筆地址，可設定預設地址 (`is_default`)，結帳時快速帶入。
8. **錢包交易類型**：TOPUP（儲值）→ ESCROW（下單凍結）→ FINAL_PAY（完成實扣）/ REFUND（取消/拒單退款）；REPAYMENT 用於揪團補款場景。
9. **Generated Column 依賴**：`user_coupons` 的 `obtained_date` / `expired_at` 由 MySQL 自動計算，
   程式端不寫入。換資料庫或重建 schema 時務必確認這兩欄仍是 generated column（詳見第 20 節）。
10. **沒有 `daily_spin_history` 表**：對應 Entity 從未建立，殘留的
    `DailySpinHistoryRepository.java`（整份被註解掉）已於 2026-08-12 刪除。
    轉盤的每日防重複改由 Redis key（`spin:done:{userId}:{date}`）
    加上 `user_coupons.obtained_date` 查詢達成。
