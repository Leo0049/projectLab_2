# ADR-0002：關閉 Open Session In View，禁止從 Controller 回傳 JPA entity

- **狀態**：Accepted
- **日期**：2026-10-12 補錄（修補過程中多次踩到的問題，決策在修補時確立）
- **相關**：ADR-0001、ADR-0003

## 背景

Spring Boot 預設 `spring.jpa.open-in-view = true`，會讓 EntityManager 的 session 一直撐到 view 渲染完成。對 REST API 而言，「view 渲染」就是 JSON 序列化。這個預設有三個問題：

1. LAZY 關聯在 Controller 與序列化階段仍然可以觸發查詢，N+1 被掩蓋，開發時看不出來。
2. 資料庫連線在整個請求期間被佔住，包括與資料庫無關的工作。
3. 交易邊界不清楚：一段查詢屬於哪個交易，取決於 Controller 何時碰到某個關聯。

## 決策

- `application.yml` 設定 `spring.jpa.open-in-view: false`，並保持關閉。
- 需要關聯資料的組裝，在 Service 的交易內完成（`@Transactional(readOnly = true)`），回傳的是純 DTO 或 Map。
- Controller 不回傳 JPA entity，也不把 entity 拿出 Service 之後再轉換。
- 需要關聯時優先用 `@EntityGraph` 一次載入（例如 `findWithStoreAndInitiatorById`），而不是在 Controller 中碰 LAZY 屬性。
- 以 `spring.jpa.properties.hibernate.default_batch_fetch_size: 50` 作為 N+1 的保險。

## 後果

**取得的**：N+1 與連線佔用成為程式碼可以看見、可以審查的問題，而不是被 OSIV 悄悄遮住。

**付出的代價**：`LazyInitializationException` 只會在執行期出現，而且通常是在特定資料組合下。規則檔記錄的同類錯誤，在多個端點反覆出現（例如下單完成頁、揪團成員清單）。這類錯誤的特徵是：單獨 curl 端點可能通過（資料剛好沒有那個關聯），要照使用者的流程實際打過才會現形。

因此本決策必須搭配：

- UI 流程測試與 API 端對端腳本（CI 的第二、三層）。
- 改動序列化或關聯相關程式碼後，對所有 GET 端點做一次 5xx 普掃。

## 替代方案

- **保持 OSIV 開啟**：寫起來最省事，但正是掩蓋問題的原因。不採用。
- **全面改為 DTO 投影查詢**：最乾淨，但改動面太大。目前只在踩到問題的端點局部實施，全面改寫列為後續工作。

## 如何驗證

- 沒有單元測試直接守住此規則。由 `scripts/e2e-verify.js` 與 `scripts/ui/run-all.js` 在 CI 中實際打端點、走完整流程來發現。
- 「不要從 Controller 直接回傳 JPA entity」列於 `CLAUDE.md` 的不變量，作為 code review 的檢查清單。
