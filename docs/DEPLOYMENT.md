# 正式環境部署指南

本指南描述目前 repository 可以支援的後端部署方式與上線前限制。正式環境使用 `prod` profile；根目錄的 `docker-compose.yml` 只啟動本機開發用 MySQL 與 Redis，使用預設帳密且會發布資料庫連接埠，不是正式環境部署範本，也不會啟動 Spring Boot。

## 目前部署範圍與上線限制

- **後端**可打包成 Java 17 可執行 JAR。正式 profile 會關閉示範資料、模擬儲值與 SQL 輸出，並以 `ddl-auto: validate` 驗證 schema。
- **尚無版本化資料庫 migration**。正式資料庫必須先由部署者透過已審核的 migration 建立或升級；空資料庫不會由 `prod` profile 自動建表。
- **前端尚未可直接部署到正式網域**：目前品牌、門市頁面及部分登入頁仍有 `http://localhost:8082` API 位址。上線前需統一改為可部署的 runtime API 設定或同源路徑，並對正式網域完成 UI 流程驗證。一般使用者瀏覽器中的 `localhost` 指向使用者自己的電腦。
- MySQL JDBC URL 目前使用 `useSSL=false`，Redis client 也沒有 TLS 設定。兩者只應放在受控私有網路或加密隧道內，不可直接暴露至公網。

## 必要服務與設定

準備 Java 17、Maven、MySQL 8 與 Redis 7。資料庫和 Redis 請使用獨立正式環境服務、限制網路來源，並建立專用帳號；正式環境必填值見 [.env.prod.example](../.env.prod.example)：

- `JWT_SECRET`：至少 64 個 UTF-8 位元組的隨機密鑰。prod profile 若仍使用空值或內建開發密鑰，應用會拒絕啟動。
- `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`：正式 MySQL 連線。不得使用 compose 的 `joindrink` / `joindrink` 開發帳密。
- `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`：正式 Redis 連線與密碼；Redis 不可公開暴露。
- `CORS_ALLOWED_ORIGINS`：前端正式來源的完整 origin，包含 `https://`，多筆以逗號分隔。
- `DEMO_DATA_ENABLED=false`、`WALLET_MOCK_TOPUP_ENABLED=false`：正式環境不可植入示範帳號或開啟未付款儲值；後端也會強制拒絕 prod 模式的模擬儲值。
- `prod` profile 將 `ddl-auto` 設為 `validate`：部署前先套用資料庫 migration，再啟動應用。若有明確需求要覆寫，Spring 環境變數名稱是 `SPRING_JPA_HIBERNATE_DDL_AUTO`。
- `SMS_MODE=firebase` 與 `FIREBASE_CONFIG_PATH`：若正式環境提供手機驗證，設定可讀取的服務帳戶 JSON 檔案路徑。不要把 JSON 放入 repository 或 JAR。
- `RESET_PASSWORD_URL`：正式密碼重設頁網址。
- Cloudinary 憑證可選；若不設定，圖片會寫入 `UPLOAD_LOCAL_DIR` 預設的 `uploads/`。使用本機儲存時，請掛載持久磁碟並納入備份。

`.env.prod` 已列入 `.gitignore`。請把填妥的設定放在部署主機的 secret store 或權限受限目錄，勿提交至 Git，也勿將該檔打包進 JAR。

## 建置與啟動後端

以下命令適用於 Linux 部署主機；請在套用資料庫 migration 後執行：

```bash
cp .env.prod.example .env.prod
chmod 600 .env.prod
# 編輯 .env.prod，填入所有正式值

cp src/main/resources/application-prod.yml.example src/main/resources/application-prod.yml
mvn -B test # 使用與 CI 相同的隔離測試資料庫，不要連正式資料庫
mvn -B package -DskipTests
```

`application-prod.yml` 是被 Git 忽略的 profile 設定，必須在打包前複製，JAR 才會包含正式設定。`mvn test` 需要可連線的 MySQL 與 Redis；使用隔離的測試資料庫，不要把正式 `.env.prod` 載入測試流程。

互動式測試啟動可載入環境檔：

```bash
set -a
. ./.env.prod
set +a
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

正式服務請以 systemd、容器平台或主機 secret manager 管理環境變數，避免依賴互動式 shell。systemd 可將受限權限的環境檔放在 repository 外，再於 unit 設定 `EnvironmentFile=/etc/joindrink/joindrink.env` 與 `ExecStart=/usr/bin/java -jar /opt/joindrink/demo-0.0.1-SNAPSHOT.jar`。環境需包含 `SPRING_PROFILES_ACTIVE=prod`。

應用預設監聽 `8082`。正式入口應由 HTTPS reverse proxy 提供，僅公開代理層；代理 WebSocket 時也需支援 `Upgrade`，後端 STOMP endpoint 為 `/ws-cart`。後端 CORS origin 必須和瀏覽器實際載入前端的 origin 一致。

部署後可用公開門市查詢做基本連線檢查：

```bash
curl -fsS 'http://127.0.0.1:8082/api/stores/nearby?lat=25&lng=121' >/dev/null
```

此檢查會連到資料庫，成功只代表服務與該查詢路徑可用；正式發布仍應執行登入、點餐、揪團及 WebSocket 流程驗證。

## 前端與資料維護

前端是 `frontend/` 下的靜態檔案，沒有前端建置流程；但正式部署前須先處理前述 `localhost:8082` API 位址，然後將靜態檔放到受 HTTPS 保護的同源或獨立網域。獨立網域要同步更新 `CORS_ALLOWED_ORIGINS`。社群登入也需在 `frontend/Customer/js/firebase-config.js` 設定正式 Firebase Web app，並在 Firebase Console 限定授權網域。

正式環境必須安排 MySQL 備份與還原演練。若圖片使用本機 `uploads/`，也要一併持久化與備份；若使用 Cloudinary，請將憑證放在部署平台的 secret store。
