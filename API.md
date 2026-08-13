# JoinDrink API 技術手冊 (v4.1)

> **最後更新**：2026-03-30
> **Base URL**：`http://localhost:8082`
> **認證方式**：`Authorization: Bearer <JWT>`
> **統一回應格式**：`{ "code": "200", "msg": "請求成功", "data": { ... } }`

---

## 認證角色說明

| 角色 | 說明 | 取得方式 |
|------|------|---------|
| CUSTOMER | 一般使用者 | `/api/auth/login` |
| BRAND | 品牌總部 | `/api/brand-auth/login` |
| STORE | 分店 | `/api/stores/auth/login` 或 `/api/auth/corporate-login` |

---

## 1. 使用者認證 (User Auth)

| Method | Path | 認證 | 說明 |
|--------|------|------|------|
| POST | `/api/auth/register` | 無 | 使用者註冊（需 Firebase idToken）Body: `{ phone, password, name, idToken }` |
| POST | `/api/auth/login` | 無 | 使用者登入 Body: `{ phone, password }` 回傳: `{ token, userId, name }` |
| POST | `/api/auth/social-login` | 無 | Firebase 第三方登入 Body: `{ idToken, phone, name, provider }` |
| POST | `/api/auth/corporate-login` | 無 | **品牌/分店統一登入入口**，自動辨識帳號身分 Body: `{ account, password }` |
| GET | `/api/auth/check-phone` | 無 | 檢查手機是否已註冊 Query: `?phone=09xx` |
| POST | `/api/auth/firebase-verify` | 無 | Firebase Token 預檢 Body: `{ idToken }` |
| POST | `/api/auth/reset-password-firebase` | 無 | 重設密碼 Body: `{ idToken, phone, newPassword }` |
| POST | `/api/auth/merge/set-password` | 無 | 帳號整合：三方帳號設密碼 Body: `{ idToken, phone, newPassword }` |
| POST | `/api/auth/merge/bind-social` | 無 | 帳號整合：傳統帳號綁三方 Body: `{ idToken, phone, providerUid, provider }` |
| PUT | `/api/auth/update` | CUSTOMER | 更新個人資料 Body: `{ name?, picUrl?, phone? }` |
| GET | `/api/auth/debug/social-logins` | 無 | Dev 工具：列出最近三方登入紀錄 |

---

## 2. 公開瀏覽 (Public - 無需登入)

| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/home` | 首頁資料（banners、附近店家）Query: `?lat=&lng=` |
| GET | `/api/stores/map` | 地圖所有營業中店家座標 |
| GET | `/api/stores/nearby` | 附近店家分頁 Query: `?lat=&lng=&brandId=&minRating=&page=1&size=6` |
| GET | `/api/stores/nearby/v2` | 附近店家 V2 Query: `?lat=&lon=&sort=&category=&freeDelivery=` |
| GET | `/api/stores/search` | 關鍵字搜尋品牌/店家 Query: `?keyword=&lat=&lng=&page=1&size=10` |
| GET | `/api/stores/{storeId}/info` | 店家詳細資訊（評分、營業時間、地址）|
| GET | `/api/stores/{storeId}/v2` | 店家詳情 V2 |
| GET | `/api/brands` | 平台所有品牌列表 |
| GET | `/api/brands/{brandId}` | 品牌資訊與旗下分店 |
| GET | `/api/products/{productId}` | 飲品基本資訊 |
| GET | `/api/products/{productId}/customization` | 飲品可選規格與配料 |
| GET | `/api/products/{productId}/customization/v2` | 飲品客製化 V2（ResponseEntity 格式）|
| GET | `/api/products/{productId}/specs` | 飲品規格列表 |
| GET | `/api/products/store/{storeId}` | 分店上架飲品列表 |
| GET | `/api/products/store/{storeId}/all` | 分店所有飲品（含下架）|
| GET | `/api/location/search` | 地址關鍵字搜尋 Query: `?keyword=台中市西區` |
| GET | `/api/location/cities` | 台灣縣市列表 |
| GET | `/api/location/districts` | 縣市下的區域列表 Query: `?cityCode=TXG` |
| GET | `/api/group-orders/join/{token}` | 透過分享 Token 取得揪團資訊（V1，無需登入）|
| GET | `/api/game-wheel/brands` | 可參與轉盤的品牌列表（無需登入）|
| GET | `/api/game-wheel/menu/{brandId}` | 品牌轉盤選項 回傳: `{ wheelOptions: [{ categoryName }] }` |

---

## 3. 使用者功能 (CUSTOMER)

### 菜單
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/menu/store/{storeId}` | 取得分店完整菜單（分類、飲品、規格、配料、售完狀態）|

