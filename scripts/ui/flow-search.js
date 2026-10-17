/**
 * 流程四：搜尋結果分頁（頁首搜尋框 → 第一頁 → 展開更多 → 競態）
 *
 * 以 page.route 攔截 /api/stores/search，改回固定的假資料，所以這條流程測的是前端的
 * 分頁狀態機，不依賴資料庫裡有哪些店家。後端的分頁規則由 PublicServiceSearchTest
 * 與 e2e-verify.js 守住。
 */
const { FE, login, launch, newSession, customerStorage, projectErrors } = require('./harness');
const report = require('./report');

// 每個關鍵字有自己的店家 id 區段，重複或混入別的關鍵字都看得出來。
// 「甲」的回應故意延遲，用來重現競態：它送出得早、回來得晚。
const FIXTURE = {
  春: { total: 15, base: 1000 },
  甲: { total: 3, base: 2000, delay: 1500 },
  乙: { total: 5, base: 3000 },
};

const isSearchApi = (url) => url.pathname.endsWith('/api/stores/search');

function storesFor(kw, page, size) {
  const f = FIXTURE[kw];
  const start = (page - 1) * size;
  const end = Math.min(start + size, f.total);
  const out = [];
  for (let i = start; i < end; i++) {
    out.push({
      storeId: f.base + i + 1,
      storeName: `${kw}店家${i + 1}`,
      brandName: `${kw}品牌`,
      brandLogoUrl: null,
      coverUrl: null,
      address: '台北市大安區',
      avgRating: 4.5,
      reviewCount: 0,
      status: 'active',
      isAcceptingOrders: true,
      isFavorite: false,
      allowsDelivery: false,
      minDeliveryAmount: null,
      openingHours: null,
      distance: null,
    });
  }
  return out;
}

const cardIds = (page) =>
  page.$$eval('#store-grid > [data-store-id]', (els) => els.map((e) => e.dataset.storeId));

/** 等卡片數量到達 n（最多 10 秒），回傳當下的卡片數。 */
async function waitForCards(page, n) {
  await page.waitForFunction(
    (want) => document.querySelectorAll('#store-grid > [data-store-id]').length === want,
    n,
    { timeout: 10000 },
  ).catch(() => {});
  return (await cardIds(page)).length;
}

/** 所有 id 都落在該關鍵字的區段內。 */
const belongsTo = (ids, kw) => {
  const f = FIXTURE[kw];
  return ids.every((id) => Number(id) > f.base && Number(id) <= f.base + f.total);
};

