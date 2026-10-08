# 前端安全：現況與 CSP 路線圖

## Secret 防護現況（2026-08-26）

- **歷史事件**：auth/ 下五個檔案曾硬編碼真實 Firebase Web API key（`project-b5e05`），
  自 initial commit 起公開於 GitHub。已實測 Identity Toolkit ×2 / Maps Static 三端點均回
  `API_KEY_INVALID`，確認金鑰已撤銷；程式碼亦已移除硬編碼，統一由
  `frontend/Customer/js/firebase-config.js`（placeholder）提供設定。
- **CI 掃描**：`.github/workflows/ci.yml` 第 0 層為 gitleaks（`gitleaks-action@v2`，
  `fetch-depth: 0`）。新外洩會直接讓 CI 變紅。
- **歷史殘留豁免**：`.gitleaksignore` 僅豁免上述「已撤銷」金鑰在舊 commit 的 5 筆 finding
  （fingerprints 由全歷史掃描產生）。除此之外的任何 finding 都代表新的外洩。
- **倉庫擁有者待辦（手動開關，無法用 CI 代辦）**：
  GitHub repo → Settings → Code security and analysis →
  1. Secret scanning → **Enable**
  2. Push protection → **Enable**（有人要 push 金鑰時當場擋下）
- **未來放新 Firebase key 的規則**：
  1. 只填在 `firebase-config.js`（單一事實來源），不得複製到其他檔案
  2. Google Cloud Console：API restrictions 限縮至 Identity Toolkit API；
     Website restrictions 限定授權網域
  3. Firebase Console → Authentication → Settings → Authorized domains 白名單
  4. 啟用 Firebase App Check（reCAPTCHA v3 / Play Integrity）
  5. 服務帳戶 JSON 只走 `FIREBASE_CONFIG_PATH=file:` 指向掛載的 secret，永不入版控

## 現況（2026-10-08 複查）

- 使用者可控資料的渲染點已導入 `esc()` HTML 跳逸：
  - `frontend/Customer/js/nav-auth.js`（toast 訊息、常用地址、購物車下拉）
  - `frontend/Customer/js/store-list.js`（店家卡片、品牌下拉）
  - `frontend/Customer/group_order.html`（團員名稱、品名、圖片屬性、inline handler 參數改走 dataset）
  - `frontend/store/js/order-all-sync.js`（顧客姓名/電話/品項備註）
- 評論監控（Brand 端）原本就以 `textContent` 渲染，無需修改。
- 後端已修復的對應面：揪團金額伺服器重算（H-1）、優惠券原子消耗＋擁有權（H-2）、
  揪團狀態越權（H-3）、idList 任意刪除（H-4）、上傳檔案 magic bytes 白名單（M-1）。
- 顧客商品頁的商品、分類文字與屬性值在插入 HTML 前會跳逸；分類錨點使用程式產生的安全識別值。
- 每日轉盤公開品牌端點只輸出頁面所需的公開欄位，不再序列化完整品牌實體。
- 全專案複查另外補上帳號合併證明、已刪除帳號 JWT、揪團數字 ID 權限、商品租戶隔離與伺服器定價；細節見 [README 的安全複查記錄](../README.md#2026-10-08-全專案程式碼複查補強)。

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
2. **P2**：以上述政策加到每頁 `<meta http-equiv="Content-Security-Policy">`，
   先在 staging 觀察 console 違規回報。
3. **P3**：頁面由 Spring 靜態資源服務後（/Customer/** 等已是），改用 response header 下發
   （WebConfig 加 Content-Security-Policy header），並移除 `'unsafe-inline'`。

> 注意：`onclick="..."` inline handler 屬於 script-src 範疇，P1 需一併改為
> `addEventListener` 或 data-* + 事件委派。
