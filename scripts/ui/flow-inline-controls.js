/**
 * 流程：內嵌事件控制項（N-14）
 *
 * N-14 把 onclick／onerror 裡插入的使用者可控值搬到 data-* 屬性，由事件函式以 this.dataset 讀取。
 * 這條流程逐一點下改過的控制項，確認「正確的值」送到「正確的函式」，並檢查結果：
 * localStorage 的購物車、送出的 API 請求內容（數字型別）、DOM、網址。
 *
 * 後端不執行：/api/ 請求全部由 page.route 攔截，回固定的假資料（格式同後端的 {code, msg, data}），
 * 所以本機與 CI 的結果一致，也不依賴資料庫裡有哪些店家。頁面跳轉的目標（store.html、
 * order_details.html）改回空白頁，只驗證目標網址。團購頁的 WebSocket 直接擋掉，這裡不測即時推播。
 *
 * 「不得把 ${…} 插進事件屬性」的寫入端規則由 InlineHandlerInterpolationGuardTest 以文字掃描守住；
 * 這裡守的是行為：點了之後狀態對不對。
 *
 * 除錯用：FLOW_ONLY=關鍵字 只跑名稱含該關鍵字的區段（例如 FLOW_ONLY=團購）。
 */
const { FE, launch, newSession, projectErrors } = require('./harness');
const report = require('./report');

const VIEWPORT = { width: 1280, height: 900 };
const USER_ID = 9001;
const BLANK_PAGE = '<!doctype html><meta charset="utf-8"><title>stub</title>';
// 含引號與角括號的地址：確認 data-* 的轉義往返不走樣（N-12、N-14 的回歸點）
const ADDRESS_WEIRD = '台北市"大安區\'1號 & <測試>';
// 必定 404 的圖片，用來觸發 onerror
const IMG_404 = 'images/does-not-exist.jpg';
const GROUP_TOKEN = 'tok-group-0001';
const GROUP_ITEM_ID = 9101;

const cartItem = (storeId, storeName, productId, productName, price, quantity) => ({
  productId, productName, price, quantity, customization: [], imageUrl: '',
  storeId, storeName, sizeSnapshot: 'M', sugarSnapshot: '', iceSnapshot: '',
  toppingNames: [], toppingIds: [], brandSpecId: null, categoryId: null, couponId: null, discountAmount: 0,
});
const CART_101 = {
  storeId: 101, storeName: '甲店', storeLogoUrl: '',
  items: [cartItem(101, '甲店', 1, '珍珠奶茶', 50, 2), cartItem(101, '甲店', 2, '紅茶', 30, 1)],
};
const CART_202 = {
  storeId: 202, storeName: '乙店', storeLogoUrl: '',
  items: [cartItem(202, '乙店', 3, '拿鐵', 60, 1)],
};

const USER = { id: USER_ID, name: '測試顧客', username: 'tester', phone: '0912000000', email: 'tester@example.com', balance: 500, picUrl: null };
const LIST_STORES = [
  { id: 1001, name: '列表甲店', storeName: '列表甲店', brandName: '測試品牌', coverUrl: null, rating: 4.5, openingHours: null },
  { id: 1002, name: '列表乙店', storeName: '列表乙店', brandName: '測試品牌', coverUrl: null, rating: 4.1, openingHours: null },
];
const SEARCH_STORES = [
  { storeId: 3001, storeName: '搜尋甲店', brandName: '測試品牌', coverUrl: null, status: 'active', isAcceptingOrders: true, avgRating: 4.5, reviewCount: 0, isFavorite: false, openingHours: null, distance: null },
  { storeId: 3002, storeName: '搜尋乙店', brandName: '測試品牌', coverUrl: null, status: 'active', isAcceptingOrders: false, avgRating: 4.0, reviewCount: 0, isFavorite: false, openingHours: null, distance: null },
];
const INDEX_STORES = [
  { id: 4001, storeName: '首頁甲店', brandName: '測試品牌', coverUrl: IMG_404, rating: 4.2, openingHours: null, latitude: 25.03, longitude: 121.54, isFavorite: false },
];
const ACTIVE_ORDERS = [{ orderId: 5001, status: 'SUBMITTED', type: 'SOLO', storeName: '首頁甲店', finalAmount: 100, items: [] }];
const RECENT_ORDERS = [{ orderId: 5002, status: 'COMPLETED', type: 'SOLO', storeName: '首頁甲店', finalAmount: 80, orderTime: '2026-10-20T12:00:00' }];
const CARD_1 = { id: 1700000000001, type: 'VISA', last4: '1111', name: 'A TESTER', expiry: '12/30', isDefault: true };
const CARD_2 = { id: 1700000000002, type: 'MASTER', last4: '2222', name: 'B TESTER', expiry: '11/29', isDefault: false };
// 結帳頁的優惠券：不限品牌與品項，所有購物車品項都適用
const CHECKOUT_COUPON = { id: 7001, status: 'unused', brandName: '測試品牌', productName: '珍珠奶茶', discountAmount: 10, brandId: null, productId: null };
// 團購頁的優惠券：頁面讀 couponId（使用者券的 id）
const GROUP_COUPON = { id: 7001, couponId: 7001, status: 'unused', brandName: '測試品牌', productName: '珍珠奶茶', productId: 301, brandId: null, discountAmount: 10 };

// ─── 共用工具 ──────────────────────────────────────────────────────────────

