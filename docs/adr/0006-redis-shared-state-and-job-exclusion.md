# ADR-0006：Redis 承載跨實例的共享狀態；本地退避只保證單實例；排程以互斥鎖執行

- **狀態**：Accepted
- **日期**：2026-10-12 補錄
- **相關**：ADR-0001、ADR-0004、ADR-0005、ADR-0007

## 背景

系統需要在多個實例之間共享的狀態：分散式鎖、限流計數、冪等紀錄、每日轉盤的一次性標記、購物車的 Redis 結構。另外，`@Scheduled` 會在每個實例上各自觸發，優惠券到期等排程任務若不加控制，會在 N 個實例上各執行一次。

## 決策

1. **Redis 是跨實例共享狀態的來源**。`docker-compose.yml` 提供 Redis 7.4；CI 以 service container 提供同一版本。
2. **`RedisLockService`**：`acquireLock(key, timeoutSeconds)` 以 `setIfAbsent`（即 `SET ... NX`，帶逾時秒數）寫入隨機 token；`releaseLock` 以 Lua 腳本比對 token 後才刪除。這避免了「A 的鎖逾時、B 取得之後，A 的 finally 誤刪 B 的鎖」的問題（M-3 修補）。
3. **本地退避**：Redis 不可用時，鎖與計數退回 JVM 內的 Map，並記錄 warn log。這讓本機開發不需要 Redis 也能啟動，**但只在單實例下正確**。
4. **排程互斥**：`ExclusiveJobRunner.runExclusively(jobName, leaseSeconds, job)` 以 `lock:job:<name>` 取得鎖，取不到就略過。`leaseSeconds` 必須大於任務的最長執行時間。`ScheduledTasks` 與 `CouponExpiryScheduler` 都使用此機制。
5. **這是互斥，不是「每天只跑一次」**：任務很快結束時，另一個實例稍後才觸發，仍可能再跑一次。因此被包住的任務必須是冪等的。例如「把已過期的券標記為 expired」重跑沒有副作用。
6. **每日轉盤**：`lock:spin:<userId>`（10 秒）防止同一使用者併發抽獎；`spin:done:<userId>:<yyyy-MM-dd>` 作為當日已抽的標記，TTL 到當天午夜。

## 後果

**取得的**：Redis 可用時，多實例下的排程、結帳與轉盤不會因重複執行而出錯。

**付出的代價**：
- Redis 故障時系統會**靜默降級**為單實例語意，只留下 warn log。多實例部署若 Redis 失效，鎖與限流都會失效。目前沒有對此狀況的告警，列為待辦。
- 轉盤的當日標記在資料庫寫入之前寫入。若資料庫寫入失敗，使用者會失去當天的機會。這是已知缺口，見 `docs/IMPROVEMENT_ASSESSMENT.md`。
- 鎖的 lease 設太短，任務尚未完成鎖就會被其他實例取得；設太長，實例當機後其他實例要等較久才能取得鎖。

## 替代方案

- **資料庫鎖（`SELECT ... FOR UPDATE` 或專用資料表）**：可以借用資料庫的可靠性，但會讓排程互斥依賴資料庫連線。金流本來就用資料庫列鎖（ADR-0001），其餘共享狀態放在 Redis 較為輕量。
- **分散式協調服務（ZooKeeper、etcd）**：超出目前專案的規模。

## 如何驗證

- `ExclusiveJobRunnerTest`（3）：`skipsWhenAnotherInstanceHoldsTheLock`、`runsOnceAndReleasesWithTheSameToken`、`releasesTheLockWhenTheJobThrows`。
- `LoginAttemptLimiterTest` 與 `IdempotencyServiceTest` 中有 Redis 與本地兩條路徑的測試（見 ADR-0004、ADR-0005）。
- 尚無獨立的 Redis 整合測試；Testcontainers 導入列為延後事項（ADR-0007）。
