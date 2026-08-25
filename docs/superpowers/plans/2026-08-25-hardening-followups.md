# 遺留債務清理（安全強化 + 依賴升級 + 生產就緒）實作計畫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清完安全審查遺留的四類債務——前端 XSS 高風險注入點、後端依賴風險、RedisCartService 的 LazyInit 地雷、以及生產部署防呆設定——全程以測試與可重跑指令驗證。

**Architecture:** 後端改動遵循既有分層（Controller → Service → Repository），前端採「每檔內嵌 `esc()` helper + 資料面跳逸」的最小侵入模式（不做模組化重構）；生產設定走 Spring profile 疊蓋（`application-prod.yml`），金鑰防呆放進 `JwtUtils` 啟動路徑。CSP 因全站 inline script 需搬移，本計畫僅產出文件化路線圖，不改程式。

**Tech Stack:** Java 17 / Spring Boot 3.4.3 / Maven 3.9.x · JUnit 5 + Mockito · 原生 JS（無打包工具）· MySQL 8 + Redis 7（docker compose）

## Global Constraints

- 分支：所有任務在 `chore/hardening-followups` 分支上執行（Task 0 建立）。
- 每個 Task 結束必須 `mvn -B test` 全綠才准 commit（前端任務則為 `node --check` 通過 + 手動清單）。
- 測試基準線：目前套件 **65 個測試全綠**；任何任務完成後不得低於此數且不得出現 Failure/Error。
- 版控規範沿用專案現況：`application-*.yml` 被 .gitignore 排除、`*.example` 保留——新設定檔一律用 `.example` 後綴提交。
- 不升級 cloudinary-http44、不改動任何 Controller 對外 API 契約（除非該契約本身是死碼）。
- 提交訊息使用 conventional commits（`chore:` / `fix:` / `test:` / `docs:`）。

---

### Task 0: 建立工作分支

**Files:** 無新檔案（git 操作）

- [ ] **Step 1: 建立並切換分支**

```bash
cd C:/Users/User/Desktop/test2/projectLab_2
git checkout -b chore/hardening-followups
```

Expected: `Switched to a new branch 'chore/hardening-followups'`

---

### Task 1: 移除 TestNG 依賴（零使用）

**背景**：`grep -rn "org.testng" src/test` 結果為空——TestNG 在 classpath 上卻沒有任何測試使用它。它會讓 surefire 的 provider 偵測變得曖昧（JUnit 與 TestNG 並存），是測試基礎設施的隱性地雷。

**Files:**
- Modify: `pom.xml`（刪除 testng dependency 區塊）

- [ ] **Step 1: 先確認確實無使用（防止半途有人加了 TestNG 測試）**

Run: `grep -rn "org.testng" src/test --include="*.java"`
Expected: 無輸出（exit code 1）

- [ ] **Step 2: 刪除 pom.xml 中的依賴區塊**

找到並整段刪除：

```xml
        <dependency>
            <groupId>org.testng</groupId>
            <artifactId>testng</artifactId>
            <version>7.10.2</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: 跑完整測試套件確認供應器切換無副作用**

Run: `mvn -B test`
Expected: `Tests run: 65, Failures: 0, Errors: 0` 且 `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "chore: remove unused TestNG dependency (JUnit5 is the sole test provider)"
```

---

### Task 2: firebase-admin 升級（9.2.0 → 9.4.2）

**背景**：9.2.0（2024 初）之後的 9.x 修補了傳遞依賴（google-oauth-client/grpc）的多個 CVE。Firebase 只用於社群登入／手機驗證（`FirebaseConfig.java`、`AuthService.java`、`UserController.java`），升 patch/minor 版本 API 相容。

**Files:**
- Modify: `pom.xml`（firebase-admin version）

- [ ] **Step 1: 驗證目標版本存在於 Maven Central**

Run: `mvn -B dependency:get -Dartifact=com.google.firebase:firebase-admin:9.4.2 -Dtransitive=false`
Expected: `BUILD SUCCESS`。若失敗，到 <https://central.sonatype.com/artifact/com.google.firebase/firebase-admin> 查 9.x 最新版並改用它（不可跨到大版本）。

- [ ] **Step 2: 修改 pom.xml**

```xml
        <dependency>
            <groupId>com.google.firebase</groupId>
            <artifactId>firebase-admin</artifactId>
            <version>9.4.2</version>
        </dependency>
```

- [ ] **Step 3: 編譯 + 完整測試**

Run: `mvn -B test`
Expected: 65 tests pass，`BUILD SUCCESS`（SMS_MODE 預設 mock，不需要真實 Firebase 金鑰）

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "chore: bump firebase-admin 9.2.0 -> 9.4.2 (CVE patches in transitive deps)"
```

---

### Task 3: RedisCartService 快照化（消除 LazyInit 地雷）＋ 移除死碼

**背景**：`saveItem(token, OrderItem)` 用 Jackson 序列化整顆 JPA entity，離開交易後 lazy proxy（product→category→brand）觸發 `LazyInitializationException`，被 catch 吞掉只在 log 留 `ERROR ... Failed to serialize OrderItem for Redis`——Redis 快照其實從來沒存成功過。另外 `getCartItems`／`getRecentActiveCarts` 全專案零呼叫端（已 grep 證實），屬死碼。

修法：改存純量快照 record（id/productId/名稱/數量/價格/狀態）。`item.getProduct().getId()` 在 Hibernate proxy 上取 id 不會觸發初始化，故安全。