module.exports = async function run() {
  console.log('\n═══ 流程四：搜尋結果分頁 ═══');
  let step = 0, failed = 0;
  const log = (s) => console.log(`  ${++step}. ${s}`);
  const fail = (s) => { failed++; report.fail(s); console.log(`  ✗ ${s}`); };

  const cust = await login('0912000000');
  const browser = await launch();
  try {
    // 桌機寬度：頁首搜尋框在窄螢幕可能收起
    const S = await newSession(browser, customerStorage(cust), { viewport: { width: 1280, height: 900 }, label: 'search' });
    const requests = [];
    await S.page.route(isSearchApi, async (route) => {
      const u = new URL(route.request().url());
      const kw = u.searchParams.get('keyword') || '';
      const page = Number(u.searchParams.get('page') || 1);
      const size = Number(u.searchParams.get('size') || 50);
      requests.push({ kw, page, size });
      const f = FIXTURE[kw];
      if (f && f.delay) await new Promise((r) => setTimeout(r, f.delay));
      const data = f
        ? { stores: storesFor(kw, page, size), page, size, total: f.total,
            totalPages: Math.ceil(f.total / size), hasNext: page * size < f.total }
        : { stores: [], page, size, total: 0, totalPages: 0, hasNext: false };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: '200', msg: 'success', data }),
      });
    });

    await S.page.goto(FE + '/Customer/search_result.html', { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
    await S.page.waitForSelector('#header-search-input', { timeout: 15000 }).catch(() => {});

    // 1. 第一頁：12 筆、結果數為總筆數 15、展開更多可見，請求參數 page=1&size=12
    await S.page.fill('#header-search-input', '春');
    const firstCount = await waitForCards(S.page, 12);
    const firstNum = await S.page.textContent('#result-num');
    const firstMore = await S.page.isVisible('#load-more-btn');
    const firstReq = requests.find((r) => r.kw === '春');
    if (firstCount !== 12) fail(`第一頁應顯示 12 筆卡片，實際 ${firstCount} 筆`);
    if (firstNum !== '15') fail(`結果數應為總筆數 15，實際為 ${firstNum}`);
    if (!firstMore) fail('還有下一頁時「展開更多」應可見');
    if (!firstReq || firstReq.page !== 1 || firstReq.size !== 12)
      fail(`第一頁的請求參數應為 page=1&size=12，實際 ${JSON.stringify(firstReq)}`);
    log(`第一頁：${firstCount} 筆，結果數 ${firstNum}`);

    // 2. 展開更多：第二頁接在後面，共 15 筆、不重複，最後一頁後按鈕隱藏
    await S.page.click('#load-more-btn');
    const secondCount = await waitForCards(S.page, 15);
    const ids = await cardIds(S.page);
    const uniqueIds = new Set(ids).size;
    const moreAfter = await S.page.isVisible('#load-more-btn');
    const secondReq = requests.find((r) => r.kw === '春' && r.page === 2);
    const springCalls = requests.filter((r) => r.kw === '春').length;
    if (secondCount !== 15) fail(`展開更多後應有 15 筆卡片，實際 ${secondCount} 筆`);
    if (uniqueIds !== ids.length) fail(`展開更多後卡片重複：${ids.length} 筆中只有 ${uniqueIds} 個不同的店家`);
    if (moreAfter) fail('已到最後一頁，「展開更多」應隱藏');
    if (!secondReq || secondReq.size !== 12)
      fail(`第二頁的請求參數應為 page=2&size=12，實際 ${JSON.stringify(secondReq)}`);
    if (springCalls !== 2) fail(`「春」應只送出 2 次請求（第 1 頁、第 2 頁），實際 ${springCalls} 次`);
    log(`展開更多：${secondCount} 筆，不重複：${uniqueIds === ids.length ? '是' : '否'}`);

    // 3. 競態：先搜「甲」（回應延遲 1.5 秒），接著改搜「乙」。畫面必須停在「乙」的結果
    await S.page.fill('#header-search-input', '甲');
    await S.page.waitForTimeout(500);   // 防抖 350ms：讓「甲」的請求已經送出
    await S.page.fill('#header-search-input', '乙');
    const betaCount = await waitForCards(S.page, 5);
    await S.page.waitForTimeout(2500);  // 等「甲」的延遲回應到達
    const raceIds = await cardIds(S.page);
    const raceTitle = await S.page.textContent('#search-title');
    const raceNum = await S.page.textContent('#result-num');
    if (!requests.some((r) => r.kw === '甲')) fail('競態測試未成立：「甲」的請求沒有送出');
    if (betaCount !== 5 || raceIds.length !== 5) fail(`競態：畫面應只剩「乙」的 5 筆，實際 ${raceIds.length} 筆`);
    else if (!belongsTo(raceIds, '乙')) fail('競態：畫面混入了較早送出的「甲」的結果');
    if (!String(raceTitle).includes('乙')) fail(`競態：標題應為「乙」的搜尋結果，實際為「${raceTitle}」`);
    if (raceNum !== '5') fail(`競態：結果數應為 5，實際為 ${raceNum}`);
    log(`競態：延遲回應的「甲」沒有覆蓋「乙」（${raceIds.length} 筆，結果數 ${raceNum}）`);

    const errs = projectErrors(S.errors);
    if (errs.length) fail(`前端有錯誤：\n      ${errs.join('\n      ')}`);
  } finally {
    await browser.close();
  }
  return failed;
};

if (require.main === module) module.exports().then(f => process.exit(f ? 1 : 0));
