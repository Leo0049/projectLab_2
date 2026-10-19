# ADR-0011：待接單逾時自動拒單改由伺服器排程執行

- **狀態**：Accepted
- **日期**：2026-10-19
- **相關**：N-32（`docs/REVIEW_2026-10-11.md`、`docs/REVIEW_2026-10-13.md`）、ADR-0001（列鎖保護金流）、ADR-0002（交易邊界）、ADR-0006（排程互斥）、CLAUDE.md「揪團退款與可見性」

## 背景

逾時拒單原本只存在於門市首頁的前端（`frontend/store/js/api-home.js` 的 `autoRejectExpired`，每秒檢查）。問題有三個：

1. 門市後台沒有開著時，待處理訂單會一直停在待處理，伺服器沒有對應的排程。
2. 瀏覽器版本把 OPEN 的揪團也算進待接單，等於團長還沒送單就把整團拒掉。
3. 拒單的決定由瀏覽器依自己的畫面狀態做出：畫面上已逾時的訂單，實際上可能已被店家接單，兩邊沒有共用的判斷。

在設計退款時，同時發現了既有的退款錯誤，並在同一次修正中處理：

- 個人訂單（錢包）取消時，WAITING_SUBMIT 品項逐筆退款，加上 escrow 整筆退款，重複退一次（一杯 $35 退 $70）。
- 揪團以現金或信用卡結帳後仍留下代墊金，取消時會把從未從錢包扣過的金額退進團長錢包。
- 整單取消時，未付款品項（ESCROWED、WAITING_SUBMIT、UNPAID）的優惠券不還原。
- 店家可以拒絕已接單（PREPARING）的訂單，訂單變成 REJECTED 而款項不退。

## 決策

1. **排程任務**：`OrderAutoRejectJob` 由 `ScheduledTasks` 每 30 秒觸發，經 `ExclusiveJobRunner` 以 `order-auto-reject` 互斥（120 秒租約）。多實例時同一時間只有一個實例掃描。
2. **範圍只限 SUBMITTED**。OPEN 的揪團仍在加點，團長還沒送單，不算待接單，不自動拒單。
3. **時間規則集中在 `AutoRejectPolicy`（純函式）**：計時起點是 `submittedAt`，沒有時退回 `createdAt`；嚴格超過 10 分鐘才逾時，滿 10 分鐘仍可接單。
4. **併發以列鎖與鎖內重判保證**：查詢只是粗篩（一批最多 100 筆）。每筆在自己的交易內以 `findByIdForUpdate` 取列鎖，鎖內再以 `AutoRejectPolicy` 判斷一次，因此掃描之後若店家已接單或顧客已取消，就不會再拒單。店家的接單與拒單與此共用同一把列鎖（`getOrderForStoreForUpdate`）。
5. **退款沿用整單取消流程**（`GroupOrderService.handleGroupOrderCancellation`），不另寫一份。拒單後狀態設為 REJECTED。
6. **每筆一個交易，推播在提交之後**：`OrderAutoRejectJob` 本身不是交易。單筆失敗只記 log，下一輪再試；推播 `/topic/order/{id}`（`REJECTED`）只在該筆交易提交之後送出。
7. **同時修正退款規則**：WAITING_SUBMIT 只由 escrow 退一次；escrow 只有錢包付款才留（`GroupRefundPolicy.escrowAfterCheckout`，與結帳的扣款條件完全相同）；整單取消時所有品項的優惠券都還原；接單後（PREPARING 以後）拒單回 409。
8. **前端只顯示倒數**：只有 SUBMITTED 才有 deadline，逾時只停用接單與拒單按鈕，不再送出拒單請求。首頁卡片的 SUBMITTED 對應 `pending`，不再依 `submittedAt` 誤對應為製作中。
9. **索引** `orders(status, submitted_at)`，供排程查詢使用（prod 為 `validate`，須人工建立）。

## 後果

取得的：

- 待處理訂單不再依賴門市後台是否開著，逾時一律由伺服器處理。
- 拒單、顧客取消與店家接單走同一條退款路徑，規則只有一份。
- 併發下每張單最多退款一次，由列鎖保證；狀態守衛只是第二層。

付出的代價：

- 逾時到拒單之間最多約 30 秒（排程間隔）。這段時間店家仍可透過 API 接單，因為 `acceptOrder` 不檢查時限；前端只是停用按鈕。這是刻意的寬限，若要改為伺服器拒絕，需要產品決定。
- OPEN 的揪團不會被自動拒單，團長不送單就會一直開著。
- 接單後拒單回 409，不退款。若要支援接單後退款，需要產品決定與新的退款規則。
- 歷史資料不會被本次修正改回。上線前已發生的重複退款與代墊金誤退，需要帳本對帳（見 `docs/DEPLOYMENT.md` 的上線檢查）。
- 上線後第一輪排程會拒單所有超過 10 分鐘的舊待處理訂單，每 30 秒最多 100 筆。
- 示範資料的待處理訂單在植入後 10 分鐘會被拒單，演示前需重跑 `scripts/demo-reset.sh`。

## 替代方案

- **維持瀏覽器計時**：拒絕。後台沒人開著時仍不會執行，而且拒單的決定沒有經過伺服器端的判斷。
- **OPEN 的揪團也自動拒單**：拒絕。團長還在加點時會把整團取消。
- **整批掃描放在同一個交易**：拒絕。一筆失敗會回滾其他已完成的退款，而且鎖的持有時間會拉長。
- **在排程內另寫一份退款邏輯**：拒絕。退款規則只能有一個來源（見 CLAUDE.md 的金流約定）。
- **接單後拒單改為退款**：暫不採用，目前回 409，等待產品決定。
- **外部排程器（Quartz 等）**：不需要。專案已有 `@Scheduled` 與 Redis 互斥（ADR-0006）。

## 如何驗證

- 純規則（不載入 Spring context）：`AutoRejectPolicyTest`（邊界、計時起點、狀態範圍）、`GroupEscrowRuleTest`（只有錢包付款會留 escrow）。
- 服務層（Mockito）：`AnalyticsServiceAutoRejectTest`（列鎖、鎖內重判、接單後 409、他店 403）、`GroupOrderServiceCancelRefundTest`（escrow 只退一次、券還原、PREPARING 不退款）、`OrderAutoRejectJobTest`（單筆失敗不影響其他筆、只有真的拒單才推播）、`ScheduledTasksAutoRejectTest`（經 `ExclusiveJobRunner` 執行）。
- 還原驗證（把修補改回舊寫法時測試必須失敗）：見 README「2026-10-19 還原驗證（CI）」。
- 端對端與 UI（CI）：`e2e-verify.js` 驗證取消後 escrow 原數退回、拒單後款項原數退回；`page-sweep` 與各 flow 會載入首頁與訂單頁。