**Files:**
- Modify: `src/main/java/com/example/demo/service/RedisCartService.java`（整檔重寫）
- Create: `src/test/java/com/example/demo/service/RedisCartServiceTest.java`
- Verify-only: `src/main/java/com/example/demo/entity/OrderItem.java`

- [ ] **Step 1: 確認 OrderItem 有 Lombok @Data（setter 可用）**

Run: `grep -n "@Data\|public class OrderItem" src/main/java/com/example/demo/entity/OrderItem.java | head -3`
Expected: 看到 `@Data`。若沒有（只有 getter），測試碼改用 `org.springframework.test.util.ReflectionTestUtils.setField(item, "productNameSnapshot", "紅茶")` 寫值——兩種寫法下方步驟都給出。

- [ ] **Step 2: 寫失敗測試**

建立 `src/test/java/com/example/demo/service/RedisCartServiceTest.java`：

```java
package com.example.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RedisCartService 必須只序列化「純量快照」，不得碰 lazy proxy：
 * 曾因直接序列化 OrderItem entity 觸發 LazyInitializationException，
 * 導致 Redis 快照靜默失敗（log 只有 ERROR，功能看似正常）。
 */
@ExtendWith(MockitoExtension.class)
class RedisCartServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<Object, Object, Object> hashOps;
    @Mock private ZSetOperations<String, String> zSetOps;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RedisCartService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
    }

    private com.example.demo.entity.OrderItem newItem() {
        com.example.demo.entity.OrderItem item = new com.example.demo.entity.OrderItem();
        item.setId(7L);
        item.setProductNameSnapshot("珍珠奶茶");
        item.setQty(2);
        item.setUnitPriceSnapshot(new BigDecimal("35.00"));
        item.setFinalPrice(new BigDecimal("70.00"));
        item.setPaymentStatus("UNPAID");
        return item;
    }

    @Test
    void saveItemStoresFlatSnapshotWithoutLazyFields() {
        com.example.demo.entity.OrderItem item = newItem();

        service.saveItem("T1", item);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(hashOps).put(eq("cart:items:T1"), eq("7"), value.capture());
        String json = value.getValue();
        assertTrue(json.contains("珍珠奶茶"), "快照應含品名");
        assertTrue(json.contains("\"qty\":2"), "快照應含數量");
        assertFalse(json.contains("\"toppings\""), "不得序列化關聯集合");
        assertFalse(json.contains("\"product\""), "不得序列化 product 關聯");
        assertFalse(json.contains("\"user\""), "不得序列化 user 關聯");
    }

    @Test
    void saveItemSurvivesNullProduct() {
        // 舊資料可能 product_id 為 null；快照路徑不得拋 NPE
        com.example.demo.entity.OrderItem item = newItem();
        service.saveItem("T2", item);
        verify(hashOps).put(eq("cart:items:T2"), eq("7"), anyString());
    }
}
```

（若 Step 1 發現 OrderItem 無 setter：把 `item.setXxx(...)` 六行全部改成
`ReflectionTestUtils.setField(item, "id", 7L);` 等形式，其餘不變。）

- [ ] **Step 3: 執行測試確認編譯失敗（紅燈）**

Run: `mvn -B test -Dtest=RedisCartServiceTest`
Expected: **COMPILATION ERROR**——`CartSnapshot` 尚不存在／或 saveItem 行為不符斷言（序列化了 `"toppings"`）。

- [ ] **Step 4: 整檔重寫 RedisCartService**

以下列內容完整取代 `src/main/java/com/example/demo/service/RedisCartService.java`：

```java
package com.example.demo.service;

import com.example.demo.entity.OrderItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 揪團購物車的 Redis 快照。
 *
 * <p>⚠️ 修復：舊版 {@code saveItem} 直接用 Jackson 序列化 OrderItem entity，
 * 離開交易後 lazy proxy（product→category→brand）拋 LazyInitializationException，
 * 例外被吞掉，Redis 快照從未真正寫入。改為只存 {@link CartItemSnapshot} 純量欄位——
 * 其中 productId 取自 proxy 的 getId()（Hibernate 保證不觸發初始化）。
 *
 * <p>⚠️ 同步移除死碼 getCartItems／getRecentActiveCarts（全專案零呼叫端，
 * 已於 2026-08 安全審查時 grep 證實）。日後要讀回購物車請反序列化成
 * CartItemSnapshot，不要再綁回 entity。
 */
@Slf4j
@Service
public class RedisCartService {

    /** 存進 Redis 的最小快照；只允許純量欄位，禁止加入任何關聯型別 */
    public record CartItemSnapshot(
            Long id,
            Long productId,
            String productNameSnapshot,
            Integer qty,
            BigDecimal unitPriceSnapshot,
            BigDecimal finalPrice,
            String paymentStatus) {
    }

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CART_HASH_PREFIX = "cart:items:";
    private static final String ACTIVE_CARTS_KEY = "carts:active";

    @Autowired
    public RedisCartService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 儲存單一品項至 Redis Hash（純量快照，永不觸發 lazy loading） */
    public void saveItem(String token, OrderItem item) {
        try {
            CartItemSnapshot snapshot = new CartItemSnapshot(
                    item.getId(),
                    item.getProduct() != null ? item.getProduct().getId() : null,
                    item.getProductNameSnapshot(),
                    item.getQty(),
                    item.getUnitPriceSnapshot(),
                    item.getFinalPrice(),
                    item.getPaymentStatus());

            String key = CART_HASH_PREFIX + token;
            String field = String.valueOf(item.getId());
            String value = objectMapper.writeValueAsString(snapshot);

            redisTemplate.opsForHash().put(key, field, value);
            updateActiveCartTimestamp(token);

            log.debug("Saved item {} to Redis cart: {}", item.getId(), token);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize cart snapshot for Redis", e);
        }
    }

    /** 從 Redis Hash 移除品項 */
    public void removeItem(String token, Long itemId) {
        String key = CART_HASH_PREFIX + token;
        redisTemplate.opsForHash().delete(key, String.valueOf(itemId));
        updateActiveCartTimestamp(token);

        log.debug("Removed item {} from Redis cart: {}", itemId, token);
    }

    /** 清空 Redis 中的購物車資料 */
    public void clearCart(String token) {
        String key = CART_HASH_PREFIX + token;
        redisTemplate.delete(key);
        redisTemplate.opsForZSet().remove(ACTIVE_CARTS_KEY, token);
        log.debug("Cleared Redis cart: {}", token);
    }

    /** 更新活躍揪團的時間戳 (Sorted Set)，供未來的清理排程使用 */
    private void updateActiveCartTimestamp(String token) {
        double score = (double) Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().add(ACTIVE_CARTS_KEY, token, score);
    }
}
```

