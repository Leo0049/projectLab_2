/**
 * JoinDrink 全功能驗證腳本
 *
 *   docker compose up -d && mvn spring-boot:run     # 先把服務跑起來
 *   node scripts/e2e-verify.js                      # 再跑這支
 *
 * 直接打真實服務，走完每條主要流程並比對回傳狀態。
 * 涵蓋三種角色的認證、瀏覽、購物車、個人訂單全生命週期、拒單退款、揪團、
 * 轉盤、收藏／地址、授權防護，以及 WebSocket 的 CONNECT / SUBSCRIBE 授權。
 */
// ws 只有第 11 節（WebSocket/STOMP 授權）會用到；沒安裝就自動跳過該節，
// 其餘 12 個面向仍然完整執行，不需要任何 npm install。
let WebSocket = null;
try { WebSocket = require('ws'); } catch (e) { /* 選用 */ }

const API = process.env.API_BASE || 'http://127.0.0.1:8082';

let pass = 0, fail = 0;
const fails = [];
function check(name, ok, detail) {
  if (ok) { pass++; console.log(`  ✓ ${name}`); }
  else { fail++; fails.push(name); console.log(`  ✗ ${name}${detail ? '  → ' + detail : ''}`); }
}
function section(t) { console.log(`\n── ${t} ──`); }

async function req(method, path, { token, body, raw } = {}) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h.Authorization = 'Bearer ' + token;
  const r = await fetch(API + path, { method, headers: h, body: body ? JSON.stringify(body) : undefined });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch (e) { /* 非 JSON */ }
  return raw ? { status: r.status, json, text } : json;
}
// 專案有兩種回應形狀：統一格式 {code,msg,data}，以及少數直接回傳陣列／物件的舊端點。
const ok = (j) => Array.isArray(j) ? true : !!(j && (j.code === '200' || j.code === 200));
const D = (j) => Array.isArray(j) ? j : (j || {}).data;

