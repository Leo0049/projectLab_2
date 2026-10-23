# 正式環境部署指南

本指南描述目前 repository 可以支援的後端部署方式與上線前限制。正式環境使用 `prod` profile；根目錄的 `docker-compose.yml` 只啟動本機開發用 MySQL 與 Redis，使用預設帳密，且連接埠只綁定 `127.0.0.1`，不是正式環境部署範本，也不會啟動 Spring Boot。

## 目前部署範圍與上線限制

- **後端**可打包成 Java 17 可執行 JAR。正式 profile 會關閉示範資料、模擬儲值與 SQL 輸出，並以 `ddl-auto: validate` 驗證 schema。
- **尚無版本化資料庫 migration**。正式資料庫必須先由部署者透過已審核的 migration 建立或升級；空資料庫不會由 `prod` profile 自動建表。
- **前端尚未可直接部署到正式網域**：目前品牌、門市頁面及部分登入頁仍有 `http://localhost:8082` API 位址。上線前需統一改為可部署的 runtime API 設定或同源路徑，並對正式網域完成 UI 流程驗證。一般使用者瀏覽器中的 `localhost` 指向使用者自己的電腦。
- MySQL JDBC URL 目前使用 `useSSL=false`，Redis client 也沒有 TLS 設定。兩者只應放在受控私有網路或加密隧道內，不可直接暴露至公網。

## 必要服務與設定

準備 Java 17、Maven、MySQL 8 與 Redis 7。資料庫和 Redis 請使用獨立正式環境服務、限制網路來源，並建立專用帳號；正式環境必填值見 [.env.prod.example](../.env.prod.example)：

- `SPRING_PROFILES_ACTIVE=prod`：**必填**。沒有它，prod 防呆（JWT 密鑰、SMS 模式、示範資料）都不會生效，`application-prod.yml` 也會被忽略。
- `JWT_SECRET`：至少 64 個 UTF-8 位元組的隨機密鑰。prod profile 若仍使用空值或內建開發密鑰，應用會拒絕啟動。
- `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`：正式 MySQL 連線。不得使用 compose 的 `joindrink` / `joindrink` 開發帳密。
- `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`：正式 Redis 連線與密碼；Redis 不可公開暴露。
- `REDIS_KEY_NAMESPACE`：**prod 必填**（ADR-0013）。每個環境各自一個值，例如 `prod`、`staging`；小寫英文開頭，之後為小寫英數與連字號，最長 32 字元。未設定時應用程式拒絕啟動。**首次設定會讓既有的 Redis 鍵全部改名**：部署當天已抽過轉盤的使用者可以再抽一次，部署前 24 小時內的結帳冪等紀錄不再回放，進行中的結帳鎖與排程鎖失效，登入與 OTP 的限流計數歸零。建議在低峰時段部署，並確認沒有進行中的結帳。`fbtoken:`（手機驗證權杖的單次使用紀錄）不受影響。詳見 `docs/adr/0013-redis-key-namespace.md`。
- `CORS_ALLOWED_ORIGINS`：前端正式來源的完整 origin，包含 `https://`，多筆以逗號分隔。未設定時會退回 `application.yml` 的預設值（只含本機來源），因此正式環境**必須**設定。
- `SERVER_FORWARD_HEADERS_STRATEGY`（預設 `native`）：後端如何信任反向代理送來的 `X-Forwarded-*`，見下方「反向代理與來源 IP」。
- `DEMO_DATA_ENABLED=false`、`WALLET_MOCK_TOPUP_ENABLED=false`：正式環境不可植入示範帳號或開啟未付款儲值；後端也會強制拒絕 prod 模式的模擬儲值。
- `prod` profile 將 `ddl-auto` 設為 `validate`：應用只檢查結構、不修改。repo 目前**沒有 migration 腳本或工具**（見 `docs/adr/0008-schema-migrations.md`）。首次部署前必須以人工方式建立與 Entity 一致的結構，例如在相同版本的開發資料庫以 `ddl-auto: update` 啟動一次、匯出 DDL 作為基線，檢查後再套用到正式庫；之後每次結構變更同樣需要人工套用。若有明確需求要覆寫，Spring 環境變數名稱是 `SPRING_JPA_HIBERNATE_DDL_AUTO`。
- `SMS_MODE=firebase` 與 `FIREBASE_CONFIG_PATH`：**必填**。`sms.mode` 的預設是 `firebase`（fail-closed）。若誤設為 `mock`，mock 模式會把 `MOCK_TOKEN` 當成「手機已驗證」，公開的重設密碼端點因此能接管任何帳號；`AuthService` 在 prod profile 下一律拒絕啟動（不是默默回退）。另請設定可讀取的服務帳戶 JSON 檔案路徑，不要把 JSON 放入 repository 或 JAR。
- `RESET_PASSWORD_URL`：正式密碼重設頁網址。
- `ADMIN_BOOTSTRAP_USERNAME`、`ADMIN_BOOTSTRAP_PASSWORD_HASH`：**只在首次建立管理員時設定**（N-28）。密碼必須是 BCrypt 雜湊（以 `$2a$`、`$2b$` 或 `$2y$` 開頭），不接受明文；格式錯誤會讓應用程式啟動失敗。帳號已存在時不會覆寫密碼。建立後請從環境變數移除這兩個設定。
- 可選：`COUPON_TEMPLATE_URL`（優惠券圖片底圖）、`JWT_EXPIRATION`（預設 86400000 毫秒，即 24 小時）、`SERVER_PORT`（預設 8082）。
- Cloudinary 憑證可選（`CLOUDINARY_CLOUD_NAME`、`CLOUDINARY_API_KEY`、`CLOUDINARY_API_SECRET`）；若不設定，圖片會寫入 `UPLOAD_LOCAL_DIR` 預設的 `uploads/`。使用本機儲存時，請掛載持久磁碟並納入備份。