注意：新檔用到 `java.math.BigDecimal`，record 參數型別需 import——在最上方 import 區加入 `import java.math.BigDecimal;`。

- [ ] **Step 5: 測試轉綠 + 全套件迴歸**

Run: `mvn -B test -Dtest=RedisCartServiceTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

Run: `mvn -B test`
Expected: `Tests run: 67, Failures: 0, Errors: 0`（65 + 新增 2）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/demo/service/RedisCartService.java src/test/java/com/example/demo/service/RedisCartServiceTest.java
git commit -m "fix: store flat cart snapshots in Redis instead of serializing lazy entities"
```

---

### Task 4: JwtUtils 生產金鑰防呆（TDD）

**背景**：`application.yml` 的 JWT_SECRET 帶開發預設值。若營運人員忘記設環境變數就直接上線，任何人都能偽造 token。防呆：偵測到 `prod` profile 且金鑰等於內建預設 → 拒絕啟動。

**Files:**
- Modify: `src/main/java/com/example/demo/common/JwtUtils.java`
- Create: `src/test/java/com/example/demo/common/JwtUtilsProdGuardTest.java`

- [ ] **Step 1: 寫失敗測試**

建立 `src/test/java/com/example/demo/common/JwtUtilsProdGuardTest.java`：

```java
package com.example.demo.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** prod profile 下使用開發預設 JWT_SECRET 必須拒絕啟動 */
class JwtUtilsProdGuardTest {

    @Test
    void devProfileAcceptsDefaultSecret() {
        assertDoesNotThrow(() ->
                JwtUtils.assertProductionSecret(false, JwtUtils.DEV_DEFAULT_SECRET_PREFIX + "xxx"));
    }

    @Test
    void prodProfileRejectsDefaultSecret() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JwtUtils.assertProductionSecret(true,
                        JwtUtils.DEV_DEFAULT_SECRET_PREFIX + "DoNotUseInProductionMustBeAtLeast64Chars!"));
        assertTrue(ex.getMessage().contains("JWT_SECRET"),
                "錯誤訊息必須指出要設定 JWT_SECRET");
    }

    @Test
    void prodProfileRejectsBlankSecret() {
        assertThrows(IllegalStateException.class,
                () -> JwtUtils.assertProductionSecret(true, "  "));
    }

    @Test
    void prodProfileAcceptsRandomSecret() {
        assertDoesNotThrow(() -> JwtUtils.assertProductionSecret(true, "a".repeat(64)));
    }
}
```

- [ ] **Step 2: 執行測試確認編譯失敗（紅燈）**

Run: `mvn -B test -Dtest=JwtUtilsProdGuardTest`
Expected: COMPILATION ERROR——`找不到符號: assertProductionSecret`

- [ ] **Step 3: 實作防呆**

修改 `src/main/java/com/example/demo/common/JwtUtils.java`：

(a) import 區加入：

```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.util.Arrays;
```

(b) 類別欄位區（`private SecretKey signingKey;` 之前）加入：

```java
    /** application.yml 內建的開發預設金鑰前綴；prod profile 下出現即拒絕啟動 */
    static final String DEV_DEFAULT_SECRET_PREFIX = "LocalDevOnlySecretForJoinDrink";

    @Autowired(required = false)
    private Environment environment;
```

(c) 加入靜態守衛方法（放在 `initSigningKey()` 上方）：

```java
    /**
     * ⚠️ 生產防呆：prod profile 下若金鑰仍是內建開發預設值或空白，
     * 直接讓啟動失敗——偽造 token 的代價遠比啟動失敗高。
     * package-private static 以利單元測試。
     */
    static void assertProductionSecret(boolean productionProfile, String secret) {
        if (!productionProfile) {
            return;
        }
        if (secret == null || secret.isBlank()
                || secret.startsWith(DEV_DEFAULT_SECRET_PREFIX)) {
            throw new IllegalStateException(
                    "偵測到 prod profile 使用空白或內建預設的 JWT_SECRET。"
                            + "正式環境必須以環境變數 JWT_SECRET 注入至少 64 字元的隨機金鑰"
                            + "（例：openssl rand -base64 72）。");
        }
    }
```

