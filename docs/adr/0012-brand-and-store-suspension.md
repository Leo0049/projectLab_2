# ADR-0012：品牌與門市由管理員停用，既有權杖立即失效

- **狀態**：Accepted
- **日期**：2026-10-20
- **相關**：N13（權杖版本，CLAUDE.md「2026-10-15 新增的不變量」）、N-28（品牌審核與管理員角色，ADR-0010）、N12（揪團推播訂閱）、ADR-0003（授權路由白名單）、ADR-0011（排程與列鎖）

## 背景

- N-28 讓管理員可以核准或退件品牌，但沒有停用路徑。已核准的品牌出問題（違規、結束營業）時，只能到資料庫手動改 `approval_status`，而且既有權杖與推播連線不會失效：品牌仍可下單，也仍會收到訂單推播。
- N13 已建立權杖版本（`token_version`），但品牌與分店沒有任何會遞增版本的路徑。CLAUDE.md 記錄的約定是「日後新增停用路徑時必須遞增版本」。本次補上停用，並讓它真的讓權杖失效。
- 停用要涵蓋分店：品牌停用時，底下分店同時不可見、不可登入、不可下單。另外也需要能只停用單一分店。

## 決策

1. **狀態欄位與條件式更新**：`brands.suspended`、`stores.suspended`（`BOOLEAN NOT NULL DEFAULT false`）與 `suspended_at`。停用與恢復都是條件式更新（`UPDATE … WHERE suspended = …`）。受影響列數為 0 時，再以一次讀取區分 404（不存在）與 409（已處理，或狀態不符）。不可改為「讀出、檢查、寫回」。
2. **停用即遞增版本**：停用品牌時遞增品牌的 `token_version`，並以 `bumpTokenVersionsOfBrand` 遞增底下所有分店的版本；停用單一分店時只遞增該分店。既有的 `AccountStatus.isActive` 版本比對擋下 HTTP 與 STOMP 的舊權杖，不需要撤銷清單或額外的 Redis 鍵。
3. **恢復不遞增版本**：停用期間無法簽發新權杖，因此恢復時不遞增，不會讓任何有效權杖失效。反過來，停用前簽發的權杖在恢復後仍是舊版本，會被拒絕，使用者必須重新登入。
4. **可見性只有一份定義**：`StoreVisibility.FILTER`（字面 SQL 條件 `s.status = 'active' AND s.suspended = FALSE AND b.suspended = FALSE`）與 `StoreVisibility.isReachable(…)`（Java 端）。所有公開列出分店的 `@Query` 使用同一個字面值。`StoreVisibilityGuardTest` 以反射掃描 `StoreRepository` 的查詢，漏掉條件的查詢會讓測試失敗。
5. **下單與付款檢查，取消與退款不檢查**：個人結帳（`OrderService.repriceItems`）、揪團的商品驗證（`GroupOrderService.validateActiveStore`）、團員付款與補款，都會拒絕（400）。取消與退款刻意不檢查：已付款的訂單必須仍能結清。
6. **既有推播連線在交易提交後切斷**：停用在交易內發布 `AccountsSuspendedEvent`（帳號清單包含品牌與其分店）。`AccountSuspensionCutListener` 以 `AFTER_COMMIT` 處理，把這些帳號的工作階段登錄為切斷（`StompSessionRegistry`）；`CutSessionOutboundInterceptor` 丟棄送往這些工作階段的訊息。交易回滾時不會切斷。
7. **連線時重新檢查**：`StompSessionListener` 在 CONNECT 後以 `AccountStatus.isActive` 再檢查一次。若停用交易早於登錄，登錄後立即切斷。
8. **管理員介面**：`/api/admin/brands/approved`、`/brands/{id}/suspend|reinstate`、`/brands/{id}/stores`、`/stores/{id}/suspend|reinstate`（需要 ADMIN 權杖）；前端頁面為 `/Admin/managed.html` 與 `/Admin/stores.html?brandId=`。只能停用已核准的品牌：待審或退件的品牌回 409，避免把尚未審核的品牌「停用」成看似有效的狀態。

## 後果

取得的：

