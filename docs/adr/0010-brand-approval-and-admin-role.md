# ADR-0010：品牌自助註冊須經管理員審核；管理員為獨立帳號

- **狀態**：Accepted
- **日期**：2026-10-18
- **相關**：N-28（`docs/REVIEW_2026-10-11.md`、`docs/REVIEW_2026-10-13.md`）、ADR-0003（路由白名單）、ADR-0004（登入限流）、N7（帳號可用性）、N13（權杖版本）

## 背景

`POST /api/brand-auth/register` 為 permitAll，任何人都能註冊品牌並建立門市，而門市會出現在公開列表。垃圾帳號與假門市因此可以進入公開畫面。2026-10-16 的決定是採審核制；這份 ADR 記錄實作時的做法與取捨。

## 決策

1. **自助註冊一律建立為 `PENDING`**。`PENDING` 與 `REJECTED` 的品牌不能登入。既有權杖也會被拒絕：`AccountStatus` 的品牌分支要求已核准，HTTP 每次請求與 STOMP 每次 CONNECT／SUBSCRIBE 都經過同一條規則（N7、N13）。
2. **公開內容只含已核准品牌**。`GET /api/brands` 只列 `APPROVED`；`GET /api/brands/{id}` 對未核准的品牌回 404，與不存在的品牌無法區分，避免列舉。
3. **管理員是獨立的 `admins` 資料表**，角色固定為 `ADMIN`，沒有公開註冊。`AdminBootstrap` 在啟動時依 `ADMIN_BOOTSTRAP_USERNAME` 與 `ADMIN_BOOTSTRAP_PASSWORD_HASH`（BCrypt）建立第一位管理員：帳號已存在不覆寫密碼；格式錯誤則啟動失敗。
4. **`/api/admin/**` 需要 `ADMIN` authority**。`POST /api/admin/auth/login` 是唯一的 permitAll，並經過 `LoginAttemptLimiter`（scope `admin-login`，ADR-0004）。
5. **管理員權杖只在 HTTP 有效**。`AccountStatus` 的六參數多載不傳管理員儲存庫，因此 STOMP 一律拒絕 ADMIN。後台不使用推播，多開一個連線入口沒有好處。
6. **核准與退件是條件式更新**：`UPDATE brands SET approval_status = ? WHERE id = ? AND approval_status = 'PENDING'`，同一品牌只會被處理一次。列數為 0 時再以存在性區分 404 與 409。
7. **既有品牌維持可登入**：`approval_status` 的 DDL 預設值為 `'APPROVED'`，正式環境的 ALTER 必須帶同一預設。

## 後果

取得的：

- 未審核的品牌不能登入，也就不能建立門市，公開列表不再出現未經審核的品牌。
- 管理員與顧客、品牌、門市分開存放。授權判斷一律比對角色（與 N13 相同），即使 id 重疊也不會誤判。
- 審核是原子的，兩位管理員同時核准與退件時只有一個結果生效。

付出的代價：

- 管理員審核目前只有 API，沒有後台頁面（列為後續階段）。
- 第一位管理員需要人工設定環境變數並啟動一次；設定中的 BCrypt 雜湊應在建立後從環境移除。
- 管理員沒有改密碼與停用的路徑（與品牌、門市相同的缺口，見 CLAUDE.md 的 N13 約定）；目前停用等同刪除列。
- 上線前必須先執行 DDL。漏掉 `DEFAULT 'APPROVED'` 會讓所有既有品牌登入失敗。
- 退件不會下架既有資料；公開列表靠查詢過濾。新增的對外查詢必須記得過濾。

## 替代方案

- **只在前端隱藏待審品牌**：拒絕。API 仍可直接登入與建立門市，等於沒有防線。
- **管理員與品牌共用 `brands` 表，以旗標區分**：拒絕。`hasAuthority("BRAND")` 的規則與審核欄位會互相牽連，而且兩種帳號的 id 會重疊，授權判斷必須額外比對角色。
- **以邀請碼取代審核**：暫不採用。需要業務決定誰發邀請碼，列於已知技術債。
- **核准採「讀出、檢查、寫回」**：拒絕。兩位管理員同時操作時，後寫者會覆蓋先寫者。
- **管理員權杖也開放 WebSocket**：拒絕（見決策 5）。

## 如何驗證

- 純規則（不載入 Spring context）：`BrandApprovalTest`、`BrandServiceApprovalTest`、`AdminServiceTest`、`AdminBootstrapTest`。
- 帳號與權杖：`AccountStatusTest`、`JwtAuthenticationFilterTest`；`StompAuthInterceptorTest.adminTokenCannotConnect`。
- 授權（載入 Spring context，CI 執行）：`AuthorizationTest`（顧客不可進入後台、管理員可讀待審清單）、`AuthorizationRouteInventoryTest`（登入是唯一的公開 POST）。
- 還原驗證：見 README「2026-10-18 還原驗證（N-28，CI）」。