/** 已登入使用者的瀏覽器狀態。刻意不設 JOIN_TOKEN：購物車維持本地模式（cart.js 的 isLoggedIn）。 */
const userStorage = (extra = {}) => ({
  JOIN_USER_ID: String(USER_ID),
  userLat: '25.0336',
  userLng: '121.5435',
  userLocationName: '台北市大安區',
  ...extra,
});
const seedCarts = (carts) => ({ join_multi_carts: JSON.stringify(carts) });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(pred, timeout = 8000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    if (await pred()) return true;
    await sleep(100);
  }
  return false;
}

const readCarts = (page) => page.evaluate(() => JSON.parse(localStorage.getItem('join_multi_carts') || '{}'));
const cartItems = (carts, storeId) => ((carts[String(storeId)] || {}).items || []);

/**
 * 離開目前頁面前先等請求靜止。頁面上還在飛的 fetch 會被導覽中止，Chromium 會記成「Failed to fetch」，
 * 那是測試造成的時機問題，不是專案的錯，所以要在導覽前等它們結束。
 */
async function settle(page) {
  await page.waitForTimeout(450); // 讓 400ms 內排程的重新載入先發出
  await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {});
}

/** 所有頁面跳轉都經過這裡：先 settle，再前往。 */
async function visit(page, url) {
  await settle(page);
  return page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
}

/** 點擊；失敗時說明是哪個控制項，不讓 Playwright 的逾時訊息只剩 locator 名稱。 */
async function act(label, locator, options = {}) {
  try {
    await locator.click({ timeout: 8000, ...options });
  } catch (e) {
    throw new Error(`${label}：找不到或無法點擊（${String(e.message).split('\n')[0]}）`);
  }
}

/** 點卡片的正中央（圖片或文字，事件會冒泡到卡片），避開左上角的收藏按鈕。 */
async function clickCard(label, locator) {
  const el = locator.first();
  await el.scrollIntoViewIfNeeded().catch(() => {});
  const box = await el.boundingBox({ timeout: 8000 }).catch(() => null);
  if (!box) throw new Error(`${label}：卡片不可見或不存在`);
  await act(label, el, { position: { x: Math.floor(box.width / 2), y: Math.floor(box.height / 2) } });
}

/**
 * 攔截所有 /api/ 請求。rules 每條為 { method, path: RegExp, reply(call) }，reply 的回傳值即 data；
 * 沒有符合的規則回空陣列，避免請求打到不存在的後端。所有請求都記在回傳的 calls 陣列裡供斷言。
 * path 比對的是 pathname＋query，例如 /api/x/1?userId=9001。
 */
async function mockApi(page, rules) {
  const calls = [];
  await page.route(/\/api\//, async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    if (req.method() === 'OPTIONS') {
      return route.fulfill({
        status: 204,
        headers: {
          'access-control-allow-origin': '*',
          'access-control-allow-methods': 'GET,POST,PUT,DELETE,OPTIONS',
          'access-control-allow-headers': 'Authorization, Content-Type',
        },
      });
    }
    let body = null;
    try { body = req.postData() ? JSON.parse(req.postData()) : null; } catch (e) { body = req.postData(); }
    const call = { method: req.method(), path: url.pathname, query: url.search, body };
    calls.push(call);
    const rule = rules.find((r) => r.method === req.method() && r.path.test(url.pathname + url.search));
    const data = rule ? await rule.reply(call) : [];
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: '200', msg: 'success', data }),
    });
  });
  return calls;
}

/** 頁面跳轉的目標改回空白頁。只攔截文件導覽，其他資源照常載入。 */
async function stubPages(page, files) {
  const re = new RegExp('/(' + files.join('|') + ')\\.html');
  await page.route(re, (route) => {
    if (route.request().resourceType() !== 'document') return route.continue();
    return route.fulfill({ status: 200, contentType: 'text/html', body: BLANK_PAGE });
  });
}

const isToggle = (c) => c.method === 'POST' && c.path.endsWith('/api/user-favorites/toggle');

/** 目前區段開過的所有分頁；區段中斷時用來附上前端錯誤。 */
let sessions = [];
async function open(browser, storage, label) {
  const S = await newSession(browser, storage, { viewport: VIEWPORT, label });
  sessions.push(S);
  return S;
}

function finish(S, ok, name) {
  const errs = projectErrors(S.errors);
  ok(errs.length === 0, `${name}：頁面有前端錯誤`, errs.join('\n'));
}

// ─── 各頁區段 ──────────────────────────────────────────────────────────────

/** 店家列表：卡片導覽、收藏（愛心不得觸發導覽） */
async function sectionStoreList(browser, ok, name) {
  const S = await open(browser, userStorage(), 'list');
  await stubPages(S.page, ['store', 'order_details']);
  const calls = await mockApi(S.page, [
    { method: 'GET', path: /\/api\/stores\/nearby\?/, reply: () => ({ stores: LIST_STORES }) },
    { method: 'POST', path: /\/api\/user-favorites\/toggle$/, reply: () => ({ status: 'added' }) },
  ]);
  const pageUrl = FE + '/Customer/store_list.html';

  await visit(S.page, pageUrl);
  await settle(S.page);
  await clickCard('店家列表：甲店卡片', S.page.locator('div[data-store-id="1001"]'));
  ok(await until(() => S.page.url().includes('store.html?id=1001')),
    `${name}：點卡片應前往 store.html?id=1001`, `實際網址：${S.page.url()}`);

  await visit(S.page, pageUrl);
  const heart = S.page.locator('div[data-store-id="1002"] button[data-store-id="1002"]').first();
  await act('店家列表：乙店收藏愛心', heart);
  const posted = await until(() => calls.some(isToggle));
  const post = calls.find(isToggle);
  ok(posted && post && post.body && post.body.storeId === 1002 && post.body.userId === USER_ID,
    `${name}：收藏請求的 storeId 與 userId 應為數字 1002 與 ${USER_ID}`,
    `實際：${JSON.stringify(post && post.body)}`);
  // 愛心要等回應回來才變色（toggleStoreFavorite 先 await 才 classList.add），所以要等，不能只讀一次
  ok(await until(() => heart.evaluate((b) => b.classList.contains('text-brand-orange'))),
    `${name}：收藏成功後愛心應變色`);
  await sleep(700); // 否定式檢查：給「不該發生」的導覽一個發生的機會（與搜尋區段的守衛檢查相同）
  ok(S.page.url().includes('store_list.html'),
    `${name}：點愛心不應觸發卡片導覽`, `實際網址：${S.page.url()}`);

  finish(S, ok, name);
  await S.ctx.close();
}