(d) `initSigningKey()` 方法開頭（取得 keyBytes 之前）插入：

```java
        boolean productionProfile = environment != null
                && Arrays.asList(environment.getActiveProfiles()).contains("prod");
        assertProductionSecret(productionProfile, secret);
```

- [ ] **Step 4: 測試轉綠**

Run: `mvn -B test -Dtest=JwtUtilsProdGuardTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: 全套件迴歸**

Run: `mvn -B test`
Expected: `Tests run: 71, Failures: 0, Errors: 0`（67 + 新增 4）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/demo/common/JwtUtils.java src/test/java/com/example/demo/common/JwtUtilsProdGuardTest.java
git commit -m "feat: refuse to boot with default JWT secret under prod profile"
```

---

### Task 5: 生產設定檔範本（application-prod.yml.example + .env.prod.example）

**Files:**
- Create: `src/main/resources/application-prod.yml.example`
- Create: `.env.prod.example`

- [ ] **Step 1: 建立 application-prod.yml.example**

```yaml
# ─────────────────────────────────────────────────────────────
# 正式環境 profile 範本。
# 用法：複製為 src/main/resources/application-prod.yml（已被 .gitignore 排除），
#       並以 SPRING_PROFILES_ACTIVE=prod 啟動。
#
# ⚠️ 此檔只放「非敏感」的正式值；所有密鑰一律走環境變數。
# ⚠️ OS 環境變數優先序高於本檔——請勿同時在環境設 JPA_SHOW_SQL=true。
# ─────────────────────────────────────────────────────────────
spring:
  jpa:
    show-sql: false           # 正式環境不在 stdout 刷 SQL
    hibernate:
      ddl-auto: validate      # schema 異動走人工 migration，啟動只驗證
    open-in-view: false

logging:
  level:
    root: INFO
    org.hibernate.SQL: WARN
    org.springframework.data.redis: WARN

app:
  demo-data:
    enabled: false            # 雙保險：即使忘了設 DEMO_DATA_ENABLED 也不植入示範帳號
```

- [ ] **Step 2: 建立 .env.prod.example**

```
# ─────────────────────────────────────────────────────────────
# 正式環境必要覆寫。複製為 .env.prod 後逐項填入（勿 commit）。
# 缺任何一項「必填」都視為未部署完成。
# ─────────────────────────────────────────────────────────────

# ── 必填 ──
SPRING_PROFILES_ACTIVE=prod
# 至少 64 字元隨機字串：openssl rand -base64 72
JWT_SECRET=
# 正式環境絕不植入示範帳號
DEMO_DATA_ENABLED=false

# ── 必填（對應你的網域，含 scheme，逗號分隔多筆）──
CORS_ALLOWED_ORIGINS=

# ── 資料庫（務必換掉 joindrink/joindrink 預設帳密）──
DB_HOST=
DB_PORT=3306
DB_NAME=joindrink
DB_USERNAME=
DB_PASSWORD=
MYSQL_ROOT_PASSWORD=

# ── Redis（務必設密碼）──
REDIS_HOST=
REDIS_PORT=6379
REDIS_PASSWORD=

# ── SMS / Firebase ──
SMS_MODE=firebase
FIREBASE_CONFIG_PATH=file:/secure/path/serviceAccountKey.json

# ── 圖片上傳（Cloudinary）──
CLOUDINARY_CLOUD_NAME=
CLOUDINARY_API_KEY=
CLOUDINARY_API_SECRET=

# ── 其他 ──
SERVER_PORT=8082
RESET_PASSWORD_URL=https://your-domain.example/reset
COUPON_TEMPLATE_URL=
```

- [ ] **Step 3: 驗證 resources 複製與 gitignore 語意**

