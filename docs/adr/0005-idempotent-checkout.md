# ADR-0005：結帳採選擇性的 `Idempotency-Key`，重送時回放第一次的結果

- **狀態**：Accepted（已知缺口：未比對請求內容，見「後果」）
- **日期**：2026-10-12 補錄（實作於本次文件化前的修補階段）
- **相關**：ADR-0001（最終防線仍是列鎖與狀態守衛）、ADR-0003（身分與金額一律由伺服器決定）、ADR-0006（Redis 與本地退避）

## 背景

結帳是最容易被重送的操作：使用者連點、行動網路逾時後客戶端自動重試、頁面重新整理。沒有冪等機制時，同一筆結帳可能扣款兩次。

結帳端點已經做了兩件事：身分一律取自 `currentUserId`，金額一律由 `OrderService.repriceItems()` 依資料庫重算。但「同一次意圖只能成立一次」仍需要獨立的機制。

## 決策

1. **選擇性標頭**：`POST /api/orders/checkout` 接受 `Idempotency-Key`。未帶時行為與原本相同，相容既有客戶端。
2. **格式**：`^[A-Za-z0-9_-]{8,64}$`，不合規回傳 400。
3. **儲存鍵**：`idem:checkout:<userId>:<key>`。範圍包含使用者，不同使用者使用相同字串互不影響。
4. **狀態機**：
   - 第一次呼叫以 `SETNX` 寫入 `IN_PROGRESS`，TTL 120 秒。
   - 成功（2xx）後，把回應的狀態碼與 body 以 `DONE:<json>` 寫入，TTL 86400 秒（24 小時）。
   - 非 2xx 或拋出例外時釋放鍵，允許重試。
   - 同一把鍵在進行中再次送來：回 409。
   - 已完成：回放第一次的回應，並加上 `Idempotent-Replayed: true` 標頭。
5. **Redis 不可用時**退回本地 Map，只在單實例下正確（見 ADR-0006）。
6. **前端**（`frontend/Customer/checkout.html`）：同一組購物車內容沿用同一把鍵；結帳成功後清除；遇到 4xx（409 除外）時釋放，讓使用者修正後可以重送。

## 後果

**取得的**：重送不會重複扣款，而且第二次的回應與第一次一致。

**付出的代價與已知缺口**：
- **未比對請求內容**。同一把鍵搭配不同的 body，仍會回放第一次的結果。這是有意識的取捨：金額已由伺服器重算，風險被限縮；但 body 指紋（hash）仍應補上，列於 `docs/IMPROVEMENT_ASSESSMENT.md` 的「仍未做」清單。
- 冪等狀態只存在 Redis。Redis 被清空或鍵逾時後，重送會再次執行。
- 標頭是選擇性的，舊客戶端沒有這層保護。

## 替代方案

- **在 `orders` 表加上唯一索引（`idempotency_key`）**：持久性較好，不怕 Redis 失效。但目前沒有遷移工具（ADR-0008），會讓 schema 變更無法安全進行，因此暫不採用。
- **只靠前端防連點**：不足以防網路層的重送。

## 如何驗證

- `IdempotencyServiceTest`（9）：`rejectsMalformedKeys`、`secondCallReplaysTheFirstResult`、`failedAttemptReleasesTheKey`、`exceptionPropagatesAndReleasesTheKey`、`duplicateWhileInProgressIsRejected`、`keysAreScopedPerUser`，以及三支 Redis 路徑的測試（`redisClaimThenStoresTheResult`、`redisReplaysWhenTheKeyIsAlreadyDone`、`redisInProgressReturnsConflict`）。
- `scripts/e2e-verify.js` 的冪等區段：同一把鍵結帳兩次，應得到相同的 `orderId`，第二次帶有 `Idempotent-Replayed`；不合規的鍵回傳 400。
- 結帳頁的實際流程由 `scripts/ui/` 的 UI 流程測試覆蓋。