/** 搜尋結果：暫停接單的卡片不導覽（守衛）、可接單的卡片導覽、收藏 */
async function sectionSearch(browser, ok, name) {
  const S = await open(browser, userStorage(), 'search');
  await stubPages(S.page, ['store']);
  const calls = await mockApi(S.page, [
    { method: 'GET', path: /\/api\/stores\/search\?/, reply: () => ({ stores: SEARCH_STORES, page: 1, size: 12, total: 2, totalPages: 1, hasNext: false }) },
    { method: 'POST', path: /\/api\/user-favorites\/toggle$/, reply: () => ({ status: 'added' }) },
  ]);
  const pageUrl = FE + '/Customer/search_result.html?q=' + encodeURIComponent('搜尋');

  await visit(S.page, pageUrl);
  const unavailable = S.page.locator('#store-grid > div[data-store-id="3002"]').first();
  await unavailable.waitFor({ timeout: 15000 }).catch(() => {});
  ok((await unavailable.getAttribute('data-unavailable')) === '1',
    `${name}：暫停接單的卡片應標示 data-unavailable="1"`);
  await clickCard('搜尋結果：暫停接單卡片', unavailable);
  await sleep(700);
  ok(S.page.url().includes('search_result.html'),
    `${name}：暫停接單的卡片點擊不應導覽（守衛）`, `實際網址：${S.page.url()}`);

  await settle(S.page);
  await clickCard('搜尋結果：可接單卡片', S.page.locator('#store-grid > div[data-store-id="3001"]'));
  ok(await until(() => S.page.url().includes('store.html?id=3001')),
    `${name}：可接單的卡片應前往 store.html?id=3001`, `實際網址：${S.page.url()}`);

  await visit(S.page, pageUrl);
  const heart = S.page.locator('#store-grid > div[data-store-id="3001"] button[data-store-id="3001"]').first();
  await act('搜尋結果：甲店收藏愛心', heart);
  const posted = await until(() => calls.some(isToggle));
  const post = calls.find(isToggle);
  ok(posted && post && post.body && post.body.storeId === 3001,
    `${name}：收藏請求的 storeId 應為數字 3001`, `實際：${JSON.stringify(post && post.body)}`);
  ok(await until(() => heart.evaluate((b) => b.classList.contains('text-brand-orange'))),
    `${name}：收藏成功後愛心應變色`);

  finish(S, ok, name);
  await S.ctx.close();
}

/** 首頁：封面 onerror 退回占位圖並套用色相、店家卡導覽、收藏、進行中與近期訂單的導覽 */
async function sectionIndex(browser, ok, name) {
  const S = await open(browser, userStorage(), 'index');
  await stubPages(S.page, ['store', 'order_details']);
  const calls = await mockApi(S.page, [
    { method: 'GET', path: /\/api\/stores\/nearby\?/, reply: () => ({ stores: INDEX_STORES }) },
    { method: 'GET', path: /\/api\/orders\/user\/\d+\/active$/, reply: () => ACTIVE_ORDERS },
    { method: 'GET', path: /\/api\/orders\/user\/\d+\/recent-cards$/, reply: () => RECENT_ORDERS },
    { method: 'POST', path: /\/api\/user-favorites\/toggle$/, reply: () => ({ status: 'added' }) },
  ]);
  const pageUrl = FE + '/Customer/index.html';

  await visit(S.page, pageUrl);
  const img = S.page.locator('div[data-store-id="4001"] img').first();
  await img.waitFor({ timeout: 15000 }).catch(() => {});
  await img.scrollIntoViewIfNeeded().catch(() => {});
  const swapped = await until(async () => (await img.getAttribute('src')) === 'images/store-placeholder.svg', 10000);
  const state = await img.evaluate((el) => ({ hue: el.dataset.hue, filter: el.style.filter }));
  ok(swapped, `${name}：封面 404 時應退回內建占位圖（onerror）`, `實際 src：${await img.getAttribute('src')}`);
  ok(state.hue !== undefined && state.hue !== '' && state.filter === `hue-rotate(${state.hue}deg)`,
    `${name}：占位圖的色相應由 this.dataset.hue 套用`, `hue=${state.hue} filter=${state.filter}`);

  await settle(S.page);
  await clickCard('首頁：店家卡片', S.page.locator('div[data-store-id="4001"]'));
  ok(await until(() => S.page.url().includes('store.html?id=4001')),
    `${name}：店家卡應前往 store.html?id=4001`, `實際網址：${S.page.url()}`);

  await visit(S.page, pageUrl);
  const heart = S.page.locator('div[data-store-id="4001"] button[data-store-id="4001"]').first();
  await act('首頁：店家收藏愛心', heart);
  const posted = await until(() => calls.some(isToggle));
  const post = calls.find(isToggle);
  ok(posted && post && post.body && post.body.storeId === 4001,
    `${name}：首頁收藏請求的 storeId 應為數字 4001`, `實際：${JSON.stringify(post && post.body)}`);

  await visit(S.page, pageUrl);
  await settle(S.page);
  await clickCard('首頁：進行中訂單卡片', S.page.locator('div[data-order-id="5001"]'));
  ok(await until(() => S.page.url().includes('order_details.html?id=5001')),
    `${name}：進行中訂單卡應前往 order_details.html?id=5001`, `實際網址：${S.page.url()}`);

  // 近期訂單只在切到「近期」分頁時才載入（index.html 的 loadRecentOrders）
  await visit(S.page, pageUrl);
  await act('首頁：「近期」分頁', S.page.locator('[data-section="recent"]').first());
  await settle(S.page);
  await clickCard('首頁：近期訂單卡片', S.page.locator('div[data-order-id="5002"]'));
  ok(await until(() => S.page.url().includes('order_details.html?id=5002')),
    `${name}：近期訂單卡應前往 order_details.html?id=5002`, `實際網址：${S.page.url()}`);

  finish(S, ok, name);
  await S.ctx.close();
}

