# 架構決策紀錄（ADR）

這個目錄記錄 JoinDrink 的重要技術決策：為什麼這樣做、付出什麼代價、什麼時候應該重新評估。

| 編號 | 標題 | 狀態 | 主題 |
|---|---|---|---|
| [0001](0001-pessimistic-locking-for-money.md) | 金流以資料庫列鎖序列化「讀取—計算—寫回」 | Accepted | 一致性、金流 |
| [0002](0002-open-session-in-view-off.md) | 關閉 Open Session In View，禁止從 Controller 回傳 entity | Accepted | 持久層、交易邊界 |
| [0003](0003-authorization-route-allowlist.md) | 預設拒絕的路由白名單，身分只取自 token | Accepted | 安全、授權 |
| [0004](0004-client-ip-trust-and-rate-limits.md) | 用戶端 IP 只取 `getRemoteAddr()`，登入與 OTP 限流 | Accepted | 安全、部署 |
| [0005](0005-idempotent-checkout.md) | 結帳採選擇性的 `Idempotency-Key` | Accepted（已知缺口） | 可靠性、API |
| [0006](0006-redis-shared-state-and-job-exclusion.md) | Redis 承載共享狀態；排程互斥 | Accepted | 多實例、併發 |
| [0007](0007-deferred-decisions.md) | 延後決策登記冊 | Deferred | 治理 |
| [0008](0008-schema-migrations.md) | 結構變更改用 Flyway（上線前） | Deferred | 資料庫、部署 |

## 格式

每篇 ADR 包含：背景、決策、後果（取得的與付出的代價）、替代方案、如何驗證。狀態使用 `Proposed`、`Accepted`、`Deferred`、`Superseded`。

## 說明

- 第 0001–0006 篇是在 2026-10-12 補錄的。決策多數在 2026-10-11 的複審與修補中做出，文件補記當時的理由與代價，沒有改寫歷史。
- 第 0007 篇是延後事項的登記冊，列出的項目沒有被採用。
- 修補的過程與實測數據見 [`docs/REVIEW_2026-10-11.md`](../REVIEW_2026-10-11.md)；改善項目的評估與取捨見 [`docs/IMPROVEMENT_ASSESSMENT.md`](../IMPROVEMENT_ASSESSMENT.md)；問題紀錄見 [`docs/ISSUE_LOG.md`](../ISSUE_LOG.md)。