### 訂單（舊版 / UserController）
| Method | Path | 說明 |
|--------|------|------|
| POST | `/api/orders/place` | 建立訂單 Body: `{ storeId, note?, items: [{ productId, sugarSnapshot, iceSnapshot, paymentType, toppingNames }] }` |
| GET | `/api/orders/history` | 歷史訂單列表 |
| GET | `/api/orders/{orderId}` | 訂單詳細資料 |
| GET | `/api/orders/summary` | 各狀態訂單數量統計 |
| GET | `/api/orders/{orderId}/timeline` | 訂單狀態時間線 |
| POST | `/api/orders/{orderId}/reorder` | 再次訂購（複製歷史訂單）|
| POST | `/api/orders/{orderId}/cancel` | 取消訂單（僅 OPEN 狀態）|
| GET | `/api/orders/{orderId}/rating` | 取得我的訂單評分狀態 |
| POST | `/api/orders/{orderId}/rating` | 提交訂單評分 Body: `{ rating: 1~5 }` |

### 訂單（V2 / OrderController）
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/orders/user/{userId}/cards` | 使用者訂單卡片列表（分頁）Query: `?page=&size=`。`{userId}` 必須是自己，否則 403 |
| GET | `/api/orders/user/{userId}/recent-cards` | 最近 10 筆訂單卡片。同上，僅限本人 |
| GET | `/api/orders/user/{userId}/active` | 使用者進行中訂單。同上，僅限本人 |
| GET | `/api/orders/{orderId}/v2` | 訂單詳情 V2（ResponseEntity 格式）。限發起人或參與者 |
| GET | `/api/orders/{orderId}/items` | 訂單品項列表。限發起人或參與者 |
| POST | `/api/orders/checkout` | 結帳下單 |
| PUT | `/api/orders/{orderId}/status` | 更新訂單狀態 Query: `?status=`。僅限訂單發起人；門市請改用 `/api/stores/dashboard/orders/{id}/*` |
| PUT | `/api/orders/{orderId}/cancel/v2` | 取消訂單 V2（ResponseEntity 格式）。僅限發起人（團員則取消自己的品項）|
| GET | `/api/orders` | 訂單列表 Query: `?userId=` |

### 揪團（V1）
| Method | Path | 說明 |
|--------|------|------|
| POST | `/api/group-orders` | 建立揪團（取得 shareToken）Body: `{ storeId, type: GROUP\|SOLO }` |
| POST | `/api/group-orders/{groupOrderId}/join` | 加入揪團並新增品項 |
| GET | `/api/group-orders/{groupOrderId}/members` | 揪團成員與點餐清單 |
| GET | `/api/group-orders/{groupOrderId}/summary` | 揪團付款統計 |
| GET | `/api/group-orders/{groupOrderId}/share` | 取得分享連結與 QR Code |
| POST | `/api/group-orders/{groupOrderId}/submit` | 團長送單 OPEN → SUBMITTED |
| DELETE | `/api/group-orders/{groupOrderId}` | 團長取消揪團（僅 OPEN）|

### 揪團（V2）
| Method | Path | 說明 |
|--------|------|------|
| POST | `/api/group-orders/v2` | 建立揪團 V2 |
| GET | `/api/group-orders/{token}` | CUSTOMER | 依 token 取得揪團資訊（雙 mapping 含 `/join/{token}`）|
| GET | `/api/group-orders/active` | 進行中揪團 Query: `?userId=&storeId=`（storeId 可選）|
| GET | `/api/group-orders/by-order/{orderId}` | 依 orderId 查詢揪團實體 |
| DELETE | `/api/group-orders/token/{token}` | 永久刪除揪團（V2）Query: `?hostId=` |
| POST | `/api/group-orders/{token}/items` | 新增品項至揪團 |
| DELETE | `/api/group-orders/{token}/items/{itemId}` | 移除揪團品項 Query: `?userId=` |
| PUT | `/api/group-orders/{token}/items/{itemId}` | 更新揪團品項 Query: `?userId=` |
| POST | `/api/group-orders/{token}/items/{itemId}/apply-coupon` | 套用優惠券至品項 |
| PUT | `/api/group-orders/{token}/status` | 設定揪團狀態 Query: `?status=&hostId=` |
| POST | `/api/group-orders/{token}/checkout` | 揪團結帳 |
| POST | `/api/group-orders/{token}/member-checkout` | 團員付款 |
| POST | `/api/group-orders/{token}/repay` | 補繳款項 |

### 優惠券與轉盤
| Method | Path | 說明 |
|--------|------|------|
| POST | `/api/coupons/spin` | 轉盤抽券（每日限1次，超過上限回 error）Body: `{ brandId }` |
| GET | `/api/coupons/my` | 我的優惠券列表 |
| PUT | `/api/coupons/{userCouponId}/use` | 使用優惠券 |
| GET | `/api/coupons/spin-status` | 今日轉盤剩餘次數 回傳: `{ remainSpins: 0\|1 }` |
| GET | `/api/coupons/user/{userId}` | 使用者優惠券 Query: `?storeId=`（可選）|
| GET | `/api/coupons/{id}` | 單一優惠券詳情 |

### 購物車
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/cart` | 取得購物車 |
| POST | `/api/cart/items` | 新增品項（不同店家會回傳 409）|
| PUT | `/api/cart/items/{itemId}` | 修改品項 |
| DELETE | `/api/cart/items/{itemId}` | 刪除單一品項 |
| DELETE | `/api/cart` | 清空購物車 |
| GET | `/api/cart/summary` | 購物車數量與總金額摘要 |

### 會員、錢包與地址
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/users/me` | 取得會員資料與餘額 |
| PUT | `/api/users/me` | 修改暱稱、頭像 Body: `{ name?, picUrl? }` |
| DELETE | `/api/users/me` | 邏輯刪除帳號 |
| POST | `/api/users/avatar` | 上傳頭像 Form: `avatar`（圖片）回傳: `{ avatarUrl }` |
| GET | `/api/users/{userId}` | 取得使用者資料 |
| PUT | `/api/users/{userId}/avatar` | 更新頭像 URL |
| PUT | `/api/users/{userId}/profile` | 更新個人資料 |
| POST | `/api/users/{userId}/change-password` | 修改密碼 |
| GET | `/api/users/{userId}/address` | 取得使用者常用地址列表 |
| POST | `/api/users/{userId}/addresses` | 新增常用地址 Body: `{ city, district, street, label?, latitude?, longitude? }` |
| PUT | `/api/users/{userId}/addresses/{addressId}` | 更新指定地址 |
| DELETE | `/api/users/{userId}/addresses/{addressId}` | 刪除指定地址 |
| GET | `/api/wallet` | 錢包餘額 回傳: `{ balance }` |
| GET | `/api/wallet/transactions` | 交易紀錄 Query: `?months=3&page=1&size=10` |
| POST | `/api/wallet/topup` | 儲值 Body: `{ amount: 500 }` 回傳: `{ newBalance }` |
| POST | `/api/users/{userId}/recharge` | 儲值（另一入口）|
| GET | `/api/users/{userId}/store-credit-records` | 取得交易紀錄 |

### 收藏店家
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/user-favorites/user/{userId}` | 使用者收藏店家列表 |
| GET | `/api/user-favorites/check` | 檢查是否已收藏 Query: `?userId=&storeId=` |
| POST | `/api/user-favorites/toggle` | 切換收藏狀態 Body: `{ userId, storeId }` |

---

## 4. 品牌後台 (BRAND)

### 認證
| Method | Path | 認證 | 說明 |
|--------|------|------|------|
| POST | `/api/brand-auth/register` | 無 | 品牌註冊 Body: `{ name, account, password }` |
| POST | `/api/brand-auth/login` | 無 | 品牌登入 Body: `{ account, password }` |
| POST | `/api/brand-auth/create-store` | BRAND | 建立分店帳號 Body: `{ storeName, account, password }` |
| POST | `/api/brand-auth/create-store-with-images` | BRAND | 建立分店（含圖片上傳）multipart/form-data |

### 圖片上傳
| Method | Path | 說明 |
|--------|------|------|
| POST | `/api/brand/upload-image` | 上傳圖片至 Cloudinary Query: `folder=` |
| POST | `/api/brand/logo` | 上傳品牌 Logo |

### 地區
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/brand/regions` | 取得所有地區列表 |

### 飲品分類
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/brand/categories` | 查詢所有分類 |
| POST | `/api/brand/categories` | 批量建立分類 Body: `{ names: ["奶茶", "果茶"] }` |
| PUT | `/api/brand/categories/{categoryId}` | 重新命名分類 Body: `{ name: "新名稱" }` |
| DELETE | `/api/brand/categories/{categoryId}` | 刪除分類（連帶刪除飲品與優惠券）|

### 飲品管理
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/brand/products` | 查詢所有飲品 回傳: `[{ productId, categoryId, categoryName, name, basePrice, maxToppings, logoUrl }]` |
| GET | `/api/brand/products/{productId}/detail` | 取得飲品詳情（含已選規格與配料）|
| POST | `/api/brand/products` | 新增飲品 Body: `{ categoryId, name, basePrice, maxToppings?, logoUrl? }` |
| PUT | `/api/brand/products/{productId}` | 更新飲品（欄位皆選填）|
| DELETE | `/api/brand/products/{productId}` | 刪除飲品 |

### 規格管理
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/brand/specs` | 品牌規格列表（依 ICE/SWEETNESS/SIZE 分組）|
| POST | `/api/brand/specs` | 新增規格 Body: `{ type: ICE\|SWEETNESS\|SIZE, name: "去冰" }` |
| PATCH | `/api/brand/specs/{brandSpecId}` | 編輯規格名稱 Body: `{ name: "自訂名稱" }` |
| PATCH | `/api/brand/specs/{brandSpecId}/toggle` | 切換規格啟用狀態 |
| DELETE | `/api/brand/specs/{brandSpecId}` | 刪除規格 |
| PATCH | `/api/brand/specs/reorder` | 重新排序規格 Body: `{ type: ICE, orderedSpecIds: [3,1,2] }` |

### 配料管理
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/brand/toppings` | 品牌配料列表 |
| POST | `/api/brand/toppings` | 新增配料 Body: `{ name: "珍珠", price: 10 }` |
| PATCH | `/api/brand/toppings/{brandToppingId}` | 編輯配料名稱與價格 |
| PATCH | `/api/brand/toppings/{brandToppingId}/toggle` | 切換配料啟用狀態 |
| DELETE | `/api/brand/toppings/{brandToppingId}` | 刪除配料 |

### Dashboard 與分析
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/brand/dashboard/overview` | 今日整體指標（營收、訂單數、AOV）附昨日對比趨勢 |
| GET | `/api/brand/dashboard/storestatus` | 分店營業狀態統計 回傳: `{ open, closed, abnormal, total }` |
| GET | `/api/brand/dashboard/top-stores` | 本週業績前五名分店 回傳: `[{ rank, storeId, storeName, revenue, trend }]` |
| GET | `/api/brand/analytics/products` | 全台熱銷/滯銷品項 Query: `?period=week\|month\|year` |

### 財務
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/brand/finance/overview` | 財務匯總概況（佣金/淨收入）Query: `?period=week\|month\|quarter\|year` |
| GET | `/api/brand/finance/full` | 財務完整摘要含各分店明細 Query: `?period=week\|month\|year` |
| GET | `/api/brand/finance/trend` | 品牌營收趨勢 Query: `?period=week\|month\|quarter` |
| GET | `/api/brand/finance/regions` | 各區域營收統計 Query: `?period=week\|month\|year` |

### 分店管理 & 口碑
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/brand/stores` | 品牌分店列表（含 regionName、managerName、managerPhone）|
| PUT | `/api/brand/stores/{storeId}` | 品牌更新旗下分店資料 |
| GET | `/api/brand/reputation` | 品牌口碑監控（平均評分、最低評分分店、各區域滿意度）|

---

## 5. 分店後台 (STORE)

基礎路徑：`/api/stores`（`@RequestMapping("/api/stores")`）

### 認證與圖片
| Method | Path | 認證 | 說明 |
|--------|------|------|------|
| POST | `/api/stores/auth/login` | 無 | 分店登入 Body: `{ account, password }` |
| POST | `/api/stores/upload-image` | STORE | 上傳分店圖片至 Cloudinary |

### 基本資料與設定
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/stores/{id}` | 取得分店公開資訊（無需登入）|
| PUT | `/api/stores/update` | 更新分店資料 Body: `{ id, storeName?, coverUrl? }` |
| PUT | `/api/stores/status` | 切換營業狀態 Body: `{ status: open\|closed }` |
| GET | `/api/stores/settings/profile` | 取得分店完整設定 |
| PUT | `/api/stores/settings/business-hours` | 更新營業時間 Body: `{ openingHours: "09:00-22:00" }` |
| PATCH | `/api/stores/settings/delivery-config` | 更新外送設定 Body: `{ allowsDelivery, maxDeliveryKm, minDeliveryAmount, deliveryStartTime, deliveryEndTime }` |
| GET | `/api/stores/brand/{brandId}` | 透過品牌 ID 查詢分店列表 |

### 訂單管理
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/stores/orders` | 訂單列表 Query: `?status=OPEN\|SUBMITTED\|...` |
| GET | `/api/stores/dashboard/orders/pending` | 待接單列表（status=OPEN）|
| GET | `/api/stores/dashboard/orders/active` | 進行中訂單（OPEN/SUBMITTED/READY）|
| POST | `/api/stores/dashboard/orders/{orderId}/accept` | 接單 OPEN → SUBMITTED |
| POST | `/api/stores/dashboard/orders/{orderId}/reject` | 拒單 Body: `{ reason: "已打烊" }` |
| POST | `/api/stores/dashboard/orders/{orderId}/complete-production` | 製作完成 SUBMITTED → READY |
| POST | `/api/stores/dashboard/orders/{orderId}/finalize` | 結案 READY → COMPLETED |

### 菜單供應管理
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/stores/menu/drinks` | 分店飲品清單與供應狀態 |
| PATCH | `/api/stores/menu/drinks/{drinkId}/toggle` | 切換飲品供應狀態 Body: `{ isAvailable: true\|false }` |
| GET | `/api/stores/menu/toppings` | 分店配料供應狀態 |
| PATCH | `/api/stores/menu/toppings/{brandToppingId}/toggle` | 切換配料供應狀態 |

### Dashboard
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/stores/dashboard/today-summary` | 今日訂單數、營收、AOV |
| GET | `/api/stores/dashboard/analytics` | 時段銷售分佈 + 熱門商品前5名 |
| GET | `/api/stores/dashboard/sales-trend` | 本週每日營收趨勢 |

### 報表
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/stores/reports/product-ranking` | 熱門商品排行 Query: `?period=week\|month\|year` |
| GET | `/api/stores/reports/recent-daily` | 近10天日營收彙總 |
| GET | `/api/stores/reports/rating-stats` | 評分分佈與最近評論 |

### 財務
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/stores/finance/summary` | 財務摘要 Query: `?period=week\|month\|year` |
| GET | `/api/stores/finance/categorized` | 按飲品類別統計營收佔比 |
| GET | `/api/stores/finance/commissions` | 佣金明細 Query: `?period=week\|month\|year` |
| GET | `/api/stores/finance/category-trend` | 各類別每日營收趨勢（本週/上週）|

---

## 6. SSE 即時推播

| Method | Path | 認證 | 說明 |
|--------|------|------|------|
| GET | `/api/sse/subscribe/{storeId}` | STORE | 分店訂閱 SSE（新訂單即時推播）|
| GET | `/api/sse/group-subscribe/{token}` | 無 | 揪團 SSE（成員加入/品項更新即時推播）|

---

## 7. 其他

| Method | Path | 認證 | 說明 |
|--------|------|------|------|
| POST | `/api/upload` | 無 | 上傳檔案至本地 `static/uploads/` |

---

## ⚡ 技術備忘

1. **統一企業登入**：`POST /api/auth/corporate-login` 先嘗試 BRAND 再嘗試 STORE，前端只需一個登入頁面。
2. **優惠券設計**：不再有獨立的 `coupons` 表，優惠券資訊直接存在 `user_coupons`（關聯 brand + product），圖片存於 `product_templates.coupon_image_url`（Java 2D 合成後上傳 Cloudinary）。
3. **分店圖片欄位**：`stores.cover_url`（非舊版 `image_url`）。
4. **分店菜單 toggle**：`store_product_settings.is_enabled=false` 表示不供應；`store_topping_settings.is_out_of_stock=true` 表示配料售完。
5. **訂單評分**：`order_ratings`（星級，可即時更新）管理評分，`order_reviews` 表已停用移除。
6. **轉盤路由**：實際路由為 `/api/coupons/spin`（執行）、`/api/coupons/spin-status`（狀態）、`/api/game-wheel/brands`（品牌列表）、`/api/game-wheel/menu/{brandId}`（轉盤選項），**非** `/api/daily-spin/*`。
7. **Controllers 清單**（15 個）：`UserController`、`GroupOrderController`、`BrandController`、`BrandSpecOrderController`、`StoreController`、`CartController`、`PublicController`、`LocationController`、`CouponController`、`DailySpinController`、`OrderController`、`ProductController`、`SseController`、`UploadController`、`UserFavoriteController`。
8. **SSE 推播**：分店使用 storeId 訂閱即時訂單通知；揪團使用 token 訂閱成員動態。
9. **GroupOrder V2**：V2 路由統一使用 `token` 作為 path variable，支援品項 CRUD、結帳、補款等完整流程。刪除路由為 `/api/group-orders/token/{token}`（加 `token/` 前綴避免衝突）。
10. **常用地址 API**：新增 `/api/users/{userId}/address`（GET）、`/api/users/{userId}/addresses`（POST）、`/api/users/{userId}/addresses/{addressId}`（PUT/DELETE）對應 `user_addresses` 表。
11. **CORS 白名單**：`localhost:5173`、`localhost:5500`、`127.0.0.1:5500`、`localhost:60687`、`localhost:63342`、`localhost:8082`。