`.env.prod` 已列入 `.gitignore`。請把填妥的設定放在部署主機的 secret store 或權限受限目錄，勿提交至 Git，也勿將該檔打包進 JAR。

## 建置與啟動後端

以下命令適用於 Linux 部署主機；請在資料庫結構建立完成後執行：

```bash
cp .env.prod.example .env.prod
chmod 600 .env.prod
# 編輯 .env.prod，填入所有正式值

cp src/main/resources/application-prod.yml.example src/main/resources/application-prod.yml
mvn -B test # 使用與 CI 相同的隔離測試資料庫，不要連正式資料庫
mvn -B package -DskipTests
```

`application-prod.yml` 是被 Git 忽略的 profile 設定，必須在打包前複製，JAR 才會包含正式設定。`mvn test` 需要可連線的 MySQL 與 Redis；使用隔離的測試資料庫，不要把正式 `.env.prod` 載入測試流程。

互動式測試啟動可載入環境檔（其中的 `SPRING_PROFILES_ACTIVE=prod` 會啟用正式 profile）：

```bash
set -a
. ./.env.prod
set +a
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

正式服務請以 systemd、容器平台或主機 secret manager 管理環境變數，避免依賴互動式 shell。systemd 可將受限權限的環境檔放在 repository 外，再於 unit 設定 `EnvironmentFile=/etc/joindrink/joindrink.env` 與 `ExecStart=/usr/bin/java -jar /opt/joindrink/demo-0.0.1-SNAPSHOT.jar`。環境需包含 `SPRING_PROFILES_ACTIVE=prod`。

應用預設監聽 `8082`。正式入口應由 HTTPS reverse proxy 提供，僅公開代理層；代理 WebSocket 時也需支援 `Upgrade`，後端 STOMP endpoint 為 `/ws-cart`。後端 CORS origin 必須和瀏覽器實際載入前端的 origin 一致。

## 反向代理與來源 IP

- 應用程式**不解析** `X-Forwarded-For`。登入與 OTP 的限流以 `getRemoteAddr()` 為準（見 `docs/adr/0004-client-ip-trust-and-rate-limits.md`），因此偽造的標頭不會改變識別。
- `server.forward-headers-strategy`（環境變數 `SERVER_FORWARD_HEADERS_STRATEGY`）決定容器是否採用代理送來的 `X-Forwarded-*`：
  - `native`（預設）：由 Tomcat 的 RemoteIpValve 處理，只信任來自內部代理網段的標頭。內部代理網段由 `server.tomcat.remoteip.internal-proxies` 決定，預設為私有網段與 127.0.0.1。
  - `framework`：由 Spring 的 ForwardedHeaderFilter 處理。
  - `none`：忽略轉送標頭。直接對外、沒有代理時使用。
- 建議部署方式：代理與後端位於私有網路；代理必須**覆寫** `X-Forwarded-For`（不可附加使用者送來的值）；後端埠口 `8082` 不直接對外。
- 若代理不在內部代理網段內，`native` 不會採用它的標頭，所有使用者都會被視為同一個來源 IP，限流會誤傷正常使用者。此時應調整代理位置，或明確設定 `server.tomcat.remoteip.internal-proxies`。

## Docker 映像與健康檢查

- 建置：於 repository 根目錄執行 `docker build -t joindrink:<tag> .`。多階段建置：Maven 產出 jar，執行階段只含 JRE、jar 與 `frontend/`（靜態頁面由後端提供，位於映像內的 `/app/frontend`）。
- 不會進入映像的內容（見 `.dockerignore`）：`.git`、`.github`、`docs/`、`scripts/`、`.env*`、`serviceAccountKey.json`、`application-local.yml`、本機 `uploads/`、建置產物。
- 映像**不含**資料庫與 Redis，兩者需由外部提供。`.env.prod` 中的 `DB_HOST`、`REDIS_HOST` 必須是容器可以連到的位址，不能寫 `localhost`（在容器內那指的是容器自己）。
- 執行範例：

  ```bash
  docker run --rm -p 8082:8082 \
    --env-file .env.prod \
    -v joindrink-uploads:/app/uploads \
    joindrink:<tag>
  ```

- 容器以非 root 的 `app` 使用者執行。本機上傳目錄為 `/app/uploads`，需要保留圖片時請掛載 volume。
- 健康檢查：`GET /actuator/health` 只回傳 `{"status":"UP"}`，可作為容器平台或負載平衡器的探測端點，例如 `curl -fsS http://127.0.0.1:8082/actuator/health`。
- CI 的 `docker` 工作只建置映像、不推送，也不帶任何憑證。

