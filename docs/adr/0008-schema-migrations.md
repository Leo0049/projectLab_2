# ADR-0008：資料庫結構變更；目前由 `ddl-auto: update` 管理，正式上線前改用 Flyway

- **狀態**：Deferred（路徑已定，尚未實作）
- **日期**：2026-10-12
- **相關**：`DATABASE.md`、`application.yml`、ADR-0005（冪等的持久化替代方案會需要 schema 變更）

## 背景

- `spring.jpa.hibernate.ddl-auto` 目前是 `update`。Hibernate 會依 Entity 新增資料表與欄位，但**不會**刪除或改名，也不會處理資料遷移。
- `DATABASE.md` 是手寫的對照文件。Entity 是 schema 的唯一事實來源，兩者需要人工同步，已經有不一致之處，例如 `user_coupons` 的生成欄位（詳見 `DATABASE.md`）。
- `CLAUDE.md` 要求正式環境改用 `validate` 或 `none`，但目前沒有版本化建立 schema 的方法，正式環境無法安全地啟動。

## 決策（路徑，尚未實作）

1. **開發與 CI 維持 `update`**，讓 Entity 的改動立即生效，降低開發成本。
2. **正式上線前**：
   - 以目前的結構匯出一份 MySQL 8 DDL，作為 `V1__baseline.sql`。
   - 引入 Flyway。之後每一次結構變更都以新增 `V<n>__<說明>.sql` 的方式完成，不修改既有的遷移檔。
   - 正式環境設定 `ddl-auto: validate`，讓 Hibernate 只檢查、不修改。
   - CI 以空白資料庫執行 Flyway 遷移，再以 `validate` 啟動一次，確認 Entity 與遷移腳本一致。
3. **`DATABASE.md`** 導入後以遷移檔為準，文件只保留說明與索引。

## 後果

**取得的**：結構變更可審查、可重現；需要回復時以新的遷移向前修補。

**付出的代價**：
- 每次修改 Entity 都要寫遷移腳本，開發負擔增加。
- 遷移與 Entity 的一致性需要 CI 檢查，否則會再次漂移。
- 現有的文件與結構漂移（例如 `user_coupons`）必須在建立基線之前釐清。

## 替代方案

- **Liquibase**：功能相近，設定較繁瑣。以本專案的規模，Flyway 較直接。
- **手動 SQL 腳本、不引入工具**：容易漂移，不採用。
- **永遠使用 `update`**：正式環境不可接受。

## 如何驗證

目前沒有遷移測試。導入時的驗收條件：空白資料庫遷移成功；以 `validate` 啟動成功；遷移後的結構與 `V1` 基線比對一致。
