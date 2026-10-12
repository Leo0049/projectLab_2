# ADR-0001：金流以資料庫列鎖序列化「讀取—計算—寫回」

- **狀態**：Accepted
- **日期**：決策於 2026-10-11 複審修補時做出；2026-10-12 補錄為 ADR
- **適用範圍**：錢包餘額（`users.balance`）、揪團金流（`group_orders`、`order_items`）、評分彙總（`stores.avg_rating`、`review_count`）、優惠券消耗（`user_coupons`）
- **相關**：ADR-0002、ADR-0005、ADR-0007（延後的樂觀鎖與條件式 UPDATE）

## 背景

專案的金流路徑都是「讀出狀態 → 在程式裡計算 → 寫回」（read-modify-write）。沒有鎖時，併發請求會讀到同一個舊值，各自寫回，最後一個寫入覆蓋前面的結果。修補前的實測（記錄於 `CLAUDE.md` 與 `docs/REVIEW_2026-10-11.md`）：

- 20 個併發儲值、每筆 10 元：只入帳 70 元，但帳本總額為 120 元，帳本與餘額對不起來。
- 團員同時送出結帳：帳本寫了 −175，餘額只扣了 35。
- 團長同時送出結帳（8 個併發）：全部成功，扣了 8 次。
- 取消揪團：託管款退了 280，應該只退一次。
- 12 個併發評分：只有 2 筆成功，其餘 10 筆死鎖；改用 Java 端 `count` 再寫回時，12 筆評分只算出 4 筆。

對含金流的系統，這些都是無法對帳的錯誤，必須在資料層擋住，不能寄望於流量小而不會發生。

## 決策

1. **餘額異動只經過兩個入口**：`TransactionRecordService.updateStoreCredit(...)` 與 `UserProfileService.topUp(...)`。兩者都以 `UserRepository.findByIdForUpdate`（`@Lock(PESSIMISTIC_WRITE)`，即 `SELECT ... FOR UPDATE`）取得列鎖。其他程式不得自行讀寫 `balance`。

2. **鎖定後重讀，不能只靠列鎖**：若呼叫端先讀過同一個 `User`，它已在 persistence context 中，`findByIdForUpdate` 雖然取得了列鎖，Hibernate 仍會回傳一級快取中「上鎖之前」的實例。因此取得鎖之後以 `entityManager.refresh(user, PESSIMISTIC_WRITE)` 重讀。不能用普通的 `refresh()`：MySQL 預設的 REPEATABLE READ 下，普通 SELECT 讀的是交易快照，仍會得到舊值；只有鎖定讀會看到最新已提交版本。

3. **狀態檢查必須在上鎖之後**：
   - 揪團結帳：`GroupOrderRepository.findByShareTokenForUpdate`，鎖定後才做狀態守衛。
   - 團員結帳與補款：`OrderItemRepository.findByGroupOrderAndUserAndStatusForUpdate`。
   - 取消退款：`GroupOrderRepository.findByIdForUpdate` 加上 `OrderItemRepository.findByGroupOrderIdForUpdate`。
   - 取消流程在取得列鎖後，必須再以 `OrderStatusGuard.isOpenOrSubmitted` 檢查狀態；在鎖外做的檢查無法保證當下狀態。

4. **優惠券以條件式 UPDATE 原子消耗**：`UserCouponRepository.markUsedIfUnused`（`UPDATE ... WHERE status = 'unused'`，受影響列數為 0 即代表已被用掉）。它必須在修改任何品項**之前**執行，輸的那一方才不會已經改了資料才發現券沒了。

5. **評分彙總的鎖順序**：先 `StoreRepository.findByIdForUpdate` 鎖門市列，再寫入 `order_ratings`；彙總以單一 `UPDATE ... (SELECT COUNT/AVG ...)`（`StoreRepository.refreshRatingAggregate`）完成。順序反過來時，外鍵會在門市列上加共享鎖，後續的 UPDATE 要升級為排他鎖，兩個交易互相等待而死鎖。

## 後果

**取得的**
- 併發下帳本總額與餘額必須相符，這是可以被測試斷言的不變量（`WalletConcurrencyTest`）。
- 每條金流路徑都有對應的併發測試，重複觸發時只會發生一次（`GroupCheckoutConcurrencyTest`）。

**付出的代價**
- 同一位使用者的餘額異動、同一個揪團的結帳會被序列化。熱門揪團的吞吐量受限於列鎖的持有時間；目前的規模可接受，若要更高吞吐量需另行設計。
- 取得鎖的順序必須一致，否則會死鎖。評分的「先鎖門市、後寫評分」就是靠這條紀律維持的。新增金流路徑時，必須寫明鎖的順序並加入併發測試。
- 列鎖只在單一 MySQL 內有效。若未來拆成多個資料庫，這個方案就不再成立。

## 替代方案

- **樂觀鎖（`@Version`）**：衝突時需要重試，而熱門列的衝突會很頻繁。目前不採用，列於 ADR-0007。
- **餘額條件式 UPDATE**（`UPDATE users SET balance = balance - ? WHERE id = ? AND balance >= ?`）：扣款的餘額檢查可以在單一語句內完成，理論上更好。尚未採用，因為儲值、託管、退款等多條路徑都要改寫；列於 ADR-0007。
- **資料庫 SERIALIZABLE 隔離等級**：全域的代價過高，仍需要處理交易重試。不採用。

## 如何驗證

- `WalletConcurrencyTest`（3）：併發儲值不可短少；帳本與餘額必須相符；併發扣款不可透支；列鎖不得被一級快取架空。
- `GroupCheckoutConcurrencyTest`（6）：揪團四條金流路徑重複觸發時只能發生一次；同一張券不可用兩次。
- `RatingConcurrencyTest`（2）：併發評分不得死鎖，門市則數與平均分數要對得起來。

這些測試需要 MySQL（`docker compose up -d`），由 CI 的 `mvn test` 層執行。修補時的回歸驗證方式（把修補改回舊寫法，確認測試會失敗）記錄於 `CLAUDE.md`。