## 安全標頭與 CSP

後端（Spring Security）固定送出下列標頭：`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: strict-origin-when-cross-origin`、`Permissions-Policy`，並在 HTTPS 請求上送出 HSTS。

`Content-Security-Policy` 預設**不送出**（`app.security.csp.enabled=false`）：本專案頁面大量使用 inline event handler，`script-src` 必須含 `'unsafe-inline'` 才能運作，而那樣的政策對「注入 inline handler」這類 XSS 保護有限，寫錯白名單卻會直接弄壞頁面。要啟用請依下列順序：

1. 先把 `frontend/` 內的 inline handler 改成外部檔案（這是收緊 CSP 的前提）。
2. 在該環境實際點過地圖、點餐、揪團、社群登入與 WebSocket 流程。
3. 設定 `CSP_ENABLED=true` 與 `CSP_POLICY`（或 `app.security.csp.policy`），範例：

```
default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
img-src 'self' data: blob: https:; connect-src 'self' https://nominatim.openstreetmap.org wss:;
object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'
```

⚠️ 前端頁面若由 nginx／Caddy 等靜態伺服器直接提供（**不經過後端**），後端的標頭不會套用到它們，必須在該層自行設定同一組標頭（含 `frame-ancestors`，它無法用 `<meta>` 標籤設定）。

部署後可用公開門市查詢做基本連線檢查：

```bash
curl -fsS 'http://127.0.0.1:8082/api/stores/nearby?lat=25&lng=121' >/dev/null
```

此檢查會連到資料庫，成功只代表服務與該查詢路徑可用；正式發布仍應執行登入、點餐、揪團及 WebSocket 流程驗證。

## 前端與資料維護

前端是 `frontend/` 下的靜態檔案，沒有前端建置流程；但正式部署前須先處理前述 `localhost:8082` API 位址，然後將靜態檔放到受 HTTPS 保護的同源或獨立網域。獨立網域要同步更新 `CORS_ALLOWED_ORIGINS`。社群登入也需在 `frontend/Customer/js/firebase-config.js` 設定正式 Firebase Web app，並在 Firebase Console 限定授權網域。

正式環境必須安排 MySQL 備份與還原演練。若圖片使用本機 `uploads/`，也要一併持久化與備份；若使用 Cloudinary，請將憑證放在部署平台的 secret store。


## 2026-10-11 第二輪修補後的上線檢查

上線前逐項確認，這些都是本輪修補後新增或變更的行為：