/** 導覽列購物車下拉的「移除」：只移除被點的那一項（orders.html 會載入 cart.js 與導覽） */
async function sectionNavDropdown(browser, ok, name) {
  const S = await open(browser, userStorage(seedCarts({ 101: CART_101 })), 'nav');
  await mockApi(S.page, []);
  await visit(S.page, FE + '/Customer/orders.html');
  const remove = S.page.locator('#cart-items-container button[data-index="1"]').first();
  const shown = await until(async () => (await remove.count()) > 0, 10000);
  ok(shown, `${name}：導覽列的購物車下拉應列出第 2 項的「移除」按鈕`);
  if (shown) {
    await remove.dispatchEvent('click');
    const names = cartItems(await readCarts(S.page), 101).map((i) => i.productName);
    ok(JSON.stringify(names) === JSON.stringify(['珍珠奶茶']),
      `${name}：移除第 2 項後應只剩「珍珠奶茶」`, `實際：${JSON.stringify(names)}`);
  }
  finish(S, ok, name);
  await S.ctx.close();
}

/** 購物車頁（本地模式）：+／−、移除、切換店家卡片 */
async function sectionCartDetails(browser, ok, name) {
  const S = await open(browser, userStorage(seedCarts({ 101: CART_101, 202: CART_202 })), 'cart');
  await mockApi(S.page, []);
  const page = S.page;
  const go = () => visit(page, FE + '/Customer/cart_details.html?id=101');
  const qtyOf = async (idx) => (cartItems(await readCarts(page), 101)[idx] || {}).quantity;

  await go();
  await act('購物車：珍珠奶茶 +', page.locator('button[data-sid="101"][data-local-idx="0"][data-new-qty="3"]').first());
  ok(await until(async () => (await qtyOf(0)) === 3),
    `${name}：+ 後珍珠奶茶的數量應為 3`, `實際：${await qtyOf(0)}`);

  await act('購物車：珍珠奶茶 −', page.locator('button[data-sid="101"][data-local-idx="0"][data-new-qty="2"]').first());
  ok(await until(async () => (await qtyOf(0)) === 2),
    `${name}：− 後珍珠奶茶的數量應為 2`, `實際：${await qtyOf(0)}`);

  await act('購物車：紅茶移除', page.locator('button[data-sid="101"][data-local-idx="1"][onclick*="removeCartItem"]').first());
  ok(await until(async () => cartItems(await readCarts(page), 101).length === 1),
    `${name}：移除紅茶後購物車應剩 1 項`, `實際：${JSON.stringify(cartItems(await readCarts(page), 101).map((i) => i.productName))}`);

  await go();
  await settle(page);
  await clickCard('購物車：乙店卡片', page.locator('div[onclick*="switchCartById"][data-store-id="202"]'));
  ok(await until(async () => new URL(page.url()).searchParams.get('id') === '202'),
    `${name}：切換到乙店的卡片後網址的 id 應為 202`, `實際網址：${page.url()}`);

  finish(S, ok, name);
  await S.ctx.close();
}