Run: `mvn -B compile && git status --short | grep -E "prod"`
Expected: compile 成功；`application-prod.yml.example` 出現在 untracked（`.env.prod.example` 亦然）；**不應**出現 `application-prod.yml`（被忽略）——若將來有人建了真檔也不會被 commit。

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application-prod.yml.example .env.prod.example
git commit -m "chore: add production profile templates (validate ddl, no demo data, sql off)"
```

---

### Task 6: 文件——README 部署章節 + 前端安全路線圖

**Files:**
- Modify: `README.md`（檔尾追加章節）
- Create: `docs/SECURITY-FRONTEND.md`

- [ ] **Step 1: README.md 檔尾追加**

````markdown
---

## 部署到正式環境

1. `cp .env.prod.example .env.prod` 並逐項填入（JWT_SECRET 未換會**拒絕啟動**）。
2. `cp src/main/resources/application-prod.yml.example src/main/resources/application-prod.yml`
3. 以 `SPRING_PROFILES_ACTIVE=prod` 啟動：`docker compose up -d && SPRING_PROFILES_ACTIVE=prod mvn spring-boot:run`
4. 啟動自我檢查：prod profile 下若 JWT_SECRET 是開發預設值，應用會直接丟 `IllegalStateException` 並退出——這是刻意設計。
5. 詳細的安全配置說明見 `docs/SECURITY-FRONTEND.md`。
````

- [ ] **Step 2: 建立 docs/SECURITY-FRONTEND.md**

````markdown
# 前端安全：現況與 CSP 路線圖

## 現況（2026-08-25 審查後）

- 使用者可控資料的渲染點已導入 `esc()` HTML 跳逸：
  - `frontend/Customer/js/nav-auth.js`（toast 訊息、常用地址）
  - `frontend/Customer/js/store-list.js`（店家卡片、品牌下拉）
  - `frontend/Customer/group_order.html`（團員名稱、品名、圖片屬性、inline handler 參數）
  - `frontend/store/js/order-all-sync.js`（顧客姓名/電話/品項備註）
- 評論監控（Brand 端）原本就以 `textContent` 渲染，無需修改。

## CSP 路線圖（未實作，需先完成下列前置）

### 外部來源盤點（目前頁面實際連線/載入的第三方）

| 來源 | 用途 | 指令 |
|---|---|---|
| *.tile.openstreetmap.org | Leaflet 圖磚 | img-src |
| api.qrserver.com | 分享 QR Code 圖 | img-src |
| identitytoolkit.googleapis.com / securetoken.googleapis.com | Firebase Auth | connect-src |
| www.googleapis.com | Firebase | connect-src |

### 目標政策（第一階段草案）

```
default-src 'self';
img-src 'self' data: https://api.qrserver.com https://*.tile.openstreetmap.org;
connect-src 'self' ws: wss: https://identitytoolkit.googleapis.com https://securetoken.googleapis.com https://www.googleapis.com;
script-src 'self' 'unsafe-inline';   ← 第二階段移除
style-src 'self' 'unsafe-inline';
frame-ancestors 'none';
```

### 實施順序

1. **P1**：把各頁 inline `<script>` 抽到外部 js（group_order.html 最大，先做）。
2. **P2**：以上述政策加到每頁 `<meta http-equiv="Content-Security-Policy">`，先在 staging 觀察 console 違規回報。
3. **P3**：頁面改由 Spring 靜態資源服務後，改用 response header 下發（WebConfig 加 Content-Security-Policy header），移除 `'unsafe-inline'`。

> 注意：`onclick="..."` inline handler 屬於 script-src 範疇，P1 需一併改為 `addEventListener` 或 data-* + 事件委派。
````

- [ ] **Step 3: Commit**

```bash
git add README.md docs/SECURITY-FRONTEND.md
git commit -m "docs: production deployment guide and frontend CSP roadmap"
```

---

### Task 7: 前端 XSS——共用 esc() helper 注入四個檔案

**背景**：使用者可控字串（姓名、地址、備註、店名、品名、圖片 URL）被直接插進 innerHTML 模板。修法統一為「模板內所有 ${user-data} 包 `esc()`」；`esc()` 定義在每個被修改檔案的頂部（避免改動 30+ 個 HTML 的 script 引入順序；日後模組化時再收斂成單一檔案）。

**Files:**
- Modify: `frontend/Customer/js/nav-auth.js`
- Modify: `frontend/Customer/js/store-list.js`
- Modify: `frontend/Customer/group_order.html`
- Modify: `frontend/store/js/order-all-sync.js`

- [ ] **Step 1: 在 nav-auth.js 檔案最頂端插入 helper**

```js
/* ── XSS 防護：HTML 跳逸（文字節點與雙引號屬性值皆適用）── */
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => (
  { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
));
```

- [ ] **Step 2: 在 store-list.js 檔案最頂端插入同一份 helper**

（同 Step 1 的三行程式碼，原樣複製）

- [ ] **Step 3: 在 group_order.html 主腳本區插入 helper**

Run: `grep -n "<script>" frontend/Customer/group_order.html`
Expected: 三個開頭標籤（28 / 877 / 2989 行附近）。主邏輯在第 877 行那塊（渲染函式都在其中）。

在第 877 行的 `<script>` 開頭標籤的**下一行**插入同樣三行 helper（此處是在 HTML 內，不需 export，直接全域 const 即可）。

- [ ] **Step 4: 在 order-all-sync.js 檔案最頂端插入同一份 helper**

（同 Step 1）

- [ ] **Step 5: 語法驗證（三個 .js 檔）**

Run:
```bash
node --check frontend/Customer/js/nav-auth.js
node --check frontend/Customer/js/store-list.js
node --check frontend/store/js/order-all-sync.js
```
Expected: 三者皆靜默通過（exit 0）。若環境無 node，改用瀏覽器 DevTools Console 載入頁面確認無 SyntaxError。

- [ ] **Step 6: Commit**

```bash
git add frontend/Customer/js/nav-auth.js frontend/Customer/js/store-list.js frontend/Customer/group_order.html frontend/store/js/order-all-sync.js
git commit -m "fix(frontend): add esc() HTML-escaping helper to user-content renderers"
```

---

### Task 8: nav-auth.js 注入點轉換（toast、常用地址、購物車下拉）

**Files:**
- Modify: `frontend/Customer/js/nav-auth.js`

- [ ] **Step 1: Toast 改用 textContent（訊息來源含伺服器錯誤字串，可能回音使用者輸入）**

找到（約 746 行）：

```js
        toast.innerHTML = `<span>${icons[type]||icons.info}</span><span>${message}</span>`;
```

替換為：

```js
        const iconSpan = document.createElement('span');
        iconSpan.textContent = icons[type] || icons.info;
        const msgSpan = document.createElement('span');
        msgSpan.textContent = message;   // ← 使用者可控內容走 textContent，永不解析 HTML
        toast.appendChild(iconSpan);
        toast.appendChild(msgSpan);
