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
- `CORS_ALLOWED_ORIGINS`：前端正式來源的完整 origin，包含 `https://`，多筆以逗號分隔。未設定時會退回 `application.yml` 的預設值（只含本機來源），因此正式環境**必須**設定。
- `SERVER_FORWARD_HEADERS_STRATEGY`（預設 `native`）：後端如何信任反向代理送來的 `X-Forwarded-*`，見下方「反向代理與來源 IP」。
- `DEMO_DATA_ENABLED=false`、`WALLET_MOCK_TOPUP_ENABLED=false`：正式環境不可植入示範帳號或開啟未付款儲值；後端也會強制拒絕 prod 模式的模擬儲值。
- `prod` profile 將 `ddl-auto` 設為 `validate`：應用只檢查結構、不修改。repo 目前**沒有 migration 腳本或工具**（見 `docs/adr/0008-schema-migrations.md`）。首次部署前必須以人工方式建立與 Entity 一致的結構，例如在相同版本的開發資料庫以 `ddl-auto: update` 啟動一次、匯出 DDL 作為基線，檢查後再套用到正式庫；之後每次結構變更同樣需要人工套用。若有明確需求要覆寫，Spring 環境變數名稱是 `SPRING_JPA_HIBERNATE_DDL_AUTO`。
- `SMS_MODE=firebase` 與 `FIREBASE_CONFIG_PATH`：**必填**。`sms.mode` 預設是 `mock`，而 mock 模式會把 `MOCK_TOKEN` 當成「手機已驗證」，公開的重設密碼端點因此能接管任何帳號；`AuthService` 在 prod profile 下一律拒絕啟動（不是默默回退）。另請設定可讀取的服務帳戶 JSON 檔案路徑，不要把 JSON 放入 repository 或 JAR。
- `RESET_PASSWORD_URL`：正式密碼重設頁網址。
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
14. **品牌自助註冊是公開的**：`/api/brand-auth/register` 為 permitAll，任何人都能註冊品牌並建立門市。上線前請業務決定是否要審核制（PENDING 狀態、後台核准）或邀請碼；在決定前，建議由營運團隊定期檢視新註冊的品牌與門市。
