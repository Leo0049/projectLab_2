#!/usr/bin/env bash
# 把前端依賴的第三方資源抓進 frontend/vendor/，讓專案不靠 CDN 也能完整展示。
#
#   ./scripts/vendor-assets.sh
#
# 為什麼要做：這些函式庫原本全部從 CDN 載入，網路不通或會議室擋掉 CDN 時
# 會出現 `L is not defined` / `tailwind is not defined` / `SockJS is not defined`，
# 地圖、結帳、揪團、個人資料四頁直接壞掉；Tailwind 掛掉更是整頁沒有樣式。
#
# 沒有抓進來的（刻意）：
#   - 內文字型（Noto Sans TC / Plus Jakarta Sans / Public Sans）：
#     Noto Sans TC 光是 subset 就 315 個檔案，而字型載不到只是退回系統字型，
#     不影響任何功能。圖示字型有抓（載不到會變成方框或直接顯示 "shopping_cart"）。
#   - 地圖圖磚：OpenStreetMap 的 tile 是線上服務，本質上抓不下來。
#     離線時 Leaflet 會正常初始化，只是底圖是灰的。
#   - Nominatim 地址搜尋：同上，是線上 API。
#   - Firebase：社群登入本來就需要連外，且頁面已有 try/catch 降級。
set -euo pipefail

cd "$(dirname "$0")/.."
V=frontend/vendor
UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36'

get() { # get <url> <輸出路徑>
    mkdir -p "$(dirname "$2")"
    curl -sSL -A "$UA" "$1" -o "$2"
    printf '  %-52s %s\n' "$(basename "$2")" "$(du -h "$2" | cut -f1)"
}

echo "▸ JS 函式庫"
# Tailwind 的 CDN 版本是瀏覽器端 JIT 編譯器，plugins 由 query string 決定。
# 專案用到 forms / typography / container-queries 三種組合，抓涵蓋全部的那一份即可。
get "https://cdn.tailwindcss.com?plugins=forms,typography,container-queries" "$V/tailwind.js"
get "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"                        "$V/leaflet/leaflet.js"
get "https://unpkg.com/lucide@latest"                                        "$V/lucide.js"
get "https://cdn.jsdelivr.net/npm/chart.js"                                  "$V/chart.js"
get "https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js"        "$V/sockjs.min.js"
get "https://cdn.jsdelivr.net/npm/stompjs@2.3.3/lib/stomp.min.js"            "$V/stomp.min.js"
get "https://cdn.jsdelivr.net/gh/davidshimjs/qrcodejs/qrcode.min.js"         "$V/qrcode.min.js"

echo "▸ Leaflet CSS 與標記圖片"
get "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" "$V/leaflet/leaflet.css"
for img in marker-icon.png marker-icon-2x.png marker-shadow.png layers.png layers-2x.png; do
    get "https://unpkg.com/leaflet@1.9.4/dist/images/$img" "$V/leaflet/images/$img"
done

echo "▸ Font Awesome"
get "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css" "$V/font-awesome/css/all.min.css"
for f in fa-solid-900 fa-regular-400 fa-brands-400; do
    get "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/webfonts/$f.woff2" "$V/font-awesome/webfonts/$f.woff2"
done
# CSS 內含 .ttf 的 fallback，本機沒抓；移掉那段避免瀏覽器再去連外
sed -i 's#,url(\.\./webfonts/[a-z0-9-]*\.ttf)format("truetype")##g' "$V/font-awesome/css/all.min.css"

echo "▸ Bootstrap Icons"
get "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css" "$V/bootstrap-icons/bootstrap-icons.min.css"
for ext in woff2 woff; do
    get "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/fonts/bootstrap-icons.$ext" \
        "$V/bootstrap-icons/fonts/bootstrap-icons.$ext"
done

echo "▸ Material Symbols（圖示字型，可變字重）"
MSYM='https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:wght,FILL@100..700,0..1&display=block'
get "$MSYM" "$V/material-symbols/material-symbols.css"
FONT_URL=$(grep -oE 'https://fonts\.gstatic\.com[^)]+' "$V/material-symbols/material-symbols.css" | head -1)
get "$FONT_URL" "$V/material-symbols/material-symbols.woff2"
# 把 CSS 內的 gstatic 連結改指本機
sed -i "s#$FONT_URL#./material-symbols.woff2#" "$V/material-symbols/material-symbols.css"

echo
echo "✓ 完成，總計 $(du -sh $V | cut -f1)"
echo "  仍需連外的只剩：地圖圖磚、Nominatim 地址搜尋、Firebase 社群登入（皆為線上服務）"
