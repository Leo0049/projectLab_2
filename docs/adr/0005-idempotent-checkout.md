# ADR-0005：結帳採選擇性的 `Idempotency-Key`，重送時回放第一次的結果

- **狀態**：Accepted（2026-10-17 補上請求指紋比對；舊格式紀錄的過渡期見「後果」）
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
   - 成功（2xx）後，把回應的狀態碼、body 與請求指紋以 `DONE:<json>` 寫入，TTL 86400 秒（24 小時）。
   - 非 2xx 或拋出例外時釋放鍵，允許重試。
   - 同一把鍵在進行中再次送來：內容相同回 409，內容不同回 422。
   - 同一把鍵、內容不同（指紋不符）：回 422，不執行、不改動既有紀錄。
   - 已完成：回放第一次的回應，並加上 `Idempotent-Replayed: true` 標頭。
5. **Redis 不可用時**退回本地 Map，只在單實例下正確（見 ADR-0006）。
6. **前端**（`frontend/Customer/checkout.html`）：同一組購物車內容沿用同一把鍵；結帳成功後清除；遇到 4xx（409 除外）時釋放，讓使用者修正後可以重送。

## 後果

**取得的**：重送不會重複扣款，而且第二次的回應與第一次一致。

**付出的代價與已知缺口**：
- **舊格式紀錄無法比對**。2026-10-17 之前寫入的紀錄沒有指紋；過渡期內（最多 24 小時）同一把鍵搭配不同 body 仍會回放第一次的結果，過期後即完全比對。金額本來就由伺服器重算，風險原本已被限縮。
- 冪等狀態只存在 Redis。Redis 被清空或鍵逾時後，重送會再次執行。
- 標頭是選擇性的，舊客戶端沒有這層保護。

## 替代方案

- **在 `orders` 表加上唯一索引（`idempotency_key`）**：持久性較好，不怕 Redis 失效。但目前沒有遷移工具（ADR-0008），會讓 schema 變更無法安全進行，因此暫不採用。
- **只靠前端防連點**：不足以防網路層的重送。

## 如何驗證

- `IdempotencyServiceTest`（14）：`rejectsMalformedKeys`、`secondCallReplaysTheFirstResult`、`failedAttemptReleasesTheKey`、`exceptionPropagatesAndReleasesTheKey`、`duplicateWhileInProgressIsRejected`、`keysAreScopedPerUser`，以及 Redis 路徑的測試（`redisClaimThenStoresTheResult`、`redisReplaysWhenTheKeyIsAlreadyDone`、`redisInProgressReturnsConflict`），再加上請求內容比對的五支（`fingerprintFollowsTheBodyContent`、`sameKeyWithAnotherBodyIsRejectedAndTheFirstResultSurvives`、`anotherBodyWhileInProgressIsRejectedAs422`、`redisInProgressWithAnotherBodyIsRejectedAs422`、`redisDoneWithAnotherBodyIsRejectedAs422`）。
- `scripts/e2e-verify.js` 的冪等區段：同一把鍵結帳兩次，應得到相同的 `orderId`，第二次帶有 `Idempotent-Replayed`；不合規的鍵回傳 400。
- 2026-10-17 還原驗證（README「2026-10-17 還原驗證（CI）」）：M6 把請求指紋改為常數，使 `IdempotencyServiceTest` 的五支 422 測試轉紅；M5 與 M9 分別確認忙碌時的例外型別與控制器的回應。
- 結帳頁的實際流程由 `scripts/ui/` 的 UI 流程測試覆蓋。

## 2026-10-22 追加（stage 9）：團長結帳與提交後的失敗

- **範圍擴充到團長結帳**：`POST /api/group-orders/{token}/checkout` 同樣接受 `Idempotency-Key`。儲存鍵為 `idem:group-checkout:<token>:<userId>:<key>`，範圍包含團的 token。同一把鍵用在另一團時不會被當成重送；否則請求內容相同時會回放別團的結果。團員付款（`member-checkout`）目前不接受此標頭。
- **提交之後的失敗不回 400**：送單與建單都是在自己的交易內提交，之後才讀取通知資訊、推播。這些步驟失敗時原本會回 400，前端會當成失敗而重送；重送即使帶了鍵也只會得到 400（佔位已釋放，第二次被服務層拒絕），無法回放成功的結果。個人結帳已於 2026-10-12 處理（第 4 項）；團長結帳本次一併修正，現在只記 log，回應仍為成功。
- **前端**：`frontend/Customer/group_order.html` 的團長結帳與 `checkout.html` 相同，內容不變時沿用同一把鍵，只存於記憶體，成功後清除。
- **驗證**：`GroupOrderCheckoutIdempotencyTest`（8，不載入 Spring context）守住重送回放、4xx 釋放、提交後的讀取與推播失敗、422、跨團獨立、未帶鍵與格式檢查。`scripts/ui/flow-group.js` 在團長送出時模擬一次網路中斷，確認重按沿用同一把鍵。還原驗證見 README「2026-10-22 群組結帳冪等還原驗證（CI）」。
