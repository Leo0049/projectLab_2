# ADR-0007：延後決策登記冊

- **狀態**：Deferred（登記，不是已採用的決策）
- **日期**：2026-10-12

## 目的

把「知道要做、但這一輪不做」的事情寫清楚：為什麼延後、目前的風險是什麼、在什麼條件下要重新評估。這樣延後的事項不會被誤認為已完成，也不會被遺忘。

## 登記

| 項目 | 目前狀態 | 延後的理由 | 重新評估的時機 |
|---|---|---|---|
| JWT 改存 httpOnly cookie，並加上 CSRF 防護與 CSP 強制執行 | JWT 存於 `localStorage`，以 Bearer 送出。CSP 由 `app.security.csp.enabled` 控制，預設關閉；其餘安全標頭（`X-Content-Type-Options`、`frame-ancestors`、`Referrer-Policy` 等）已由 `SecurityConfig` 加上。 | 前端大量使用 `innerHTML` 與 inline event handler，直接強制 CSP 會讓頁面整頁壞掉。改 cookie 需要同時處理 CSRF 與登出流程。 | 前端完成 inline handler 外部化後，先以 report-only 上線 CSP，再評估 cookie。 |
| 大型類別拆分 | `GroupOrderService` 1,610 行、`OrderService` 925 行、`UserController` 758 行 | 拆分會動到金流路徑，應在測試網完整之後再做。本次已把三條與金額、防竄改有關的規則抽成純函式（`service/order/`）。 | 每次修改這些類別時，順手抽出一段有測試的規則。 |
| WebSocket 改用外部 STOMP broker（relay） | 使用記憶體內的 simple broker | 單實例足夠；多實例時推播不會跨實例送達。 | 部署到多實例之前必須完成。 |
| Testcontainers | 測試依賴 `docker compose up -d` 的 MySQL 與 Redis；CI 以 service container 提供 | 導入需要調整測試基底類別與 CI 時間。 | 需要在沒有 Docker 的環境執行測試時。 |
| Maven Wrapper（`mvnw`） | 無；需要系統安裝 `mvn` | 需要從 Maven Central 下載 wrapper，本次的開發環境無法產生。 | Maven 版本需要在團隊間鎖定時。 |
| 密碼政策 | 8–16 字元 | 屬產品決策。若提高上限，要注意 BCrypt 只會使用前 72 bytes。 | 產品決定政策之後。 |
| 地圖視窗查詢 | 附近店家以 50 km 半徑與分頁查詢；地圖頁面未依可視範圍查詢 | 需要前端配合，目前資料量小。 | 店家數量明顯增加時。 |
| 伺服器端快取 | 無 | 目前沒有量測到的瓶頸；快取會引入失效問題。 | 壓測證明熱點查詢是瓶頸時。 |
| 前端資產建置流程 | 靜態 HTML，第三方函式庫手動放在 `frontend/vendor/` | 專案規模小，建置工具會增加入門成本。 | 頁面數量或共用程式碼明顯增加時。 |
| `Map<String, Object>` 回傳型別 | 約 311 處（`grep -rhoE 'Map<String,\s*Object>' src/main/java`） | 逐一改為 record 或 DTO 會牽動大量前端讀取點。 | 修改對應端點時順手替換。 |
| UI 測試的固定等待 | `scripts/ui/` 中有 34 處 `waitForTimeout` | 改為條件等待需要逐一確認每個流程的就緒條件。 | 該流程測試出現不穩定時。 |
| `/api/stores/search` 分頁 | 未分頁 | 搜尋結果的上限尚未定義，需要產品決定。 | 搜尋結果量增加或壓測顯示問題時。 |
| 揪團通知改為 AFTER_COMMIT | 通知在交易內送出 | 需要調整事件流程；目前沒有觀察到實際錯誤。 | 交易回滾後仍收到通知的案例出現時。 |
| 規則檔（`CLAUDE.md`）拆分 | 單一檔案 | AI 協作的規則與人類文件的載入方式需要先確定，否則規則可能失效。 | 規則檔超出可讀範圍時。 |
| 資料庫 schema 遷移 | `ddl-auto: update` | 見 ADR-0008。 | 準備正式上線時。 |
| 餘額條件式 UPDATE 與 `@Version` | 未採用 | 見 ADR-0001。 | 併發失敗率或鎖等待時間成為問題時。 |
| 轉盤的當日標記與寫入順序 | 標記先寫、資料庫後寫；失敗時不回收標記 | 修正需要把標記與寫入放進同一個可回滾的單位，設計上需要權衡。 | 有使用者實際遇到「扣了機會卻沒有獎」的情況時。 |