```

- [ ] **Step 2: 常用地址列表——所有 addr 欄位包 esc()**

找到（約 430-450 行）addresses.map 區塊，把其中：

```js
                            const badgeHtml = label
                                ? `<span class="inline-block text-xs font-bold bg-orange-100 text-brand-orange px-2 py-0.5 rounded-full mr-2">${label}</span>`
                                : '';
```

改為：

```js
                            const badgeHtml = label
                                ? `<span class="inline-block text-xs font-bold bg-orange-100 text-brand-orange px-2 py-0.5 rounded-full mr-2">${esc(label)}</span>`
                                : '';
```

以及 return 模板中的五個 data-* 屬性與顯示文字：

```js
                                data-city="${city}"
                                data-district="${district}"
                                data-street="${street}"
                                data-lat="${addr.latitude || ''}"
                                data-lng="${addr.longitude || ''}"
                                onclick="window._selectCommonAddress(this)">
                                ${badgeHtml}<span class="text-brand-dark">${display}</span>${defaultBadge}
```

改為：

```js
                                data-city="${esc(city)}"
                                data-district="${esc(district)}"
                                data-street="${esc(street)}"
                                data-lat="${esc(addr.latitude || '')}"
                                data-lng="${esc(addr.longitude || '')}"
                                onclick="window._selectCommonAddress(this)">
                                ${badgeHtml}<span class="text-brand-dark">${esc(display)}</span>${defaultBadge}
```

（下游 `_selectCommonAddress` 讀 `btn.dataset.*`，dataset 回傳的是**解碼後原始字串**，行為不變。）

- [ ] **Step 3: 購物車下拉——店名/品名/圖片 URL 包 esc()**

找到（約 668-686 行）html += 模板中：

```js
                                    ${cart.storeName || '店家'} 
```

改為：

```js
                                    ${esc(cart.storeName || '店家')} 
```

```js
                                            <img src="${item.imageUrl || 'images/logo.png'}" class="w-full h-full object-cover" onerror="this.src='images/logo.png'">
```

改為：

```js
                                            <img src="${esc(item.imageUrl || 'images/logo.png')}" class="w-full h-full object-cover" onerror="this.src='images/logo.png'">
```

```js
                                            <h4 class="text-xs font-bold text-gray-800 truncate">${item.productName}</h4>
```

改為：

```js
                                            <h4 class="text-xs font-bold text-gray-800 truncate">${esc(item.productName)}</h4>
```

- [ ] **Step 4: 語法驗證**

Run: `node --check frontend/Customer/js/nav-auth.js`
Expected: exit 0

- [ ] **Step 5: 手動煙霧測試**

用 Live Server 開 `frontend/Customer/index.html`，以 `0912000000/demo1234` 登入：
1. 觸發一次 toast（例如登入成功）→ 文字正常、無 HTML 標籤外漏。
2. 開地址面板 → 常用地址按鈕正常顯示與點選。
3. 加一件商品進購物車 → 下拉顯示店名/品名正常。

- [ ] **Step 6: Commit**

```bash
git add frontend/Customer/js/nav-auth.js
git commit -m "fix(frontend): escape user-controlled data in toast, addresses and cart dropdown"
```

---

### Task 9: store-list.js 注入點轉換（店家卡片、品牌下拉）

**Files:**
- Modify: `frontend/Customer/js/store-list.js`

- [ ] **Step 1: renderCard 屬性與圖片跳逸**

在 `function renderCard(store)` 內：

```js
        data-store-name="${(store.name || store.storeName || '').toLowerCase().replace(/"/g, '')}"
        data-brand-name="${(store.brandName || '').toLowerCase().replace(/"/g, '')}"
```

改為（esc 已涵蓋雙引號，原 replace 可移除）：

```js
        data-store-name="${esc(store.name || store.storeName || '').toLowerCase()}"
        data-brand-name="${esc(store.brandName || '').toLowerCase()}"
```

```js
          <img src="${store.imageUrl || store.image || store.coverUrl || 'images/store-placeholder.svg'}"
               class="w-full h-full object-cover transition-transform duration-500 group-hover:scale-110"
               loading="lazy"
               alt="${store.name || store.storeName || '店家封面'}"
```

改為：

```js
          <img src="${esc(store.imageUrl || store.image || store.coverUrl || 'images/store-placeholder.svg')}"
               class="w-full h-full object-cover transition-transform duration-500 group-hover:scale-110"
               loading="lazy"
               alt="${esc(store.name || store.storeName || '店家封面')}"
```

卡片下半部（約 165-172 行）：

```js
                ${store.brandLogoUrl
                  ? `<img src="${store.brandLogoUrl}" class="w-full h-full object-cover" alt="${store.brandName || ''}"
                          onerror="this.style.display='none';this.nextElementSibling.style.display='flex'">`
                  : ''}
              </div>` : ''}
              <h3 class="font-extrabold text-lg truncate">${store.name || store.storeName || ''}</h3>
```

改為：

```js
                ${store.brandLogoUrl
                  ? `<img src="${esc(store.brandLogoUrl)}" class="w-full h-full object-cover" alt="${esc(store.brandName || '')}"
                          onerror="this.style.display='none';this.nextElementSibling.style.display='flex'">`
                  : ''}
              </div>` : ''}
              <h3 class="font-extrabold text-lg truncate">${esc(store.name || store.storeName || '')}</h3>
