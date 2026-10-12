# ADR-0003：授權採「預設拒絕的路由白名單」，身分只取自 token，擁有權在服務層檢查

- **狀態**：Accepted
- **日期**：2026-10-11 複審修補時確立；2026-10-12 補錄
- **相關**：ADR-0001、ADR-0005；`CLAUDE.md` 的「授權規則」一節

## 背景

2026-10-11 的複審找到的授權問題，多數來自同一類錯誤：

- **matcher 不分 HTTP method**。`requestMatchers(String...)` 不區分方法，`/api/stores/*` 整段 permitAll 讓 `PUT /api/stores/update` 對外開放，任意分店可被竄改（IDOR）。`/api/products/**` 同樣讓 `POST /api/products` 未登入即可新增商品，而且 `save()` 的 merge 語意可以竄改既有商品的售價。
- **身分來自用戶端**：`@RequestParam authUserId` 可以不帶（不帶就跳過檢查）；路徑上的 `userId` 直接拿去查他人訂單；請求 body 的 `userId` 被用來扣他人餘額。
- **`if (userId != null) 檢查 else 放行`**：不帶參數就能把他人訂單從 READY 改成 COMPLETED。
- **WebSocket 訂閱沒有任何檢查**，而 `orderId` 是連續整數，可以列舉。

## 決策

1. **預設拒絕**：`SecurityConfig` 的最後一條規則是 `anyRequest().authenticated()`。
2. **放行必須明確**：`permitAll` 只能指定 HTTP method 與具體路徑，例如 `requestMatchers(HttpMethod.GET, "/api/products/{productId}")`。寫入端點永遠落到 `authenticated()`。新增 permitAll 時，同步加入 `AuthorizationRouteInventoryTest` 的 `PUBLIC_GET` 或 `PUBLIC_POST` 清單，讓例外在 review 中可見。
3. **身分只來自 token**：`JwtAuthenticationFilter` 驗證 JWT 後，把使用者 id 寫入 request attribute `currentUserId`。顧客端點以 `@RequestAttribute(value = "currentUserId", required = false)` 讀取，不接受任何請求參數。
4. **路徑上的 id 必須比對擁有權**：進入方法後第一件事是 `requireSelf(...)`（`UserController`、`CouponController`、`OrderController` 都有）；訂單相關端點則呼叫 `OrderController.requireOwnedOrder()`。查詢參數中的 `userId` 只為相容舊前端而保留，一律不採信。
5. **角色以 authority 隔離**：顧客、品牌、門市各自的路由以 `hasAuthority` 限定；`/api/stores/**` 兜底要求 STORE 角色，涵蓋所有寫入端點。
6. **WebSocket 同樣依角色與參與關係**：`WebSocketConfig.authorizeSubscribe` 檢查訂閱目的地。principal 必須帶 `role`，因為顧客與門市的 id 會撞號。

## 後果

**取得的**：每條公開路由都是一個可審查的決定。新增端點若漏掉授權，`AuthorizationRouteInventoryTest` 會在 CI 失敗，因為任何非公開路由匿名呼叫都必須得到 401 或 403。

**付出的代價**：
- 白名單需要人工維護。這是刻意的取捨：安全的預設值比開發便利更重要。
- Spring Security 的路徑比對語意與 inventory 測試使用的比對方式可能有細微差異。inventory 測試的正確性要靠 CI 實際執行來確認，這一點列在 `docs/IMPROVEMENT_ASSESSMENT.md` 的「未在本環境驗證」清單中。
- 擁有權檢查分散在服務與控制器中，沒有單一的集中策略。專案若再擴大，應改為方法層級授權（`@PreAuthorize`）加上集中的擁有權判斷；本次不做，列於 ADR-0007。

## 替代方案

- **`@PreAuthorize` 遍布所有端點**：表達力較好，但對 14 支 Controller 的改動面太大，本次不採用。
- **外部政策引擎（OPA、Casbin）**：超出目前專案的規模。

## 如何驗證

- `AuthorizationTest`（19）：未認證的商品寫入、跨帳號讀寫個資／錢包／訂單、偽造參數與不帶參數兩種繞過、拿他人 userId 結帳、竄改 `finalPrice`、跨帳號讀寫收藏、消耗他人優惠券、debug 與門市傾印端點已移除。
- `AuthorizationRouteInventoryTest`（3）：以 `RequestMappingHandlerMapping` 列舉全部路由；非公開路由匿名呼叫必須回 401 或 403；公開清單中每一條都必須真的存在。
- `StompSubscribeRulesTest`（9）：WebSocket 訂閱規則的純函式測試。
- `GroupOrderSecurityTest`（5）、`GroupOrderServiceAuthorizationTest`（6）：揪團相關的擁有權檢查。