/** 結帳頁：+／−、編輯（開啟正確品項的彈窗）、移除（確認後）、選券 */
async function sectionCheckout(browser, ok, name) {
  const S = await open(browser, userStorage(seedCarts({ 101: CART_101 })), 'checkout');
  await mockApi(S.page, [
    { method: 'GET', path: /\/api\/users\/me$/, reply: () => USER },
    { method: 'GET', path: new RegExp(`/api/users/${USER_ID}$`), reply: () => USER },
    { method: 'GET', path: /\/api\/stores\/101$/, reply: () => ({ id: 101, name: '甲店', storeName: '甲店', brandId: 1 }) },
    { method: 'GET', path: /\/customization\/v2/, reply: () => ({}) },
    { method: 'GET', path: /\/api\/coupons\/my$/, reply: () => [CHECKOUT_COUPON] },
  ]);
  const page = S.page;
  const go = () => visit(page, FE + '/Customer/checkout.html?storeId=101');
  const qtyOf = async (idx) => (cartItems(await readCarts(page), 101)[idx] || {}).quantity;
  // 編輯與移除按鈕藏在 group-hover 的浮層裡，要先把滑鼠移到該品項列上
  const hoverRow = async (label, idx) => {
    await page.locator('[class~="group/item"]').nth(idx).hover({ timeout: 8000 }).catch((e) => {
      throw new Error(`${label}：品項列無法 hover（${String(e.message).split('\n')[0]}）`);
    });
  };

  await go();
  await act('結帳：珍珠奶茶 +', page.locator('button[onclick*="updateItemQty"][onclick*=", 1)"][data-index="0"]').first());
  ok(await until(async () => (await qtyOf(0)) === 3),
    `${name}：+ 後珍珠奶茶的數量應為 3`, `實際：${await qtyOf(0)}`);

  await act('結帳：珍珠奶茶 −', page.locator('button[onclick*="updateItemQty"][onclick*=", -1)"][data-index="0"]').first());
  ok(await until(async () => (await qtyOf(0)) === 2),
    `${name}：− 後珍珠奶茶的數量應為 2`, `實際：${await qtyOf(0)}`);

  await go();
  await hoverRow('結帳：紅茶列', 1);
  await act('結帳：紅茶編輯', page.locator('[class~="group/item"]').nth(1).locator('button[onclick*="openEditModal"]').first());
  ok(await until(async () => (await page.locator('#modal-product-title').innerText().catch(() => '')) === '紅茶'),
    `${name}：編輯第 2 項應開啟紅茶的彈窗`, `實際標題：${await page.locator('#modal-product-title').innerText().catch(() => '（取不到）')}`);

  await go();
  let dialogMessage = null;
  page.once('dialog', (d) => { dialogMessage = d.message(); d.accept(); });
  await hoverRow('結帳：紅茶列', 1);
  // 導覽列的購物車下拉也有一顆 removeItem 按鈕（隱藏、排在前面），所以要限定在紅茶那一列裡找
  await act('結帳：紅茶移除', page.locator('[class~="group/item"]').nth(1).locator('button[onclick*="removeItem"]').first());
  const remainingNames = async () => cartItems(await readCarts(page), 101).map((i) => i.productName);
  ok(await until(async () => JSON.stringify(await remainingNames()) === JSON.stringify(['珍珠奶茶'])),
    `${name}：確認移除第 2 項後購物車應只剩「珍珠奶茶」`, `實際：${JSON.stringify(await remainingNames())}`);
  ok(dialogMessage && dialogMessage.includes('確定要刪除'),
    `${name}：移除前應出現確認視窗`, `實際訊息：${dialogMessage}`);

  await go();
  await act('結帳：開啟優惠券', page.locator('#open-coupon-modal-btn').first());
  await clickCard('結帳：第 1 張優惠券', page.locator('#coupon-list-container [data-idx="0"]'));
  ok(await until(async () => (await page.locator('#selected-coupon-name').innerText().catch(() => '')) === '測試品牌 - 珍珠奶茶'),
    `${name}：選第 1 張券後應顯示該券的品牌與品項`, `實際：${await page.locator('#selected-coupon-name').innerText().catch(() => '（取不到）')}`);

  finish(S, ok, name);
  await S.ctx.close();
}

/** 團購頁的群組資料：團長身分（initiatorId 即本人），品項狀態 OPEN，可編輯。 */
function groupFixture(state) {
  return {
    groupOrder: {
      shareToken: GROUP_TOKEN, status: 'OPEN', initiatorId: USER_ID, initiatorName: '測試顧客',
      storeId: 101, storeName: '甲店', viewerIsMember: true,
    },
    items: state.removed ? [] : [{
      id: GROUP_ITEM_ID, idList: [GROUP_ITEM_ID], productId: 301, productName: '珍珠奶茶',
      storeId: 101, storeName: '甲店', userId: USER_ID, userName: '測試顧客',
      qty: state.qty, unitPriceSnapshot: 50, finalPrice: 50 * state.qty, totalGroupPrice: 50 * state.qty,
      discountAmount: 0, paymentStatus: 'UNPAID', imageUrl: '',
    }],
  };
}

/**
 * 團購頁（group_order.html，團長身分）：數量 +／−、編輯彈窗、移除（自訂確認窗）、
 * 品項優惠券（「不使用」送 couponId=null；選第 1 張送數字 7001）。
 * 以 ?token= 進入。頁面會先連 WebSocket；這裡直接擋掉，因為頁面在確認成員身分之前不會訂閱。
 */
