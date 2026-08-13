/**
 * 三個前台的全頁面普掃：逐頁載入，收集 JS 例外與 API 錯誤。
 *
 * 這支專門抓「API 單獨打會過、但畫面一載入就壞」的問題——
 * LazyInitializationException、相對路徑打錯主機、未宣告的變數、
 * 兩支腳本搶同一張 canvas，都是這樣被抓到的。
 */
const { FE, login, corporateLogin, launch, newSession, customerStorage, projectErrors } = require('./harness');

const CUSTOMER = ['index', 'store_list', 'store.html?id=1', 'map', 'search_result?keyword=茶',
  'cart_details', 'checkout', 'orders', 'order_details', 'group_order', 'profile'];
const BRAND = ['hq-dashboard', 'hq-menu-overview', 'hq-menu-specs', 'hq-store-management',
  'hq-finance', 'hq-reputation', 'hq-trending'];
const STORE = ['home', 'order-all', 'menu-drinks', 'menu-toppings', 'store-basic-info',
  'store-business-hours', 'store-delivery-settings', 'finance-revenue-overview',
  'finance-platform-commission-details', 'reports-business-overview',
  'reports-product-ranking', 'reports-rating-stars'];

module.exports = async function run() {
  console.log('\n═══ 全頁面普掃 ═══');
  const cust = await login('0912000000');
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

    await sweep('Customer 前台', '/Customer', CUSTOMER, customerStorage(cust), { width: 390, height: 844 });
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
