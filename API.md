# JoinDrink API 技術手冊 (v4.1)

> **最後更新**：2026-10-18（補充：店家搜尋分頁；冪等結帳、轉盤狀態碼、附近店家分頁與座標檢查、健康檢查、`X-Request-Id`；移除不存在的 SSE 端點）
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
| ADMIN | 平台管理員（N-28，只能由 `AdminBootstrap` 建立）| `/api/admin/auth/login`（僅 HTTP，WebSocket 不接受）|

---

## 1. 使用者認證 (User Auth)

| Method | Path | 認證 | 說明 |
|--------|------|------|------|
| POST | `/api/auth/register` | 無 | 使用者註冊（需 Firebase idToken）Body: `{ phone, password, name, idToken }` |
| POST | `/api/auth/login` | 無 | 使用者登入 Body: `{ phone, password }` 回傳: `{ token, userId, name }` |
| POST | `/api/auth/social-login` | 無 | Firebase 第三方登入 Body: `{ idToken, provider, phone?, name? }`。已綁定者回 200 並登入；首次登入回 201（需完成手機驗證）。**不會再以未驗證的 phone 建立帳號**，全新手機一律改走 `social-signup` |
| POST | `/api/auth/social-signup` | 無 | 第三方帳號「全新手機」註冊：**先**以 Firebase 手機 OTP 驗證（`phoneIdToken`），通過後才建立帳號並綁定社群身分。Body: `{ phoneIdToken, phone, idToken, provider, providerUid?, name? }`；手機已有帳號回 203 |
| POST | `/api/auth/corporate-login` | 無 | **品牌/分店統一登入入口**，自動辨識帳號身分 Body: `{ account, password }` |
| GET | `/api/auth/check-phone` | 無 | 檢查手機是否已註冊 Query: `?phone=09xx` |
| POST | `/api/auth/firebase-verify` | 無 | Firebase Token 預檢 Body: `{ idToken }` |
| POST | `/api/auth/reset-password-firebase` | 無 | 重設密碼 Body: `{ idToken, phone, newPassword }` |
| POST | `/api/auth/merge/set-password` | 無 | 帳號整合：三方帳號設密碼 Body: `{ idToken, phone, newPassword }` |
| POST | `/api/auth/merge/bind-social` | 無 | 帳號整合：傳統帳號綁三方 Body: `{ idToken, phone, providerUid, provider }` |
| PUT | `/api/auth/update` | CUSTOMER | 更新個人資料（暱稱、頭像）。Body: `{ name?, picUrl? }`。**變更手機號碼已停用**（回 400，需走手機驗證流程）。暱稱不可含 `< > " \`` 或控制字元，最多 30 字 |

---

## 2. 公開瀏覽 (Public - 無需登入)

| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/home` | 首頁資料（banners、附近店家）Query: `?lat=&lng=` |
| GET | `/api/stores/map` | 地圖所有營業中店家座標 |
| GET | `/api/stores/nearby` | 附近店家分頁 Query: `?lat=&lng=&brandId=&minRating=&page=1&size=6`。有座標時依距離排序；無座標時 `minRating` 不生效（見 §7）|
| GET | `/api/stores/search` | 關鍵字搜尋品牌/店家（分頁）Query: `?keyword=&lat=&lng=&page=1&size=50`。`size` 上限 200；回傳 `{stores, page, size, total, totalPages, hasNext}`（見 §7）|
| GET | `/api/stores/{storeId}/info` | 店家詳細資訊（評分、營業時間、地址）|
| GET | `/api/stores/{storeId}/v2` | 店家詳情 V2 |
| GET | `/api/brands` | 平台所有已核准品牌列表（待審與退件的品牌不列出，N-28）|
| GET | `/api/brands/{brandId}` | 品牌資訊與旗下分店（未核准的品牌回 404，N-28）|
| GET | `/api/products/{productId}` | 飲品基本資訊 |
| GET | `/api/products/{productId}/customization` | 飲品可選規格與配料 |
| GET | `/api/products/{productId}/customization/v2` | 飲品客製化 V2（ResponseEntity 格式）|
| GET | `/api/products/{productId}/specs` | 飲品規格列表 |
| GET | `/api/products/store/{storeId}` | 分店上架飲品列表（僅 `isEnabled = true`）|
| GET | `/api/location/search` | 地址關鍵字搜尋 Query: `?keyword=台中市西區` |
| GET | `/api/location/cities` | 台灣縣市列表 |
| GET | `/api/location/districts` | 縣市下的區域列表 Query: `?cityCode=TXG` |
| GET | `/api/group-orders/join/{token}` | 透過分享 Token 取得揪團資訊（V1，無需登入）|
| GET | `/api/game-wheel/brands` | 可參與轉盤的品牌列表（無需登入）。回傳 `[{ brandId, brandName, logoUrl }]` |
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
| POST | `/api/orders/{orderId}/cancel` | 取消訂單（僅發起人；狀態 OPEN 或 SUBMITTED）。會退款並還原優惠券，行為與 `cancel/v2` 的整單取消相同 |
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
| POST | `/api/orders/checkout` | 結帳下單。選擇性標頭 `Idempotency-Key`：重送時回放第一次的結果（見 §7）|
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
| POST | `/api/coupons/spin` | 轉盤抽券（每日 1 次）Body: `{ brandId }`。狀態碼見 §7 |
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
| POST | `/api/users/{userId}/change-password` | 修改密碼；回傳 `{ message, token }`。改密碼後其他裝置的舊權杖失效，前端須換上新 token（N13） |
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
| POST | `/api/brand-auth/register` | 無 | 品牌註冊 Body: `{ name, account, password }`。回傳 `{ approvalStatus: "PENDING", message }`，**不發放權杖**；待管理員核准後才能登入（N-28）|
| POST | `/api/brand-auth/login` | 無 | 品牌登入 Body: `{ account, password }`。密碼錯誤回 401；帳號待審或退件回 403（密碼正確後才說明審核狀態，N-28）|
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
| GET | `/api/brand/dashboard/storestatus` | 分店營業狀態統計 回傳: `{ open, closed, abnormal, total }`（`open` 統計的是 `status = active`）|
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
| PUT | `/api/stores/status` | 切換營業狀態 Body: `{ status: active\|closed }`（白名單驗證，其餘值回 400）|
| GET | `/api/stores/settings/profile` | 取得分店完整設定 |
| PUT | `/api/stores/settings/business-hours` | 更新營業時間 Body: `{ openingHours: "09:00-22:00" }` |
| PATCH | `/api/stores/settings/delivery-config` | 更新外送設定 Body: `{ allowsDelivery, maxDeliveryKm, minDeliveryAmount, deliveryStartTime, deliveryEndTime }` |
| GET | `/api/stores/brand/{brandId}` | 透過品牌 ID 查詢分店列表 |

### 訂單管理
| Method | Path | 說明 |
|--------|------|------|
| GET | `/api/stores/orders` | 訂單列表 Query: `?status=OPEN\|SUBMITTED\|...&page=0&size=50`（`size` 上限 200；回傳 `{ orders, page, size, total, totalPages, hasNext }`）|
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

## 6. 其他

| Method | Path | 認證 | 說明 |
|--------|------|------|------|
| POST | `/api/upload` | 登入（任一角色）| 上傳圖片（png／jpg／jpeg／gif／webp），以檔案內容（magic bytes）驗證，5 MB 上限；儲存位置由 `ImageStorageService` 決定（Cloudinary，或本機 `uploads/`）|
| GET | `/actuator/health` | 無 | 健康檢查，只回傳 `{"status":"UP"}`（見 §7）|

---

### 管理員後台（ADMIN，N-28）

所有路徑都需要 `ADMIN` 權杖（`Authorization: Bearer <token>`），登入除外。管理員只能由 `AdminBootstrap` 建立，沒有公開註冊。

| Method | Path | 認證 | 說明 |
|--------|------|------|------|
| POST | `/api/admin/auth/login` | 無 | 管理員登入 Body: `{ username, password }`，回傳 `{ token, role }`。帳號不存在與密碼錯誤回同一則 401 訊息；失敗次數受 `LoginAttemptLimiter` 限流（scope `admin-login`）|
| GET | `/api/admin/brands/pending` | ADMIN | 待審核品牌清單（分頁）Query: `?page=0&size=50`。page 從 0 起算，size 上限 100。回傳 `{ brands: [{ brandId, name, account, createdAt }], page, size, total, totalPages, hasNext }` |
| POST | `/api/admin/brands/{brandId}/approve` | ADMIN | 核准待審品牌，品牌即可登入。品牌不存在回 404；已處理過（已核准或已退件）回 409 |
| POST | `/api/admin/brands/{brandId}/reject` | ADMIN | 退件待審品牌，品牌無法登入。品牌不存在回 404；已處理過回 409 |
| GET | `/api/admin/brands/approved` | ADMIN | 已核准品牌清單（含停用狀態，分頁）Query: `?page=0&size=50`。回傳 `{ brands: [{ brandId, name, account, suspended, suspendedAt }], page, size, total, totalPages, hasNext }` |
| POST | `/api/admin/brands/{brandId}/suspend` | ADMIN | 停用已核准的品牌。品牌與底下所有分店的既有權杖立即失效，推播連線在交易提交後切斷。待審或退件的品牌回 409；已停用回 409；不存在回 404。回傳 `{ brandId, suspended: true, affectedStores }` |
| POST | `/api/admin/brands/{brandId}/reinstate` | ADMIN | 恢復已停用的品牌。停用前的權杖不會因此重新有效，須重新登入。未停用回 409；不存在回 404 |
| GET | `/api/admin/brands/{brandId}/stores` | ADMIN | 某品牌的分店清單（含停用狀態，分頁）Query: `?page=0&size=50`。回傳 `{ stores: [{ storeId, storeName, status, suspended, suspendedAt }], page, size, total, totalPages, hasNext, brandId, brandName, brandSuspended }`。品牌不存在回 404 |
| POST | `/api/admin/stores/{storeId}/suspend` | ADMIN | 停用分店。分店的既有權杖立即失效，推播連線在交易提交後切斷。已停用回 409；不存在回 404 |
| POST | `/api/admin/stores/{storeId}/reinstate` | ADMIN | 恢復已停用的分店，須重新登入。未停用回 409；不存在回 404 |

> 管理員權杖只在 HTTP 有效。WebSocket 的 CONNECT 一律拒絕 ADMIN 權杖。
>
> 停用的效果（stage 6，ADR-0012）：被停用的品牌或分店不出現在公開列表、地圖、搜尋與附近店家；分店與品牌詳情、分店菜單與商品詳情回 404，商品清單、規格與轉盤分類為空，轉盤不能抽獎；登入回 403；既有權杖在下一次請求即被拒絕；新的 WebSocket 連線與訂閱被拒絕，既有訂閱在交易提交後切斷。結帳與團員付款會檢查停用；取消與退款不檢查，已付款的訂單仍可處理。

---

## 7. 共通約定

### 冪等結帳（`Idempotency-Key`）

`POST /api/orders/checkout` 接受選擇性的標頭 `Idempotency-Key`；未帶時行為與舊版相同。

| 情況 | 回應 |
|------|------|
| 格式不合規（需符合 `^[A-Za-z0-9_-]{8,64}$`）| 400 |
| 首次送出且成功（2xx）| 200，結果保存 24 小時 |
| 同一使用者、同一把鍵，成功後重送 | 回放第一次的狀態碼與 body，並加上 `Idempotent-Replayed: true`；不會再次扣款 |
| 同一把鍵，第一次仍在處理中（內容相同）| 409 |
| 同一把鍵，內容與第一次不同（請求指紋不符）| 422，不執行、不改動第一次的結果 |
| 系統忙碌（結帳的使用者鎖取不到）| 503，沒有建單也沒有扣款；釋放此鍵，可稍後以同一把鍵重試 |
| 首次失敗（非 2xx 或例外）| 釋放此鍵，可以用同一把鍵重試 |

- 鍵的範圍是使用者；不同使用者可以使用相同字串，互不影響。
- 請求內容以 JSON 指紋（SHA-256）比對：同一把鍵搭配不同內容回 422，不執行、不改動既有紀錄。舊版紀錄沒有指紋，在過期（最多 24 小時）前仍照舊重播（見 ADR-0005）。

### 轉盤（`POST /api/coupons/spin`）狀態碼

| 狀態碼 | 情況 |
|------|------|
| 200 | 抽獎成功，回傳抽獎結果 |
| 400 | 未選擇品牌（`請選擇品牌`）|
| 409 | 今日已抽過（`你今日已參加過轉盤遊戲`）|
| 422 | 該品牌沒有可抽獎的飲品，或沒有飲品分類（此時不會消耗當日次數）|
| 503 | 同一使用者的抽獎請求正在處理中（使用者鎖 10 秒）|

`GET /api/coupons/spin-status` 回傳 `{ remainSpins: 0 | 1 }`。

### 附近店家（`GET /api/stores/nearby`）

- 分頁：`page` 從 1 開始（小於 1 視為 1，上限 100000）；`size` 介於 1 與 50 之間（超過即截為 50）。回傳 `{ stores, page, size }`。
- **有座標**（`lat` 介於 -90 與 90、`lng` 介於 -180 與 180）：只列出 50 km 內的營業中店家，依距離排序；`minRating` 生效。
- **無座標**：依店家 id 分頁列出營業中店家；**`minRating` 不生效**（評分篩選只存在於距離排序的查詢路徑）。
- 座標超出範圍或不是有限數字：400（`經緯度超出合法範圍`）。

### 即時推播（WebSocket／STOMP）

- 端點：`/ws-cart`（SockJS）。CONNECT 必須帶 `Authorization: Bearer <JWT>`。
- 帳號仍須可使用（與 HTTP 相同規則）：已刪除的帳號、待審或退件的品牌，以及 ADMIN 權杖一律拒絕連線（N-28）。
- SUBSCRIBE 規則：
  - `/topic/order/{orderId}`：顧客，且為該訂單的發起人或參與者。
  - `/topic/group/{token}`：顧客（token 本身即分享憑證）。
  - `/topic/store/{storeId}`：該門市本人（角色 STORE 且 id 相符）。
  - 其他目的地一律拒絕。
- 推播來源為 `messagingTemplate.convertAndSend`，涵蓋訂單狀態、揪團品項異動與門市的新訂單通知。
- 本專案**沒有** SSE（Server-Sent Events）端點。

### 健康檢查

`GET /actuator/health`（公開）只回傳 `{"status":"UP"}`，不揭露細節（`management.endpoint.health.show-details: never`）。其他 Actuator 端點未開放。

### 請求追蹤（`X-Request-Id`）

每個回應都帶 `X-Request-Id`。傳入的合規值（`^[A-Za-z0-9_-]{8,64}$`）會被沿用，否則產生新的 32 位十六進位 id。同一個 id 會出現在後端 log 的 `[requestId]` 欄位，可用來串接前端回報與後端紀錄。

### 錯誤與狀態碼

業務錯誤以 `CustomException` 拋出，其 code 即 HTTP 狀態碼（如 409、422、503），由 `GlobalExceptionHandler` 轉換。500 只回傳泛用訊息，內部例外細節只寫入 log。

### 分頁

- 會隨營運時間累積的列表端點必須分頁（規則見 `CLAUDE.md`）。
- 門市訂單：`GET /api/stores/orders?page=0&size=50`，`size` 上限 200。
- 店家搜尋：`GET /api/stores/search?keyword=&page=1&size=50`，`size` 上限 200。回應的 `total` 是真實總數（COUNT 查詢），`hasNext` 供判斷是否還有下一頁；距離排序時，分頁在 SQL 內以 `LIMIT`／`OFFSET` 完成。
- `page` 在店家搜尋與附近店家都從 1 起算；門市訂單的範例為 `page=0`。三者都把 0 視為第一頁，接 API 時請依各端點的範例。

---

## ⚡ 技術備忘

1. **統一企業登入**：`POST /api/auth/corporate-login` 先嘗試 BRAND 再嘗試 STORE，前端只需一個登入頁面。
2. **優惠券設計**：不再有獨立的 `coupons` 表，優惠券資訊直接存在 `user_coupons`（關聯 brand + product），圖片存於 `product_templates.coupon_image_url`（Java 2D 合成後上傳 Cloudinary）。
3. **分店圖片欄位**：`stores.cover_url`（非舊版 `image_url`）。
4. **分店菜單 toggle**：`store_product_settings.is_enabled=false` 表示不供應；`store_topping_settings.is_out_of_stock=true` 表示配料售完。
5. **訂單評分**：`order_ratings`（星級，可即時更新）管理評分，`order_reviews` 表已停用移除。
6. **轉盤路由**：實際路由為 `/api/coupons/spin`（執行）、`/api/coupons/spin-status`（狀態）、`/api/game-wheel/brands`（品牌列表）、`/api/game-wheel/menu/{brandId}`（轉盤選項），**非** `/api/daily-spin/*`。
7. **Controllers 清單**（14 個）：`UserController`、`GroupOrderController`、`BrandController`、`BrandSpecOrderController`、`StoreController`、`CartController`、`PublicController`、`LocationController`、`CouponController`、`DailySpinController`、`OrderController`、`ProductController`、`UploadController`、`UserFavoriteController`。本專案沒有 SSE 控制器。
8. **即時推播**：WebSocket／STOMP（`/ws-cart`），訂閱規則見 §7。
9. **GroupOrder V2**：V2 路由統一使用 `token` 作為 path variable，支援品項 CRUD、結帳、補款等完整流程。刪除路由為 `/api/group-orders/token/{token}`（加 `token/` 前綴避免衝突）。
10. **常用地址 API**：新增 `/api/users/{userId}/address`（GET）、`/api/users/{userId}/addresses`（POST）、`/api/users/{userId}/addresses/{addressId}`（PUT/DELETE）對應 `user_addresses` 表。
11. **CORS 白名單**：預設為 `localhost:5173`、`127.0.0.1:5500`、`localhost:5500`、`localhost:8082`、`127.0.0.1:8082`（與 `application.yml` 一致）。本機其他來源請放在 `application-local.yml`，或設定環境變數 `CORS_ALLOWED_ORIGINS`。
