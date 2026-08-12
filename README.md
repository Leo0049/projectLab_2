# JoinDrink 後端專案

多租戶飲料訂購與揪團平台。後端 Spring Boot 3.4.3 / Java 17，前端為 Vanilla JS + Tailwind。

---

## 🚀 本機開發啟動方式

本機環境使用 **Docker Compose 提供 MySQL 8 與 Redis 7**。

### 1. 啟動資料庫與 Redis

```bash
docker compose up -d
```

確認兩個服務都是 `healthy` 再繼續：

```bash
docker compose ps
```

| 服務 | 位址 | 帳密 |
|------|------|------|
| MySQL 8 | `localhost:3306` | `joindrink` / `joindrink`（DB：`joindrink`） |
| Redis 7 | `localhost:6379` | 無密碼 |

資料存放在 docker volume，`docker compose down` 不會刪除；要清空請用 `docker compose down -v`。

### 2. 設定（可選）

`application.yml` 的每一項都已內建對應上表的本機預設值，**直接啟動即可**。
只有 Cloudinary 圖片上傳需要自己的憑證，兩種擇一：

```bash
cp .env.example .env                                                    # 方式一：環境變數
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml                             # 方式二：local profile
```

用方式二時記得以 `local` profile 啟動（IntelliJ → Run/Debug Configurations → Environment variables）：

```
SPRING_PROFILES_ACTIVE=local
```

### 3. 啟動後端

```bash
mvn spring-boot:run
```

- 服務埠：`8082`
- Swagger UI：http://localhost:8082/swagger-ui.html
- 首次啟動時 Hibernate 會依 Entity 自動建立 26 張表（`ddl-auto: update`），
  `DataSeeder` 會植入預設的規格 / 加料主檔。

### 4. Firebase（社群登入 / 手機驗證）

本機預設 `SMS_MODE=mock`，會跳過 Firebase 驗證，**不需要金鑰即可註冊登入**。
若要測試真實的社群登入，把 `serviceAccountKey.json` 放到 `src/main/resources/`
並將 `SMS_MODE` 改為 `firebase`。

---

## 📁 機密檔案說明（不會進 git）

| 檔案 | 說明 |
|------|------|
| `.env` | 環境變數（含密碼） |
| `src/main/resources/serviceAccountKey.json` | Firebase 服務帳號金鑰 |
| `src/main/resources/application-local.yml` | 本地開發設定（含密碼） |

`application.yml` 現在只保留 `${ENV_VAR:預設值}`，不含任何正式環境密鑰。

> ⚠️ 舊版設定檔曾以明文寫入資料庫密碼、JWT secret 與 Cloudinary api-secret。
> 這些憑證應視為已外洩，請至各服務後台重新產生（rotate）。

---

## ⚠️ 上線部署注意事項

- 所有 `${ENV_VAR}` 於伺服器以環境變數提供，特別是 `JWT_SECRET`（勿使用預設值）
- `JPA_DDL_AUTO` 改為 `validate` 或 `none`，不要在正式環境用 `update`
- Redis 設定密碼並以 `REDIS_PASSWORD` 注入
- 不要將 `.env`、`application-local.yml` 或 `serviceAccountKey.json` 上傳至任何雲端 repo

---

## 🔧 技術架構

- **Spring Boot** 3.4.3 / Java 17
- **Spring Security 6** + JWT（JJWT 0.11.5、HS512、24h）
- **MySQL 8**（本機 Docker）+ Spring Data JPA / Hibernate
- **Redis 7**（本機 Docker）— 揪團購物車、分散式鎖、每日轉盤狀態
- **WebSocket / STOMP**（`/ws-cart`，SockJS）+ **SSE** 即時推播
- **Firebase Admin SDK** 9.2.0 — 社群登入與手機驗證
- **Cloudinary** 圖片上傳
- **SpringDoc OpenAPI** 2.8.5

---

## 📚 其他文件

- [API.md](./API.md) — REST API 端點文件

> `DATABASE.md`（26 張表 schema 說明）尚未建立，schema 目前以 `src/main/java/com/example/demo/entity/` 下的 JPA Entity 為準。
