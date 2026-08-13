# 驗證腳本

不是應用程式的一部分（前端是純靜態頁，沒有建置流程），只用來驗證跑起來的服務。
CI 會照同樣的順序跑一遍：[`.github/workflows/ci.yml`](../.github/workflows/ci.yml)。

## 用法

```bash
# 先把服務跑起來
docker compose up -d && mvn spring-boot:run
python3 -m http.server 5500 --directory frontend    # 另開一個終端

cd scripts
npm install

node e2e-verify.js                  # API 端對端：12 面向 / 67 項斷言
npx playwright install chromium
node ui/run-all.js                  # UI：全頁面普掃 + 點餐 / 轉盤 / 揪團
```

單獨跑某一支：

```bash
node ui/page-sweep.js       # 30 個頁面逐一載入，抓 JS 例外與 API 錯誤
node ui/flow-order.js       # 點餐：客製化 → 購物車 → 結帳 → 訂單成立
node ui/flow-wheel.js       # 轉盤：抽獎 → 優惠券入帳 → 當日不可再抽
node ui/flow-group.js       # 揪團：建立 → 團員加點 → 團員付款 → 團長送出
```

## 環境變數

| 變數 | 用途 |
|------|------|
| `API_BASE` | 後端位址，預設 `http://127.0.0.1:8082` |
| `FE_BASE` | 靜態前端位址，預設 `http://127.0.0.1:5500` |
| `HEADED=1` | 開有頭瀏覽器，肉眼看流程跑 |
| `E2E_SHOT_DIR` | 截圖輸出位置，預設 `target/e2e-shots/` |
| `CHROME_PATH` | 自備 Chromium 執行檔（不設就用 Playwright 內建） |
| `E2E_CDN_DIR` | 離線環境專用：把外部 CDN 換成這個資料夾裡的檔案 |

## 為什麼要有這兩層

`mvn test` 抓不到「服務真的跑起來、照著使用者路徑走一遍才會現形」的問題。
實際被這兩層抓到的包括：訂單完成頁固定 500（交易外讀 LAZY 關聯）、
結帳頁的姓名電話永遠讀不到（相對路徑打到靜態伺服器）、
揪團明明建立成功卻顯示「建立揪團失敗」（QR 元件載入失敗中斷了整個 try）。

所以流程腳本刻意**操作真實 UI**（點按鈕、選規格、兩個瀏覽器分飾團長與團員），
而不是直接呼叫 API —— 直接打 API 的話，上面那些問題一個都不會出現。