async function sectionGroupOrder(browser, ok, name) {
  const S = await open(browser, userStorage(), 'group');
  const page = S.page;
  await page.route(/\/ws-cart/, (route) => route.abort());
  const state = { qty: 2, removed: false };
  const itemPath = new RegExp(`/api/group-orders/${GROUP_TOKEN}/items/${GROUP_ITEM_ID}(\\?.*)?$`);
  const calls = await mockApi(page, [
    { method: 'GET', path: new RegExp(`/api/group-orders/join/${GROUP_TOKEN}$`), reply: () => groupFixture(state) },
    { method: 'GET', path: /\/api\/stores\/101$/, reply: () => ({ storeName: '甲店' }) },
    { method: 'GET', path: /\/api\/products\/301\/customization\/v2/, reply: () => ({ specs: [], toppings: [], basePrice: 50, maxToppings: 3 }) },
    { method: 'GET', path: /\/api\/coupons\/my$/, reply: () => [GROUP_COUPON] },
    { method: 'PUT', path: itemPath, reply: (c) => { state.qty = c.body.qty; return {}; } },
    { method: 'DELETE', path: itemPath, reply: () => { state.removed = true; return {}; } },
    { method: 'POST', path: new RegExp(`/api/group-orders/${GROUP_TOKEN}/items/${GROUP_ITEM_ID}/apply-coupon$`), reply: () => ({}) },
  ]);
  const go = () => visit(page, FE + '/Customer/group_order.html?token=' + GROUP_TOKEN);
  // 選擇器只用函式名稱與「固定的參數字面值」（+1／−1），不用轉換後的參數文字：
  // 否則改壞參數的破壞會變成「找不到控制項」，而不是行為檢查失敗。
  const itemBtn = (fn) => page.locator(`button[data-item-id="${GROUP_ITEM_ID}"][onclick*="${fn}"]`).first();
  const qtyBtn = (delta) => page.locator(`button[data-item-id="${GROUP_ITEM_ID}"][onclick*="updateItemQty"][onclick*=", ${delta})"]`).first();
  const hasQty = async (qty) => (await page.locator(`button[data-item-id="${GROUP_ITEM_ID}"][data-qty="${qty}"]`).count()) > 0;
  // 編輯／刪除／優惠券按鈕藏在 group-hover/item 的浮層裡，要先把滑鼠移到品項列上（不能靠殘留的滑鼠位置）
  const hoverItem = () => page.locator('[class~="group/item"]').first().hover({ timeout: 8000 });

  // 1. 「+」：data-qty 經 Number() 轉成數字後相加。送出 qty=3，重新載入後顯示 3
  await go();
  await act('團購：數量 +', qtyBtn(1));
  ok(await until(() => calls.some((c) => c.method === 'PUT' && c.body && c.body.qty === 3)),
    `${name}：+ 應送出數量 3（數字）`,
    `實際 PUT：${JSON.stringify(calls.filter((c) => c.method === 'PUT').map((c) => c.body))}`);
  ok(await until(() => hasQty(3)), `${name}：+ 後重新載入應顯示數量 3`);

  // 2. 「−」：送出 qty=2，重新載入後顯示 2
  await act('團購：數量 −', qtyBtn(-1));
  ok(await until(() => calls.some((c) => c.method === 'PUT' && c.body && c.body.qty === 2)),
    `${name}：− 應送出數量 2（數字）`,
    `實際 PUT：${JSON.stringify(calls.filter((c) => c.method === 'PUT').map((c) => c.body))}`);
  ok(await until(() => hasQty(2)), `${name}：− 後重新載入應顯示數量 2`);

  // 3. 修改：彈窗以 data-item-id（字串）在 idList 中找到品項，標題應為該品項
  await go();
  await hoverItem();
  await act('團購：修改', itemBtn('openEditModal'));
  ok(await until(async () => (await page.locator('#modal-product-title').innerText().catch(() => '')) === '珍珠奶茶'),
    `${name}：修改彈窗的標題應為「珍珠奶茶」`,
    `實際：${await page.locator('#modal-product-title').innerText().catch(() => '（取不到）')}`);

  // 4. 刪除整項：先過自訂確認窗（#confirm-ok-btn），送出 DELETE，重新載入後不再有該品項
  await go();
  await hoverItem();
  await act('團購：刪除整項', itemBtn('removeItem'));
  await act('團購：確認刪除', page.locator('#confirm-ok-btn').first());
  ok(await until(() => calls.some((c) => c.method === 'DELETE' && c.path.endsWith(`/items/${GROUP_ITEM_ID}`))),
    `${name}：確認刪除後應送出 DELETE /items/${GROUP_ITEM_ID}`,
    `實際：${JSON.stringify(calls.filter((c) => c.method === 'DELETE').map((c) => c.path))}`);
  ok(await until(async () => (await page.locator(`button[data-item-id="${GROUP_ITEM_ID}"]`).count()) === 0),
    `${name}：刪除後重新載入，該品項不應再出現`);
  // 還原假後端的品項（之後的優惠券步驟需要它）
  state.removed = false;

  // 5. 品項優惠券：「不使用優惠券」送出 couponId=null；選第 1 張送出 couponId=7001（數字）
  await go();
  await hoverItem();
  await act('團購：品項優惠券', page.locator(`button[data-item-id="${GROUP_ITEM_ID}"][onclick*="openItemCouponModal"]`).first());
  await act('團購：不使用優惠券', page.locator('[onclick="goSelectCoupon(-1)"]').first());
  ok(await until(() => calls.some((c) => c.method === 'POST' && c.path.endsWith('/apply-coupon') && c.body && c.body.couponId === null)),
    `${name}：「不使用優惠券」應送出 couponId=null`,
    `實際：${JSON.stringify(calls.filter((c) => c.path.endsWith('/apply-coupon')).map((c) => c.body))}`);

  await go();
  await hoverItem();
  await act('團購：品項優惠券（再次開啟）', page.locator(`button[data-item-id="${GROUP_ITEM_ID}"][onclick*="openItemCouponModal"]`).first());
  await act('團購：選第 1 張優惠券', page.locator('[onclick*="goSelectCoupon"][data-idx="0"]').first());
  ok(await until(() => calls.some((c) => c.method === 'POST' && c.path.endsWith('/apply-coupon') && c.body && c.body.couponId === 7001)),
    `${name}：選第 1 張優惠券應送出 couponId=7001（數字）`,
    `實際：${JSON.stringify(calls.filter((c) => c.path.endsWith('/apply-coupon')).map((c) => c.body))}`);

  finish(S, ok, name);
  await S.ctx.close();
}

