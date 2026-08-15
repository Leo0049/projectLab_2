#!/usr/bin/env bash
# 演示前把示範資料重置成剛啟動的樣子。
#
#   ./scripts/demo-reset.sh
#
# 為什麼需要這支：
#   1. 門市後台「待處理」的訂單有 10 分鐘倒數，逾時會自動取消。服務開著講了
#      十幾分鐘架構之後才打開後台，那兩張待接單的示範訂單已經自己取消掉了，
#      接單／拒單這段就沒東西可以示範。
#   2. 每日轉盤一個帳號一天只能抽一次。彩排時抽過，正式演示就只會看到
#      「你今日已參加過轉盤遊戲」。
#   3. 錢包餘額會被彩排時下的單扣掉。
#
# 做法很直接：清空資料庫與 Redis，再讓 DemoDataSeeder 重新植入（實測 13 秒）。
set -euo pipefail

cd "$(dirname "$0")/.."

DB_CONTAINER=${DB_CONTAINER:-joindrink-mysql}
REDIS_CONTAINER=${REDIS_CONTAINER:-joindrink-redis}
DB_NAME=${DB_NAME:-joindrink}
DB_USER=${DB_USER:-root}
DB_PASS=${DB_PASS:-root}
PORT=${PORT:-8082}
JAR=target/demo-0.0.1-SNAPSHOT.jar

echo "▸ 確認 MySQL / Redis 容器"
docker compose up -d >/dev/null
until docker exec "$DB_CONTAINER" mysqladmin ping -u"$DB_USER" -p"$DB_PASS" >/dev/null 2>&1; do sleep 1; done

echo "▸ 停止後端（若正在執行）"
pkill -f "$JAR" 2>/dev/null || true
# mvn spring-boot:run 起的行程
pkill -f "spring-boot:run" 2>/dev/null || true
sleep 2

echo "▸ 清空資料庫與 Redis"
docker exec "$DB_CONTAINER" mysql -u"$DB_USER" -p"$DB_PASS" \
    -e "DROP DATABASE IF EXISTS $DB_NAME; CREATE DATABASE $DB_NAME CHARACTER SET utf8mb4;" 2>/dev/null
docker exec "$REDIS_CONTAINER" redis-cli FLUSHALL >/dev/null

if [ ! -f "$JAR" ]; then
    echo "▸ 找不到 $JAR，資料已清空，請自行重新啟動服務（mvn spring-boot:run）"
    exit 0
fi

echo "▸ 重新啟動後端，等待 DemoDataSeeder 植入示範資料"
START=$(date +%s)
setsid nohup java -jar "$JAR" > target/demo-app.log 2>&1 < /dev/null &
# ⚠️ 不能只等 /api/home 有回應。DemoDataSeeder 是 ApplicationRunner，
# 網頁伺服器已經開始接受請求時它可能還在跑——實測這個空檔打登入會拿到
# 「帳號不存在」。這裡等到示範門市真的查得到為止。
until [ "$(curl -sf "http://127.0.0.1:$PORT/api/stores/nearby?lat=25.0336&lng=121.5435" 2>/dev/null \
        | grep -c storeName)" != "0" ]; do
    sleep 1
    if [ $(( $(date +%s) - START )) -gt 120 ]; then
        echo "✗ 逾時未啟動，請看 target/demo-app.log"
        exit 1
    fi
done

echo "✓ 重置完成（$(( $(date +%s) - START )) 秒）"
echo "  顧客 0912000000 / 品牌 demo_brand / 門市 demo_store，密碼皆為 demo1234"
echo "  待處理訂單的 10 分鐘倒數從現在開始計算"
