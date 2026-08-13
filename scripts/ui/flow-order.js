/**
 * 流程一：點餐
 * 首頁 → 店家頁 → 客製化 → 加入購物車 → 購物車 → 結帳 → 訂單成立
 * 全程操作真實 UI（點擊、選規格），不直接呼叫下單 API。
 */
const { FE, api, login, launch, newSession, customerStorage, projectErrors } = require('./harness');

let step = 0, failed = 0;
const log = (s) => console.log(`  ${++step}. ${s}`);
const fail = (s) => { failed++; console.log(`  ✗ ${s}`); };

module.exports = async function run() {
  console.log('\n═══ 流程一：點餐 ═══');
  const cust = await login('0912000000');
  const browser = await launch();
  try {
    const { page, errors, shot } = await newSession(browser, customerStorage(cust), { label: 'order' });

    await page.goto(FE + '/Customer/index.html', { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
    await page.waitForTimeout(2500);
    await shot('home');
    log('首頁載入');

    await page.goto(FE + '/Customer/store.html?id=1', { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
    await page.waitForTimeout(2500);
    await shot('store');
    const drinks = await page.$$('[data-product-id]');
    if (!drinks.length) { fail('店家頁沒有任何飲品卡片'); return failed; }
    log(`店家頁載入，${drinks.length} 款飲品`);

    await drinks[0].click();
    await page.waitForTimeout(1500);
    if (!await page.isVisible('#modal-add-to-cart').catch(() => false)) { fail('客製化視窗沒有打開'); return failed; }
    log(`開啟客製化：${((await page.textContent('#modal-product-title')) || '').trim()}`);
    await shot('customize');

    let picked = 0;
    for (const b of await page.$$('#modal-spec-container button, [data-spec-id], .spec-option')) {
      if (await b.isVisible().catch(() => false)) { await b.click().catch(() => {}); picked++; }
      if (picked >= 3) break;
    }
    await page.waitForTimeout(600);
    log(`選了 ${picked} 個規格，價格 ${((await page.textContent('#modal-product-price-display')) || '').trim()}`);

    await page.click('#modal-add-to-cart');
    await page.waitForTimeout(2000);
    await shot('added');
    log('加入購物車');

    await page.goto(FE + '/Customer/cart_details.html', { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
    await page.waitForTimeout(2500);
    await shot('cart');
    if (/購物車是空的|沒有商品|空空如也/.test(await page.evaluate(() => document.body.innerText))) {
      fail('購物車頁顯示為空，品項沒進去'); return failed;
    }
    log('購物車頁有品項');

    await page.goto(FE + '/Customer/checkout.html', { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
    await page.waitForTimeout(2500);

    // 預設是外送，需要完整地址才會解鎖送出鈕；這裡走自取
    await page.evaluate(() => {
      const r = document.querySelector('input[name="delivery_method"][value="pickup"]');
      if (r) { r.checked = true; r.dispatchEvent(new Event('change', { bubbles: true })); r.click(); }
    });
    await page.waitForTimeout(2000);
    await shot('checkout-pickup');
    log('結帳頁，選擇自取');

    const placeBtn = await page.$('#submit-order-btn');
    if (!placeBtn) { await shot('no-button'); fail('結帳頁找不到送出訂單的按鈕'); return failed; }
    if (!await placeBtn.isEnabled()) { await shot('disabled'); fail('選了自取後送出鈕仍為停用'); return failed; }
    await placeBtn.click();
    await page.waitForTimeout(5000);
    await shot('placed');

    // 用導向後的 orderId 驗證，不要用訂單筆數——卡片 API 有分頁，
    // 示範帳號累積超過一頁之後筆數會卡在 page size，看起來像沒新增。
    const orderId = (page.url().match(/orderId=(\d+)/) || [])[1];
    if (!orderId) {
      fail(`送出後沒有導向訂單完成頁，目前在 ${page.url()}`);
    } else {
      const r = await api(`/api/orders/${orderId}`, { token: cust.token });
      if (r.code === '200' && r.data && r.data.status)
        log(`訂單成立：#${orderId}，狀態 ${r.data.status}，金額 $${r.data.totalAmount}`);
      else
        fail(`導向完成頁但查不到訂單 #${orderId}：${JSON.stringify(r).slice(0, 120)}`);
    }

    const errs = projectErrors(errors);
    if (errs.length) { fail(`前端有錯誤：\n      ${errs.join('\n      ')}`); }
  } finally {
    await browser.close();
  }
  return failed;
};

if (require.main === module) module.exports().then(f => process.exit(f ? 1 : 0));