1. **第三方登入**：正式環境的首次社群登入必須經過手機 OTP，新帳號由 `POST /api/auth/social-signup` 建立。前端需部署 `verify-phone.html`、`account-integrate.html` 的新流程，否則首次登入的使用者會卡住。
2. **簡訊驗證**：`SMS_MODE=firebase`。prod profile 下若不是 `firebase`，應用程式會拒絕啟動。
3. **示範資料與模擬儲值**：`DEMO_DATA_ENABLED=false`；`WALLET_MOCK_TOPUP_ENABLED` 不設或為 `false`（prod 一律拒絕模擬儲值）。
4. **手機號碼**：改手機不再經由 `PUT /api/auth/update`，若有客服流程需要換號，請走 Firebase 手機驗證流程或後台人工處理。
5. **顧客名稱**：既有名稱若含 `< > " \`` 或控制字元，之後改名會被拒絕，需要使用者重新輸入合規的名稱。
6. **登入與 OTP 限流**：`LoginAttemptLimiter` 預設每 300 秒 10 次（`check-phone` 為每 300 秒 60 次），Redis 不可用時退回本機計數。若多台應用程式同時上線，請確認 Redis 可用，否則每台各自計數。
7. **安全標頭**：HSTS 只在 HTTPS 回應中送出。若頁面由 nginx 或 Caddy 直接提供，需在該層補上同樣的標頭；`app.security.csp.enabled` 預設為 `false`，啟用前需先處理頁面內的 inline event handler。
8. **取消訂單**：`POST /api/orders/{orderId}/cancel` 現在會退款並還原優惠券，如果你的營運流程依賴舊的「只改狀態」行為，請先與團隊確認。

## 第四輪核查（N-15、N-23 至 N-27）的上線注意事項

9. **團購儲值金付款會實際扣款**：團長結帳與團員付款選擇「儲值金」時，後端會扣除錢包餘額；餘額不足會回錯誤，而不是像修改前那樣靜默記成門市付款。上線前請告知營運團隊，並確認客服流程能處理餘額不足的情況。
10. **WebSocket 訂閱已依角色限制**：門市不會再收到其他門市的新訂單推播；顧客只能訂閱自己參與的訂單與揪團。目前前端沒有訂閱 `/topic/store`，因此不影響現有畫面。
11. **本機 Docker 埠口只綁 127.0.0.1**：若需要從其他機器連線資料庫或 Redis，請改用 SSH tunnel，不要把 `docker-compose.yml` 的綁定改回所有介面。
12. **正式環境不公開 API 文件**：`application-prod.yml.example` 已關閉 springdoc。若要在正式環境查看 API，請在內部環境使用另一份設定。
13. **靜態資源大小寫**：`WebConfig` 的 `/Store/**` 已改為對應 `frontend/store/`（實際目錄），Linux 上不再 404。`/auth/**` 的遺留映射已移除。
14. **品牌自助註冊已改為審核制**（N-28，2026-10-18，見下方）：`/api/brand-auth/register` 仍是公開端點，但新品牌為 `PENDING`，未核准前不能登入、不出現在公開列表。

## 2026-10-15 權杖版本（N13）的上線檢查

部署前必須先完成資料庫變更；順序錯誤會讓應用程式在啟動時失敗（prod 為 `ddl-auto: validate`）。

15. **先加欄位，再部署程式**：`users`、`brands`、`stores` 各需新增 `token_version INT NOT NULL DEFAULT 0`。SQL 與原因見 `DATABASE.md`「結構變更方式」：

    ```sql
    ALTER TABLE users  ADD COLUMN token_version INT NOT NULL DEFAULT 0;
    ALTER TABLE brands ADD COLUMN token_version INT NOT NULL DEFAULT 0;
    ALTER TABLE stores ADD COLUMN token_version INT NOT NULL DEFAULT 0;
    ```

16. **部署時既有登入不會被強制登出**：部署前簽發的權杖沒有版本聲明，視為版本 0；資料庫的 `DEFAULT 0` 讓既有帳號同為版本 0，因此仍然有效。
17. **改密碼後，同一帳號在其他裝置上的舊權杖會失效**：`POST /api/users/{userId}/change-password` 回傳新權杖，前端（`profile.html`）會換上。其他裝置的舊權杖在下一次需要登入的請求時被拒絕（HTTP 與 STOMP 皆然）。客服與使用者需要知道這件事。
18. **品牌與門市目前沒有改密碼的路徑**：停用路徑已於 2026-10-20 加入（見下方 28 至 31），停用時會遞增版本。日後新增改密碼路徑時必須遞增版本，否則舊權杖不會失效（見 `CLAUDE.md` 的 N13 約定）。
19. **登出所有裝置只限顧客（stage 8，2026-10-22）**：`POST /api/users/{userId}/logout-all` 撤銷該顧客所有權杖，沿用 N13 的 `token_version`，不需要新的欄位或資料表變更。切斷既有 WebSocket 連線只作用於處理請求的實例（與 ADR-0012 相同）；其他實例上既有的訂閱要等連線結束，但新的 CONNECT 與 SUBSCRIBE 一律被拒絕。品牌與門市尚未提供此功能。

## 2026-10-18 品牌審核（N-28）的上線檢查

部署前必須先完成資料庫變更（prod 為 `ddl-auto: validate`）。