```

- [ ] **Step 2: 品牌下拉（約 209 行）logoUrl 與品牌名跳逸**

```js
        li.innerHTML = brand.logoUrl
          ? `<span style="display:flex;align-items:center;gap:8px;">
               <img src="${brand.logoUrl}" style="width:20px;height:20px;border-radius:50%;object-fit:cover;" onerror="this.style.display='none'">
               ${brand.brandName}
             </span>`
          : brand.brandName;
```

改為：

```js
        li.innerHTML = brand.logoUrl
          ? `<span style="display:flex;align-items:center;gap:8px;">
               <img src="${esc(brand.logoUrl)}" style="width:20px;height:20px;border-radius:50%;object-fit:cover;" onerror="this.style.display='none'">
               ${esc(brand.brandName)}
             </span>`
          : esc(brand.brandName);
```

- [ ] **Step 3: 語法驗證 + 手動煙霧測試**

Run: `node --check frontend/Customer/js/store-list.js`
Expected: exit 0

手動：開 `frontend/Customer/index.html` → 店家卡片正常渲染、品牌下拉可選、搜尋可用。特別測一筆店名含特殊字的資料（可在 DB 把某 store_name 改成 `"><img src=x onerror=alert(1)>` 再重新整理，畫面應顯示純文字而非彈窗）。

- [ ] **Step 4: Commit**

```bash
git add frontend/Customer/js/store-list.js
git commit -m "fix(frontend): escape store/brand names and image URLs in store list"
```

---

### Task 10: group_order.html 注入點轉換（團員名、品名、inline handler 參數）

**Files:**
- Modify: `frontend/Customer/group_order.html`

- [ ] **Step 1: 團員名稱（約 1522 行）**

```js
                                                <span class="font-bold text-sm text-slate-700">${group.userName}${group.isHost ? ' (發起人)' : ''}</span>
```

改為：

```js
                                                <span class="font-bold text-sm text-slate-700">${esc(group.userName)}${group.isHost ? ' (發起人)' : ''}</span>
```

- [ ] **Step 2: 品項圖片與品名（約 1566-1573 行）**

```js
                                                <img src="${imgUrl}" alt="${item.productName || '飲品'}" class="w-full h-full object-cover">
```

改為：

```js
                                                <img src="${esc(imgUrl)}" alt="${esc(item.productName || '飲品')}" class="w-full h-full object-cover">
```

```js
                                                    <p class="font-bold text-slate-800 text-base truncate">${item.productName || item.productNameSnapshot}</p>
```

改為：

```js
                                                    <p class="font-bold text-slate-800 text-base truncate">${esc(item.productName || item.productNameSnapshot)}</p>
```

- [ ] **Step 3: 優惠券按鈕——品名移出 onclick 的 JS 字串情境（約 1607 行）**

inline handler 內的 `'${item.productName}'` 是**雙重情境**（HTML 屬性 → 再被當 JS 解析），單靠 esc() 不足（實體會先被解碼還原成引號）。正確做法：參數改走 dataset，瀏覽器不再二次解析。

```js
                                                    <button onclick="openItemCouponModal('${item.representativeId}', '${item.productName}')" class="p-1.5 text-orange-500 hover:bg-orange-50 rounded-md transition-all" title="使用優惠券">
                                                        <i data-lucide="ticket" class="w-3.5 h-3.5"></i>
                                                    </button>
```

改為：

```js
                                                    <button data-item-id="${esc(item.representativeId)}" data-item-name="${esc(item.productName)}" onclick="openItemCouponModal(this.dataset.itemId, this.dataset.itemName)" class="p-1.5 text-orange-500 hover:bg-orange-50 rounded-md transition-all" title="使用優惠券">
                                                        <i data-lucide="ticket" class="w-3.5 h-3.5"></i>
                                                    </button>
```

（`openItemCouponModal(itemId, itemName)` 收到的就是原始字串，函式簽名與用途不變。）

- [ ] **Step 4: 手動煙霧測試（揪團核心流程）**

1. Live Server 開 group_order.html，用兩組帳號（0912000000 / demo_brand 各自登入不同瀏覽器 profile）模擬團主＋團員。
2. 團員暱稱、品名、規格標籤、杯數按鈕、優惠券按鈕全部正常顯示與點擊。
3. 攻擊樣板驗證：暫時把 Demo 資料庫自己的 users.name 改成 `</span><script>alert(1)</script>`，重新載入頁面——應看到純文字，**不應**彈出 alert。（驗完改回原名。）

- [ ] **Step 5: Commit**

```bash
git add frontend/Customer/group_order.html
git commit -m "fix(frontend): escape member names and product data in group order page; move handler args to dataset"
```

---

### Task 11: store 端訂單列表轉換（order-all-sync.js——顧客資料進店家螢幕）

**背景**：這是店家看到的訂單流。`fmtItem()` 把**顧客的品項備註（note）、團員姓名**直接插 HTML；`renderRow()` 把**顧客姓名/電話**插表格儲存格——顧客可控資料直達店家後台，是最典型的儲存型 XSS 目標群。

**Files:**
- Modify: `frontend/store/js/order-all-sync.js`

- [ ] **Step 1: fmtItem 全面跳逸（52-63 行）**

