/**
 * 三個前台的全頁面普掃：逐頁載入，收集 JS 例外與 API 錯誤。
 *
 * 這支專門抓「API 單獨打會過、但畫面一載入就壞」的問題——
 * LazyInitializationException、相對路徑打錯主機、未宣告的變數、
 * 兩支腳本搶同一張 canvas，都是這樣被抓到的。
 */
const { FE, api, login, newCustomer, corporateLogin, launch, newSession, customerStorage, projectErrors } = require('./harness');

// 顧客端主要頁面。order_details / order_confirm 需要真實 orderId，
// 由 customerPages() 在跑之前去 API 撈一筆補上。
const CUSTOMER_MAIN = ['index', 'store_list', 'store.html?id=1', 'map', 'search_result?keyword=茶',
  'cart_details', 'checkout', 'orders', 'order_details', 'order_confirm', 'group_order', 'profile'];
// 登入／註冊／驗證流程共 8 頁。這些頁在未登入狀態下也要能開，
// 少掃這一段等於「顧客連門都還沒進」的頁面完全沒被驗證過。
const CUSTOMER_AUTH = ['auth/login', 'auth/register', 'auth/register-success', 'auth/forgot-password',
  'auth/reset-password', 'auth/verify-phone', 'auth/account-verify', 'auth/account-integrate'];
const BRAND = ['hq-dashboard', 'hq-menu-overview', 'hq-menu-specs', 'hq-store-management',
  'hq-finance', 'hq-reputation', 'hq-trending'];
const STORE = ['home', 'order-all', 'menu-drinks', 'menu-toppings', 'store-basic-info',
  'store-business-hours', 'store-delivery-settings', 'finance-revenue-overview',
  'finance-platform-commission-details', 'reports-business-overview',
  'reports-product-ranking', 'reports-rating-stars'];

/** 把 order_details / order_confirm 換成帶真實 orderId 的網址（撈不到就原樣保留，走空狀態） */
async function customerPages(cust) {
  const r = await api(`/api/orders/user/${cust.userId}/cards?page=0&size=1`, { token: cust.token });
  const src = (r && r.data) || r || {};
  const list = src.orders || src.content || [];
  const id = Array.isArray(list) && list[0] ? (list[0].orderId || list[0].id) : null;
  if (!id) return CUSTOMER_MAIN;
  return CUSTOMER_MAIN.map(p =>
    (p === 'order_details' || p === 'order_confirm') ? `${p}.html?orderId=${id}` : p);
}

module.exports = async function run() {
  console.log('\n═══ 全頁面普掃 ═══');
  const cust = await login('0912000000');
  // 空狀態帳號：沒有任何訂單／購物車／優惠券。
  // 有資料的帳號掃不出「清單為空時才會踩到的 undefined」，兩種都要掃。
  const fresh = await newCustomer('0977', '空狀態普掃');
  const brand = await corporateLogin('demo_brand');
  const store = await corporateLogin('demo_store');

  const browser = await launch();
  let bad = 0, total = 0;
  try {
    const sweep = async (title, dir, pages, storage, viewport) => {
      console.log(`\n  ── ${title} ──`);
      const { page, errors } = await newSession(browser, storage, { viewport, label: 'sweep' });
      for (const p of pages) {
        const file = p.includes('.html') ? p : p + '.html';
        errors.length = 0;
        await page.goto(FE + dir + '/' + file, { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
        await page.waitForTimeout(2500);
        const real = projectErrors(errors);
        total++;
        if (real.length) { bad++; console.log(`  ✗ ${file}`); real.forEach(e => console.log(`      ${e}`)); }
        else console.log(`  ✓ ${file}`);
      }
    };

    const MOBILE = { width: 390, height: 844 };
    await sweep('Customer 前台（有資料）', '/Customer', await customerPages(cust), customerStorage(cust), MOBILE);
    await sweep('Customer 前台（空狀態）', '/Customer', CUSTOMER_MAIN, customerStorage(fresh), MOBILE);
    await sweep('Customer 登入註冊', '/Customer', CUSTOMER_AUTH, {}, MOBILE);
    await sweep('Brand 後台', '/Brand', BRAND, {
      brandToken: brand.token, brandId: String(brand.brandId),
      JOIN_TOKEN: brand.token, token: brand.token, brandName: brand.name || '',
    }, { width: 1440, height: 900 });
    await sweep('Store 後台', '/store', STORE, {
      store_token: store.token, store_id: String(store.storeId),
      JOIN_TOKEN: store.token, token: store.token,
    }, { width: 1440, height: 900 });

    console.log(`\n  ${total} 個頁面，有問題的 ${bad} 個`);
  } finally {
    await browser.close();
  }
  return bad;
};

if (require.main === module) module.exports().then(b => process.exit(b ? 1 : 0));