(async () => {
  // ─────────────────────────────────────────────
  section('1. 認證');
  const cust = D(await req('POST', '/api/auth/login', { body: { phone: '0912000000', password: 'demo1234' } }));
  check('顧客登入取得 JWT', !!(cust && cust.token));
  const brand = D(await req('POST', '/api/auth/corporate-login', { body: { account: 'demo_brand', password: 'demo1234' } }));
  check('品牌登入取得 JWT', !!(brand && brand.token));
  const store = D(await req('POST', '/api/auth/corporate-login', { body: { account: 'demo_store', password: 'demo1234' } }));
  check('門市登入取得 JWT', !!(store && store.token));

  const badPw = await req('POST', '/api/auth/login', { body: { phone: '0912000000', password: 'wrong-password' } });
  check('錯誤密碼被拒絕', !ok(badPw));

  const noToken = await req('GET', '/api/orders/history', { raw: true });
  check('未帶 token 存取需登入端點被擋', noToken.status === 401 || noToken.status === 403,
    `HTTP ${noToken.status}`);

  const CT = cust.token, BT = brand.token, STT = store.token, UID = cust.userId;

  // 第二位顧客：揪團與跨帳號授權測試都需要
  const phone2 = '0921' + String(Date.now()).slice(-6);
  let cust2 = D(await req('POST', '/api/auth/register',
    { body: { phone: phone2, password: 'demo1234', name: '測試團員' } }));
  if (!cust2 || !cust2.token)
    cust2 = D(await req('POST', '/api/auth/login', { body: { phone: phone2, password: 'demo1234' } }));
  check('第二位顧客註冊／登入（mock SMS，免 Firebase）', !!(cust2 && cust2.token));
  const CT2 = cust2 && cust2.token, UID2 = cust2 && cust2.userId;

  // ─────────────────────────────────────────────
  section('2. 公開瀏覽');
  const nearby = await req('GET', '/api/stores/nearby?lat=25.0336&lng=121.5435');
  const stores = D(nearby) && D(nearby).stores;
  check('附近門市列表（含距離排序）', ok(nearby) && Array.isArray(stores) && stores.length > 0,
    `取得 ${stores ? stores.length : 0} 間`);
  const storeId = 1;
  const sd = await req('GET', `/api/stores/${storeId}`);
  check('門市公開資訊', ok(sd) && !!D(sd).name);
  const prods = await req('GET', `/api/products/store/${storeId}`);
  const plist = D(prods);
  check('門市飲品列表', ok(prods) && Array.isArray(plist) && plist.length > 0,
    `取得 ${plist ? plist.length : 0} 款`);
  const productId = plist && plist[0] && (plist[0].id || plist[0].productId);
  const pd = await req('GET', `/api/products/${productId}`);
  check('飲品詳情（含規格與配料）', ok(pd) && !!D(pd));

  // ─────────────────────────────────────────────
  section('3. 錢包與帳本');
  const before = D(await req('GET', '/api/wallet', { token: CT }));
  const balBefore = Number((before && (before.balance ?? before.newBalance)) || 0);
  const top = await req('POST', '/api/wallet/topup', { token: CT, body: { amount: 1000 } });
  check('儲值 1000', ok(top));
  const after = D(await req('GET', '/api/wallet', { token: CT }));
  const balAfter = Number((after && (after.balance ?? after.newBalance)) || 0);
  check('餘額正確增加 1000', Math.abs(balAfter - balBefore - 1000) < 0.01,
    `${balBefore} → ${balAfter}`);

  // ─────────────────────────────────────────────
  section('4. 購物車');
  const addCart = await req('POST', '/api/cart/items', {
    token: CT, body: { storeId, productId, qty: 1, sugar: '半糖', ice: '少冰', size: '大杯' },
  });
  check('加入購物車', ok(addCart));
  const cart = await req('GET', '/api/cart', { token: CT });
  check('讀取購物車', ok(cart));

  // ─────────────────────────────────────────────
  section('5. 個人訂單全流程');
  const place = await req('POST', '/api/orders/place', {
    token: CT,
    body: {
      storeId, note: 'E2E 驗證用訂單',
      items: [{ productId, sugarSnapshot: '半糖', iceSnapshot: '少冰', sizeSnapshot: '大杯', paymentType: 'WALLET' }],
    },
  });
  check('建立個人訂單', ok(place), JSON.stringify(place).slice(0, 120));
  const orderId = D(place) && (D(place).orderId || D(place).id || D(place).groupOrderId);
  check('回傳訂單 ID', !!orderId);

  const detail1 = await req('GET', `/api/orders/${orderId}`, { token: CT });
  check('查詢訂單明細', ok(detail1));

  const statusAfterSubmit = D(await req('GET', `/api/orders/${orderId}`, { token: CT }));
  check('下單後狀態為 SUBMITTED',
    ['SUBMITTED', 'PREPARING'].includes(statusAfterSubmit && statusAfterSubmit.status),
    `狀態=${statusAfterSubmit && statusAfterSubmit.status}`);

  const accept = await req('POST', `/api/stores/dashboard/orders/${orderId}/accept`, { token: STT });
  const s2 = D(await req('GET', `/api/orders/${orderId}`, { token: CT }));
  check('門市接單 → PREPARING', ok(accept) && s2.status === 'PREPARING', `狀態=${s2 && s2.status}`);

  const prod = await req('POST', `/api/stores/dashboard/orders/${orderId}/complete-production`, { token: STT });
  const s3 = D(await req('GET', `/api/orders/${orderId}`, { token: CT }));
  check('製作完成 → READY', ok(prod) && s3.status === 'READY', `狀態=${s3 && s3.status}`);

  const fin = await req('POST', `/api/stores/dashboard/orders/${orderId}/finalize`, { token: STT });
  const s4 = D(await req('GET', `/api/orders/${orderId}`, { token: CT }));
  check('完成取餐 → COMPLETED', ok(fin) && s4.status === 'COMPLETED', `狀態=${s4 && s4.status}`);

  const balDone = Number((D(await req('GET', '/api/wallet', { token: CT })) || {}).balance || 0);
  check('完成後餘額有實際扣款', balDone < balAfter, `${balAfter} → ${balDone}`);

  const rate = await req('POST', `/api/orders/${orderId}/rating`, { token: CT, body: { rating: 5 } });
  check('訂單評分', ok(rate));

  // ─────────────────────────────────────────────
  section('6. 拒單與取消退款');
  // 退款要比對「回到下單前的數字」。原本寫成 balAfter >= balBefore，
  // 實測把退款整段拿掉時這條仍然會過——因為沒退款時餘額本來就沒變、剛好滿足 >=。
  const balBeforePlace2 = Number((D(await req('GET', '/api/wallet', { token: CT })) || {}).balance || 0);
  const place2 = await req('POST', '/api/orders/place', {
    token: CT, body: { storeId, items: [{ productId, sugarSnapshot: '無糖', iceSnapshot: '去冰', paymentType: 'WALLET' }] },
  });
  const oid2 = D(place2) && (D(place2).orderId || D(place2).id);
  await req('POST', `/api/group-orders/${oid2}/submit`, { token: CT, body: {} });
  const rej = await req('POST', `/api/stores/dashboard/orders/${oid2}/reject`, { token: STT });
  const s5 = D(await req('GET', `/api/orders/${oid2}`, { token: CT }));
  check('門市拒單 → REJECTED', ok(rej) && ['REJECTED', 'CANCELLED'].includes(s5 && s5.status),
    `狀態=${s5 && s5.status}`);
  const balAfterReject = Number((D(await req('GET', '/api/wallet', { token: CT })) || {}).balance || 0);
  check('拒單後款項原數退回', balAfterReject === balBeforePlace2, `${balBeforePlace2} → ${balAfterReject}`);

  // 顧客自己取消是另一條路徑（cancelOrderV2 → handleGroupOrderCancellation），
  // 與門市拒單走的不是同一段程式，退款要分開驗。
  const balBeforePlace3 = Number((D(await req('GET', '/api/wallet', { token: CT })) || {}).balance || 0);
  const place3 = await req('POST', '/api/orders/place', {
    token: CT, body: { storeId, items: [{ productId, sugarSnapshot: '半糖', iceSnapshot: '正常冰', paymentType: 'WALLET' }] },
  });
  const oid3 = D(place3) && (D(place3).orderId || D(place3).id);
  await req('POST', `/api/group-orders/${oid3}/submit`, { token: CT, body: {} });
  const balEscrowed = Number((D(await req('GET', '/api/wallet', { token: CT })) || {}).balance || 0);
  check('下單後 escrow 先凍結餘額', balEscrowed < balBeforePlace3, `${balBeforePlace3} → ${balEscrowed}`);
  // 這支端點回的是原始 Map（沒有 code 欄位），要看 HTTP 狀態碼
  const cancel = await req('PUT', `/api/orders/${oid3}/cancel/v2`, { token: CT, raw: true });
  check('顧客取消自己的訂單', cancel.status === 200, `HTTP ${cancel.status} ${cancel.text.slice(0, 80)}`);
  const s6 = D(await req('GET', `/api/orders/${oid3}`, { token: CT }));
  check('取消後狀態為 CANCELLED', s6 && s6.status === 'CANCELLED', `狀態=${s6 && s6.status}`);
  const balAfterCancel = Number((D(await req('GET', '/api/wallet', { token: CT })) || {}).balance || 0);
  // 用「等於取消前」而不是「大於等於」：少退、重複退、沒退都會被抓到
  check('取消後 escrow 原數退回', balAfterCancel === balBeforePlace3,
    `${balBeforePlace3} → ${balEscrowed} → ${balAfterCancel}`);

  // ─────────────────────────────────────────────
  section('7. 揪團流程');
  const gc = await req('POST', '/api/group-orders', { token: CT, body: { storeId, type: 'GROUP' } });
  check('建立揪團', ok(gc), JSON.stringify(gc).slice(0, 120));
  const gid = D(gc) && (D(gc).groupOrderId || D(gc).id);
  const shareToken = D(gc) && (D(gc).shareToken || D(gc).token);
  check('取得分享 token', !!shareToken);

  const byToken = await req('GET', `/api/group-orders/${shareToken}`, { token: CT2 });
  check('用 token 查揪團（分享連結）', ok(byToken));

  const join = await req('POST', `/api/group-orders/${gid}/join`, {
    token: CT2, body: { items: [{ productId, sugar: '微糖', ice: '少冰', qty: 1, paymentType: 'WALLET' }] },
  });
  check('第二位顧客用 token 加入並加點', ok(join), JSON.stringify(join).slice(0, 120));

  const members = await req('GET', `/api/group-orders/${gid}/members`, { token: CT });
  const mlist = D(members);
  check('揪團成員與品項列表', ok(members) && !!mlist,
    Array.isArray(mlist) ? `${mlist.length} 位成員` : '');

  const gsubmit = await req('POST', `/api/group-orders/${gid}/submit`, { token: CT, body: {} });
  check('團長統一送出', ok(gsubmit), JSON.stringify(gsubmit).slice(0, 120));

  // ─────────────────────────────────────────────
  section('8. 優惠券轉盤');
  const spinStatus = await req('GET', '/api/coupons/spin-status', { token: CT });
  check('查詢今日轉盤狀態', ok(spinStatus));
  const wheelBrands = await req('GET', '/api/game-wheel/brands', { token: CT });
  check('轉盤品牌列表', ok(wheelBrands));
  const spin = await req('POST', '/api/coupons/spin', { token: CT, body: { brandId: 1 } });
  check('執行抽獎（已抽過會回明確錯誤，兩者都算正常）',
    ok(spin) || (spin && spin.msg), JSON.stringify(spin).slice(0, 100));
  const coupons = await req('GET', '/api/coupons/my', { token: CT });
  check('查詢我的優惠券', ok(coupons));

  // ─────────────────────────────────────────────
  section('9. 收藏與常用地址');
  const favToggle = await req('POST', '/api/user-favorites/toggle', { token: CT, body: { userId: UID, storeId } });
  check('切換收藏店家', ok(favToggle) || !!(favToggle && favToggle.status));
  const favs = await req('GET', `/api/user-favorites/user/${UID}`, { token: CT });
  check('查詢收藏清單', ok(favs) || favs !== null);

  const addAddr = await req('POST', `/api/users/${UID}/addresses`, {
    token: CT, body: { city: '台北市', district: '大安區', street: '復興南路一段 100 號', label: '公司' },
  });
  check('新增常用地址', ok(addAddr) || !!(addAddr && addAddr.id), JSON.stringify(addAddr).slice(0, 90));
  const addrs = await req('GET', `/api/users/${UID}/address`, { token: CT });
  check('查詢常用地址', ok(addrs) && Array.isArray(D(addrs)));

  // ─────────────────────────────────────────────
  section('10. 授權防護（這些都必須被擋下）');
  const otherProfile = await req('GET', `/api/users/${UID}`, { token: CT2, raw: true });
  check('B 帳號讀 A 的個資 → 拒絕',
    otherProfile.status === 403 || (otherProfile.json && otherProfile.json.code === '403'),
    `HTTP ${otherProfile.status} ${otherProfile.json && otherProfile.json.code}`);

  const otherWallet = await req('GET', `/api/users/${UID}/store-credit-records`, { token: CT2, raw: true });
  check('B 帳號讀 A 的錢包帳本 → 拒絕',
    otherWallet.status === 403 || (otherWallet.json && otherWallet.json.code === '403'),
    `HTTP ${otherWallet.status} ${otherWallet.json && otherWallet.json.code}`);

  const forgeRecharge = await req('POST', `/api/users/${UID}/recharge`, {
    token: CT2, body: { amount: 99999 }, raw: true,
  });
  check('B 帳號替 A 儲值 99999 → 拒絕',
    forgeRecharge.status === 403 || (forgeRecharge.json && forgeRecharge.json.code === '403'),
    `HTTP ${forgeRecharge.status}`);

  const custHitsStore = await req('GET', '/api/stores/orders', { token: CT, raw: true });
  check('顧客 token 打門市端點 → 拒絕',
    custHitsStore.status === 401 || custHitsStore.status === 403, `HTTP ${custHitsStore.status}`);

  const anonWrite = await req('POST', '/api/products', { body: { name: '未授權商品', basePrice: 1 }, raw: true });
  check('未登入新增商品 → 拒絕（端點已移除）',
    anonWrite.status === 401 || anonWrite.status === 403 || anonWrite.status === 404 || anonWrite.status === 405,
    `HTTP ${anonWrite.status}`);

  // ── OrderController 的 IDOR（本輪驗證新發現，全部以 token 身分為準）──
  const idorCards = await req('GET', `/api/orders/user/${UID}/cards?page=0&size=2`, { token: CT2, raw: true });
  check('B 讀 A 的訂單卡片 → 拒絕', idorCards.status === 403,
    `HTTP ${idorCards.status}`);

  const idorActive = await req('GET', `/api/orders/user/${UID}/active`, { token: CT2, raw: true });
  check('B 讀 A 的進行中訂單 → 拒絕', idorActive.status === 403, `HTTP ${idorActive.status}`);

  const idorRecent = await req('GET', `/api/orders/user/${UID}/recent-cards`, { token: CT2, raw: true });
  check('B 讀 A 的近期訂單 → 拒絕', idorRecent.status === 403, `HTTP ${idorRecent.status}`);

  // 關鍵：不帶 userId 參數時，舊寫法會整段跳過檢查
  const idorStatus = await req('PUT', `/api/orders/${orderId}/status?status=OPEN`, { token: CT2, raw: true });
  check('B 不帶 userId 竄改 A 的訂單狀態 → 拒絕', idorStatus.status === 403, `HTTP ${idorStatus.status}`);

  const idorItems = await req('GET', `/api/orders/${orderId}/items`, { token: CT2, raw: true });
  check('B 讀 A 的訂單品項 → 拒絕', idorItems.status === 403, `HTTP ${idorItems.status}`);

  // ── POST /api/orders/checkout：身分與金額都不可由用戶端指定 ──
  // 這支是顧客結帳頁真正打的端點。舊寫法直接用 request.userId 建單扣款，
  // 且把 totalAmount / finalPrice 當成成交價。
  const checkoutBody = (claimedUserId, price) => ({
    userId: claimedUserId, storeId, totalAmount: price, paymentMethod: 'WALLET', deliveryType: 'pickup',
    items: [{ productId, userId: claimedUserId, quantity: 1, productNameSnapshot: '免費飲料',
              unitPriceSnapshot: price, finalPrice: price,
              sugarSnapshot: '半糖', iceSnapshot: '正常冰', sizeSnapshot: '大杯' }],
  });

  const victimBefore = Number((D(await req('GET', '/api/wallet', { token: CT })) || {}).balance || 0);
  await req('POST', '/api/orders/checkout', { token: CT2, body: checkoutBody(UID, 1), raw: true });
  const victimAfter = Number((D(await req('GET', '/api/wallet', { token: CT })) || {}).balance || 0);
  check('B 用 A 的 userId 結帳 → 不得動到 A 的錢包', victimAfter === victimBefore,
    `${victimBefore} → ${victimAfter}`);

  const tamperBefore = Number((D(await req('GET', '/api/wallet', { token: CT })) || {}).balance || 0);
  const tamper = await req('POST', '/api/orders/checkout', { token: CT, body: checkoutBody(UID, 1), raw: true });
  const tamperAfter = Number((D(await req('GET', '/api/wallet', { token: CT })) || {}).balance || 0);
  const charged = tamperBefore - tamperAfter;
  check('竄改 finalPrice 為 1 → 仍以資料庫售價扣款', tamper.status === 200 && charged > 1,
    `HTTP ${tamper.status}，實扣 ${charged}`);

  const storeDump = await req('GET', '/api/orders/store/1', { token: CT2, raw: true });
  check('顧客撈門市全部訂單 → 端點已移除', storeDump.status === 404 || storeDump.status === 403,
    `HTTP ${storeDump.status}`);

  // B 參加過揪團所以本來就有自己的訂單；重點是「不能出現 A 的那張單」
  const ordersParam = await req('GET', `/api/orders?userId=${UID}`, { token: CT2 });
  const mine = (ordersParam && ordersParam.content) || [];
  check('GET /api/orders?userId=A 仍只回自己的訂單',
    Array.isArray(mine) && !mine.some(o => String(o.orderId) === String(orderId)),
    `${mine.length} 筆，含 A 的訂單=${mine.some(o => String(o.orderId) === String(orderId))}`);

  // ─────────────────────────────────────────────
  section('11. WebSocket / STOMP 授權');
  const wsUrl = API.replace(/^http/, 'ws') + '/ws-cart/websocket';
  if (!WebSocket) {
    console.log('  － 略過（未安裝 ws 套件：npm i ws 後可一併驗證）');
  } else {

  function stompTry({ token, dest, timeout = 6000 }) {
    return new Promise((resolve) => {
      const ws = new WebSocket(wsUrl);
      let connected = false, errored = false, done = false;
      const finish = (r) => { if (!done) { done = true; try { ws.close(); } catch (e) {} resolve(r); } };
      const t = setTimeout(() => finish({ connected, errored }), timeout);
      ws.on('open', () => {
        const h = token ? `\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer ${token}` :
                          `\naccept-version:1.2\nheart-beat:0,0`;
        ws.send(`CONNECT${h}\n\n\0`);
      });
      ws.on('message', (m) => {
        const s = m.toString();
        if (s.startsWith('CONNECTED')) {
          connected = true;
          if (!dest) { clearTimeout(t); return finish({ connected, errored }); }
          ws.send(`SUBSCRIBE\nid:sub-0\ndestination:${dest}\n\n\0`);
          setTimeout(() => { clearTimeout(t); finish({ connected, errored }); }, 1200);
        } else if (s.startsWith('ERROR')) {
          errored = true; clearTimeout(t); finish({ connected, errored });
        }
      });
      ws.on('error', () => { errored = true; clearTimeout(t); finish({ connected, errored }); });
      ws.on('close', () => { clearTimeout(t); finish({ connected, errored }); });
    });
  }

  const wsNoToken = await stompTry({ token: null });
  check('CONNECT 不帶 JWT → 拒絕', !wsNoToken.connected || wsNoToken.errored,
    `connected=${wsNoToken.connected} errored=${wsNoToken.errored}`);

  const wsWithToken = await stompTry({ token: CT });
  check('CONNECT 帶有效 JWT → 允許', wsWithToken.connected && !wsWithToken.errored,
    `connected=${wsWithToken.connected} errored=${wsWithToken.errored}`);

  const wsOwn = await stompTry({ token: CT, dest: `/topic/order/${orderId}` });
  check('訂閱自己的訂單 → 允許', wsOwn.connected && !wsOwn.errored,
    `errored=${wsOwn.errored}`);

  const wsOther = await stompTry({ token: CT2, dest: `/topic/order/${orderId}` });
  check('訂閱他人的訂單 → 拒絕', wsOther.errored,
    `connected=${wsOther.connected} errored=${wsOther.errored}`);
  }

  // ─────────────────────────────────────────────
  section('12. 三種後台的資料端點');
  for (const [name, path, tok] of [
    ['門市 Dashboard 今日摘要', '/api/stores/dashboard/today-summary', STT],
    ['門市訂單列表（分頁）', '/api/stores/orders?page=0&size=20', STT],
    ['門市財務摘要', '/api/stores/finance/summary', STT],
    ['門市評分統計', '/api/stores/reports/rating-stats', STT],
    ['品牌分店清單', '/api/brand/stores', BT],
    ['品牌飲品清單', '/api/brand/products', BT],
    ['品牌分類清單', '/api/brand/categories', BT],
    ['品牌規格設定', '/api/brand/specs', BT],
    ['品牌配料設定', '/api/brand/toppings', BT],
  ]) {
    const r = await req('GET', path, { token: tok, raw: true });
    check(name, r.status === 200 && r.json && r.json.code === '200',
      `HTTP ${r.status} code=${r.json && r.json.code}`);
  }

  // 門市訂單列表必須是分頁格式
  const pageResp = D(await req('GET', '/api/stores/orders?page=0&size=5', { token: STT }));
  check('訂單列表回傳分頁欄位（total/totalPages/hasNext）',
    pageResp && 'total' in pageResp && 'totalPages' in pageResp && 'hasNext' in pageResp);
  check('size 有生效（最多 5 筆）', pageResp && Array.isArray(pageResp.orders) && pageResp.orders.length <= 5,
    `實際 ${pageResp && pageResp.orders && pageResp.orders.length} 筆`);
  const over = D(await req('GET', '/api/stores/orders?page=0&size=9999', { token: STT }));
  check('size 上限被夾到 200', over && over.size <= 200, `size=${over && over.size}`);

  console.log(`\n${'='.repeat(52)}`);
  console.log(`  通過 ${pass} 項，失敗 ${fail} 項`);
  if (fail) console.log(`  失敗項目：\n   - ${fails.join('\n   - ')}`);
  console.log('='.repeat(52));
  process.exit(fail ? 1 : 0);
})();