```js
  const fmtItem = (i) => {
    const name  = i.productNameSnapshot || '飲品';
    const tps   = Array.isArray(i.toppings) ? i.toppings.map(t => `加${t}`).join(',') : '';
    const size  = i.sizeSnapshot  || i.size  || '';
    const sugar = i.sugarSnapshot || i.sugar || '';
    const ice   = i.iceSnapshot   || i.ice   || '';
    const specs = [size, sugar, ice, tps].filter(Boolean).join('|');
    const specsPart = specs ? ` <span style="color:#64748b;font-weight:500">(${specs})</span>` : '';
    const note  = i.note ? ` <span style="color:#ec5b13;font-weight:800">${i.note}</span>` : '';
    const user  = i.userName ? ` <span style="color:#0f172a;font-weight:700">【${i.userName}】</span>` : '';
    return `<span style="font-weight:600;color:#64748b">【${i.qty||1}杯】${name}</span>${specsPart}${note}${user}`;
  };
```

整段改為：

```js
  const fmtItem = (i) => {
    const name  = esc(i.productNameSnapshot || '飲品');
    const tps   = Array.isArray(i.toppings) ? i.toppings.map(t => `加${esc(t)}`).join(',') : '';
    const size  = i.sizeSnapshot  || i.size  || '';
    const sugar = i.sugarSnapshot || i.sugar || '';
    const ice   = i.iceSnapshot   || i.ice   || '';
    const specs = [size, sugar, ice, tps].filter(Boolean).join('|');
    const specsPart = specs ? ` <span style="color:#64748b;font-weight:500">(${esc(specs)})</span>` : '';
    const note  = i.note ? ` <span style="color:#ec5b13;font-weight:800">${esc(i.note)}</span>` : '';
    const user  = i.userName ? ` <span style="color:#0f172a;font-weight:700">【${esc(i.userName)}】</span>` : '';
    return `<span style="font-weight:600;color:#64748b">【${Number(i.qty)||1}杯】${name}</span>${specsPart}${note}${user}`;
  };
```

- [ ] **Step 2: renderRow 表格儲存格跳逸（275 行附近）**

```js
    const tds = [
      `#${o.orderNo||o.id}`,
      badge,
      o.formattedItems,
      o.customerName,
      o.customerPhone,
```

改為：

```js
    const tds = [
      `#${esc(o.orderNo||o.id)}`,
      badge,
      o.formattedItems,          /* 已由 fmtItem 內部跳逸 */
      esc(o.customerName || ''),
      esc(o.customerPhone || ''),
```

（詳情面板的 note/address 已走 textContent，無需修改。）

- [ ] **Step 3: 語法驗證**

Run: `node --check frontend/store/js/order-all-sync.js`
Expected: exit 0

- [ ] **Step 4: 手動煙霧測試**

以 `demo_store/demo1234` 登入門市後台：訂單列表（卡片＋表格）、詳情彈窗品項明細正常顯示；下單時在備註填 `<b>test</b>`，店家端應看到粗體標籤文字本身而非粗體效果。

- [ ] **Step 5: Commit**

```bash
git add frontend/store/js/order-all-sync.js
git commit -m "fix(frontend): escape customer names, phones and item notes in store order views"
```

---

### Task 12: 最終迴歸與收尾

- [ ] **Step 1: 後端全套件**

Run: `mvn -B test`
Expected: `Tests run: 71, Failures: 0, Errors: 0`，BUILD SUCCESS

- [ ] **Step 2: 前端語法總檢**

Run:
```bash
node --check frontend/Customer/js/nav-auth.js \
&& node --check frontend/Customer/js/store-list.js \
&& node --check frontend/store/js/order-all-sync.js && echo ALL_OK
```
Expected: `ALL_OK`

- [ ] **Step 3: （可选）UI 流程驗證**

若網路允許：
```bash
cd scripts && npm install --no-audit --no-fund && npx playwright install chromium && E2E_OFFLINE=1 node ui/run-all.js
```
Expected: 全頁普掃 + 點餐 + 轉盤 + 揪團流程全綠。網路不佳則留給 CI（push 後 GitHub Actions 會跑同一步驟）。

- [ ] **Step 4: 推送分支**

```bash
git push -u origin chore/hardening-followups
```

- [ ] **Step 5: 開 PR**

PR 標題：`chore: hardening follow-ups (XSS sinks, deps, redis snapshot, prod guard)`
內容貼入本計畫的任務清單與對應 commit，標記 reviewer 檢查 `docs/SECURITY-FRONTEND.md` 的 CSP 路線圖是否認可。

---

## Self-Review 紀錄

1. **涵蓋度**：審查報告的四項遺留建議 → 前端 XSS＝Task 7–11；依賴升級＝Task 1–2；RedisCartService LazyInit＝Task 3；生產設定＝Task 4–6。CSP 明確降級為文件（Task 6），理由（全站 inline script 需搬移）已寫入路線圖。
2. **Placeholder 掃描**：所有程式步驟皆附完整 before/after 或整檔內容；唯一條件分支（OrderItem 是否有 @Data）在 Task 3 Step 1 給了兩種完整寫法。
3. **一致性**：`esc()` 定義在四個檔案完全相同；`assertProductionSecret(boolean, String)` 與 `DEV_DEFAULT_SECRET_PREFIX` 的名稱在測試與實作一致；`CartItemSnapshot` 七個欄位在 record、saveItem、測試斷言三處一致。