20. **先執行 DDL，再部署程式**：`brands` 新增 `approval_status`（`DEFAULT 'APPROVED'`）與 `reviewed_at`，並建立 `admins` 資料表。SQL 見 `DATABASE.md`「結構變更方式」的 N-28 項。少了 `DEFAULT 'APPROVED'` 會讓所有既有品牌登入失敗。
21. **既有品牌不受影響**：升級後既有品牌維持 `APPROVED`，可照常登入；只有之後自助註冊的新品牌會是 `PENDING`。
22. **建立第一位管理員**：設定 `ADMIN_BOOTSTRAP_USERNAME` 與 `ADMIN_BOOTSTRAP_PASSWORD_HASH`（BCrypt）後啟動一次，用 `POST /api/admin/auth/login` 確認可以登入，之後從環境變數移除這兩個設定。
23. **管理員審核頁面**：`/Admin/login.html` 登入後，可查看待審品牌並核准或退件（權杖存在瀏覽器的 `adminToken`）。頁面本身是靜態檔、不含資料；資料仍只經 `/api/admin/**`（`GET /api/admin/brands/pending`、`POST /api/admin/brands/{brandId}/approve` 或 `/reject`），需要管理員權杖。正式環境已關閉 Swagger UI。
24. **管理員沒有改密碼或停用的路徑**：`token_version` 存在但未使用。目前要停用管理員，只能在資料庫刪除該列；刪除後，既有權杖在下一次請求即被拒絕。

## 2026-10-19 待接單自動拒單（N-32）的上線檢查

25. **先建立索引**：`orders(status, submitted_at)`，SQL 見 `DATABASE.md`「結構變更方式」的 N-32 項。`validate` 不會建立或檢查索引，缺少時啟動不會失敗，但排程每 30 秒掃一次，沒有索引時會全表掃描。
26. **上線後會拒單一批舊的待處理訂單**：第一輪排程會拒單並退款所有 `SUBMITTED` 且已超過 10 分鐘的訂單，每 30 秒最多 100 筆。上線前請先查數量，並確認營運可以接受：

    ```sql
    SELECT COUNT(*) FROM orders
    WHERE status = 'SUBMITTED'
      AND COALESCE(submitted_at, created_at) < DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR) - INTERVAL 10 MINUTE;
    ```

    `orders` 的時間欄位是台北時間，因此以 UTC 加 8 小時比較。
27. **歷史資料需要對帳**：N-32 修正之前，個人訂單（錢包）取消時會多退一次品項金額；揪團以現金或信用卡結帳後取消時，會把未從錢包扣過的代墊金退進團長錢包。這些已發生的金額不會被本次修正改回。請以錢包帳本與訂單比對，決定是否需要人工調整（由營運負責人決定）。

## 2026-10-20 品牌與門市停用（stage 6）的上線檢查

28. **先加欄位，再部署程式**：`brands`、`stores` 各新增 `suspended BOOLEAN NOT NULL DEFAULT false` 與 `suspended_at DATETIME(6) NULL`。SQL 見 `DATABASE.md`「結構變更方式」的 stage 6 項，也列在下方。prod 為 `ddl-auto: validate`，欄位不存在時啟動會失敗。

    ```sql
    ALTER TABLE brands ADD COLUMN suspended BOOLEAN NOT NULL DEFAULT false;
    ALTER TABLE brands ADD COLUMN suspended_at DATETIME(6) NULL;
    ALTER TABLE stores ADD COLUMN suspended BOOLEAN NOT NULL DEFAULT false;
    ALTER TABLE stores ADD COLUMN suspended_at DATETIME(6) NULL;
    ```

29. **上線後的驗證**：以管理員登入 `/Admin/login.html`，到「已核准品牌」頁停用一個測試品牌，確認 (a) 公開品牌列表不再出現、(b) 該品牌登入回 403、(c) 它的分店頁顯示停用提示。再恢復，確認品牌可以重新登入。**停用會立即切斷該品牌與分店的既有登入與推播連線**，請在非營業時段操作，並事先告知門市與團長需要重新登入。
30. **多實例部署**：切斷的登錄表只在收到停用事件的那個實例內。其他實例上既有的 WebSocket 訂閱會持續收到推播，直到連線結束；新的 CONNECT 與 SUBSCRIBE 在所有實例都會被拒絕，因為權杖版本保存在資料庫。需要所有實例即時切斷時，須另行設計共享的通知機制（見 ADR-0012）。
31. **停用不會取消既有訂單**：已建立的訂單與揪團保留，取消與退款不受停用影響。停用期間的下單與團員付款會被拒絕（400）。
