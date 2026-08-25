# 前端安全：現況與 CSP 路線圖

## 現況（2026-08-25 審查後）

- 使用者可控資料的渲染點已導入 `esc()` HTML 跳逸：
  - `frontend/Customer/js/nav-auth.js`（toast 訊息、常用地址、購物車下拉）
  - `frontend/Customer/js/store-list.js`（店家卡片、品牌下拉）
  - `frontend/Customer/group_order.html`（團員名稱、品名、圖片屬性、inline handler 參數改走 dataset）
  - `frontend/store/js/order-all-sync.js`（顧客姓名/電話/品項備註）
- 評論監控（Brand 端）原本就以 `textContent` 渲染，無需修改。
- 後端已修復的對應面：揪團金額伺服器重算（H-1）、優惠券原子消耗＋擁有權（H-2）、
  揪團狀態越權（H-3）、idList 任意刪除（H-4）、上傳檔案 magic bytes 白名單（M-1）。

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