- HTTP 的停用立即生效：下一次請求即被 401／403。STOMP 的新 CONNECT 與新 SUBSCRIBE 立即被拒絕；既有訂閱在交易提交後切斷。
- 不需要新的撤銷表、Redis 鍵或排程。版本機制（N13）延伸使用，沒有第二套規則。
- 可見性只有一份字面值，新增列出查詢時有測試守門。

付出的代價：

- **切斷登錄表只在單一實例內**（與 SimpleBroker 相同的限制）。多實例部署時，停用事件只切斷收到它的那個實例上的工作階段；其他實例上既有的訂閱要等連線結束。新的 CONNECT 與 SUBSCRIBE 在所有實例都會被拒絕，因為版本保存在資料庫。若需要所有實例即時切斷，必須另外設計共享的通知機制（例如以 Redis 發布，或改用外部 broker）。本次不做。
- 恢復後必須重新登入。對被停用的使用者不是無痛的，營運端需要事先告知。
- 停用的品牌與分店仍在資料庫中，報表與營運查詢需要自行過濾 `suspended`。

## 替代方案

- **撤銷清單（黑名單）**：每次請求查 Redis 或資料庫。能精準撤銷單一權杖，但要管理 TTL，且與 N13 的版本機制重複。未採用。
- **恢復時也遞增版本**：行為上沒有差別（停用期間無法簽發新權杖），只是多一次寫入。未採用。
- **只擋 HTTP、不切 STOMP**：實作較少，但停用的品牌仍會透過既有連線收到訂單與揪團內容，與「立即失效」的目標不符。未採用。
- **每次請求讀取停用狀態，但不遞增版本**：可以停用，但恢復後停用前的權杖會重新有效，變成「恢復即復活」。未採用。

## 已知缺口

- 收藏清單（`UserFavoriteController`）仍會列出已停用的分店。這只是顯示，無法下單。
- 購物車加入不檢查停用，但結帳會檢查，因此無法完成訂單。
- 停用前已建立的揪團仍可由成員檢視；揪團的商品驗證、團員付款與補款會檢查停用。
- 多實例的即時切斷（見上）。
- 測試缺口（2026-10-20 還原驗證）已於 2026-10-21 補上：停用期間的個人結帳、單人下單、團員付款、補款與揪團建立，由 `StoreSuspensionPaymentGuardTest` 斷言會被拒絕（I、J、K）；`AFTER_COMMIT` 階段由反射固定（G）。交易回滾不切斷沒有整合測試，語意依賴 Spring。這些原本是測試缺口，不是程式錯誤。

## 如何驗證

- 本機：`node --check` 與內嵌腳本檢查通過。以 Chromium 對假的管理員 API 跑管理員頁面的 39 項檢查，並以六組故意破壞確認檢查會轉紅（見 README「2026-10-20 還原驗證（本機）」）。
- Java 測試：`StoreSuspensionIntegrationTest`（真實資料庫：條件式更新、版本、可見性、登入，以及恢復後舊權杖仍失效）、`SuspensionPublicReadsTest`、`SuspensionVisibilityServiceTest`、`StoreLoginSuspensionTest`、`StoreVisibilityTest`、`StoreVisibilityGuardTest`、`StompSessionRegistryTest`、`CutSessionOutboundInterceptorTest`、`StompSessionListenerTest`、`AccountSuspensionCutListenerTest`、`StoreSuspensionPaymentGuardTest`，以及 `AccountStatusTest`、`AdminServiceTest`、`BrandServiceApprovalTest` 的新增案例。
- Java 測試已在 CI 上通過（`ea1531d`，[run 39535734153](https://github.com/Leo0049/projectLab_2/actions/runs/39535734153)，460 個測試、84 個類別，失敗 0）。本機無法執行 Maven，還原驗證改在 CI 上做：十組破壞中六組被具名測試抓到，四組（G、I、J、K）當時沒有任何測試轉紅，列於上方「已知缺口」。2026-10-21 補上測試後重跑，四組全數轉紅（見 README「2026-10-21 補測試後還原驗證（Java，CI）」）；2026-10-20 的結果與各 run 連結見 README「2026-10-20 還原驗證（Java，CI）」。