/** 訂單列表：封面 onerror 退回占位圖、評星連結帶上正確的訂單 id */
async function sectionOrders(browser, ok, name) {
  const DONE_ORDER = {
    orderId: 6001, status: 'COMPLETED', type: 'SOLO', storeName: '甲店', storeImageUrl: IMG_404,
    finalAmount: 85, itemsSummary: '珍珠奶茶 x2', ratingSubmitted: false, orderTime: '2026-10-20T12:00:00',
  };
  const S = await open(browser, userStorage(), 'orders');
  await stubPages(S.page, ['order_details']);
  await mockApi(S.page, [
    { method: 'GET', path: /\/api\/orders\/user\/\d+\/cards/, reply: () => ({ content: [DONE_ORDER], isLast: true, last: true }) },
  ]);
  await visit(S.page, FE + '/Customer/orders.html');

  const rate = S.page.locator('button[data-order-id="6001"]').first();
  await rate.waitFor({ timeout: 15000 }).catch(() => {});
  const img = S.page.locator('img[data-fallback]').first();
  const swapped = await until(async () => (await img.getAttribute('src').catch(() => null)) === 'images/store-placeholder.svg', 10000);
  ok(swapped, `${name}：訂單封面 404 時應退回內建占位圖（onerror）`, `實際 src：${await img.getAttribute('src').catch(() => '（取不到）')}`);

  await settle(S.page);
  await act('訂單：評星', rate);
  ok(await until(() => S.page.url().includes('order_details.html?id=6001&rate=1')),
    `${name}：評星應前往 order_details.html?id=6001&rate=1`, `實際網址：${S.page.url()}`);

  finish(S, ok, name);
  await S.ctx.close();
}

/** 個人資料：預設卡、刪卡（嚴格比對數字 id）、地址編輯（data-* 往返）、刪除正在編輯的地址（數字 id 比對）、收藏與收藏卡導覽 */
async function sectionProfile(browser, ok, name) {
  const addresses = [
    { id: 8001, label: '公司', address: ADDRESS_WEIRD },
    { id: 8002, label: '學校', address: '新北市板橋區測試路2號' },
  ];
  const favorites = [
    { store: { id: 2001, storeName: '收藏甲店', brandName: '測試品牌', coverUrl: null, avgRating: 4.2 } },
    { store: { id: 2002, storeName: '收藏乙店', brandName: '測試品牌', coverUrl: null, avgRating: 4.0 } },
  ];
  const S = await open(browser, userStorage({ [`paymentCards_${USER_ID}`]: JSON.stringify([CARD_1, CARD_2]) }), 'profile');
  await stubPages(S.page, ['store']);
  const calls = await mockApi(S.page, [
    { method: 'GET', path: new RegExp(`/api/users/${USER_ID}$`), reply: () => USER },
    { method: 'GET', path: new RegExp(`/api/users/${USER_ID}/address$`), reply: () => addresses },
    { method: 'DELETE', path: new RegExp(`/api/users/${USER_ID}/addresses/(\\d+)$`), reply: (c) => {
        const id = Number(c.path.split('/').pop());
        const i = addresses.findIndex((a) => a.id === id);
        if (i >= 0) addresses.splice(i, 1);
        return {};
      } },
    { method: 'GET', path: new RegExp(`/api/user-favorites/user/${USER_ID}$`), reply: () => favorites },
    { method: 'POST', path: /\/api\/user-favorites\/toggle$/, reply: (c) => {
        const id = Number(c.body.storeId);
        const i = favorites.findIndex((f) => f.store.id === id);
        if (i >= 0) { favorites.splice(i, 1); return { status: 'removed' }; }
        return { status: 'added' };
      } },
    { method: 'GET', path: /\/api\/wallet\/transactions/, reply: () => ({ transactions: [] }) },
  ]);
  const page = S.page;
  // 各區段由 ?section= 直接開啟（profile.html 的 switchSection 會載入該區段的資料）
  const go = (section) => visit(page, FE + '/Customer/profile.html?section=' + section);
  const cards = async () => JSON.parse(await page.evaluate((k) => localStorage.getItem(k) || '[]', `paymentCards_${USER_ID}`));

  // 1. 設為預設：嚴格比對數字 id，應只改動被點的那張
  await go('payment');
  await act('個人資料：第 2 張設為預設', page.locator(`button[onclick*="setDefaultCard"][data-id="${CARD_2.id}"]`).first());
  ok(await until(async () => {
    const c = await cards();
    return c.find((x) => x.id === CARD_2.id)?.isDefault === true && c.find((x) => x.id === CARD_1.id)?.isDefault === false;
  }), `${name}：設為預設後，第 2 張應為預設、第 1 張不是`, `實際：${JSON.stringify(await cards())}`);

  // 2. 刪除卡：現在第 1 張不是預設，可以刪除
  await go('payment');
  await act('個人資料：第 1 張刪除', page.locator(`button[onclick*="deleteCard"][data-id="${CARD_1.id}"]`).first());
  ok(await until(async () => (await cards()).length === 1 && (await cards())[0].id === CARD_2.id),
    `${name}：刪除第 1 張後應只剩第 2 張`, `實際：${JSON.stringify((await cards()).map((x) => x.id))}`);

  // 3. 地址編輯：data-* 往返後，表單應帶回原字串（含引號與角括號）
  await go('address');
  await act('個人資料：地址 8001 編輯', page.locator('button[onclick*="editExtraAddressFromEl"][data-id="8001"]').first());
  ok(await until(async () => (await page.locator('#addr-street').inputValue()) === ADDRESS_WEIRD),
    `${name}：編輯地址後表單應帶回原字串（data-* 往返）`, `實際：${await page.locator('#addr-street').inputValue()}`);

  // 4. 刪除「正在編輯的」地址 8001：表單應重設（editingAddressId 與 addressId 都是數字，嚴格比對才會命中）
  await act('個人資料：地址 8001 刪除（正在編輯）', page.locator('button[onclick*="deleteExtraAddress"][data-id="8001"]').first());
  ok(await until(() => calls.some((c) => c.method === 'DELETE' && c.path.endsWith('/addresses/8001'))),
    `${name}：刪除地址應對 /addresses/8001 送出 DELETE`,
    `實際：${JSON.stringify(calls.filter((c) => c.method === 'DELETE').map((c) => c.path))}`);
  ok(await until(async () => (await page.locator('#confirm-update-btn').isHidden()) && (await page.locator('#addr-street').inputValue()) === ''),
    `${name}：刪除正在編輯的地址後表單應重設（更新鈕隱藏、欄位清空）`,
    `實際：更新鈕隱藏=${await page.locator('#confirm-update-btn').isHidden()}，欄位=「${await page.locator('#addr-street').inputValue()}」`);

  // 5. 刪除另一筆（8002）：清單重新載入後不再有它（同一頁內確認，不換頁，避免中止重新載入的請求）
  await act('個人資料：地址 8002 刪除', page.locator('button[onclick*="deleteExtraAddress"][data-id="8002"]').first());
  ok(await until(() => calls.some((c) => c.method === 'DELETE' && c.path.endsWith('/addresses/8002'))),
    `${name}：刪除地址應對 /addresses/8002 送出 DELETE`);
  ok(await until(async () => (await page.locator('button[onclick*="deleteExtraAddress"][data-id="8002"]').count()) === 0),
    `${name}：刪除後的地址清單不應再有 8002`);

  // 6. 收藏愛心：數字 storeId 送出，卡片隨後消失
  await go('favorites');
  await act('個人資料：收藏 2001 取消', page.locator('button[onclick*="toggleProfileFavorite"][data-store-id="2001"]').first());
  const toggled = await until(() => calls.some((c) => isToggle(c) && c.body && c.body.storeId === 2001));
  ok(toggled, `${name}：取消收藏請求的 storeId 應為數字 2001`,
    `實際：${JSON.stringify(calls.filter(isToggle).map((c) => c.body))}`);
  ok(await until(async () => (await page.locator('#favorites-container > div[data-store-id]').count()) === 1),
    `${name}：取消收藏後收藏清單應只剩 1 家`);

  // 7. 收藏卡導覽（放最後：會離開本頁）
  await go('favorites');
  await settle(page);
  await clickCard('個人資料：收藏 2002 卡片', page.locator('#favorites-container > div[data-store-id="2002"]'));
  ok(await until(() => page.url().includes('store.html?id=2002')),
    `${name}：收藏卡應前往 store.html?id=2002`, `實際網址：${page.url()}`);

  finish(S, ok, name);
  await S.ctx.close();
}

// ─── 進入點 ────────────────────────────────────────────────────────────────

const SECTIONS = [
  ['店家列表（卡片與收藏）', sectionStoreList],
  ['搜尋結果（暫停接單守衛與收藏）', sectionSearch],
  ['首頁（封面退回、卡片導覽與收藏）', sectionIndex],
  ['導覽列購物車下拉（移除）', sectionNavDropdown],
  ['購物車頁（數量、移除、切換店家）', sectionCartDetails],
  ['結帳頁（數量、編輯、移除、選券）', sectionCheckout],
  ['團購頁（數量、編輯、移除、品項優惠券）', sectionGroupOrder],
  ['訂單列表（封面退回與評星連結）', sectionOrders],
  ['個人資料（預設卡、刪卡、地址、收藏）', sectionProfile],
];

module.exports = async function run() {
  console.log('\n═══ 流程：內嵌事件控制項（N-14） ═══');
  const only = process.env.FLOW_ONLY || '';
  const list = SECTIONS.filter(([n]) => !only || n.includes(only));
  if (only) console.log(`  （FLOW_ONLY=${only}：只跑 ${list.length} 個區段）`);
  let failed = 0;
  const ok = (cond, check, detail = '') => {
    if (cond) return true;
    failed++;
    report.fail(detail ? `${check}\n${detail}` : check);
    console.log(`  ✗ ${check}${detail ? `：${String(detail).split('\n')[0]}` : ''}`);
    return false;
  };
  const browser = await launch();
  try {
    for (const [name, body] of list) {
      console.log(`  ▸ ${name}`);
      sessions = [];
      try {
        await body(browser, ok, name);
      } catch (e) {
        if (process.env.FLOW_DEBUG) console.log(String((e && e.stack) || e));
        const errs = sessions.flatMap((S) => projectErrors(S.errors)).slice(0, 3);
        ok(false, `${name}：執行中斷`, [String((e && e.message) || e).split('\n')[0], ...errs].join('\n'));
      }
    }
  } finally {
    await browser.close();
  }
  return failed;
};

if (require.main === module) module.exports().then((f) => process.exit(f ? 1 : 0));
