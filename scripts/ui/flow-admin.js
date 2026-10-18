/**
 * 流程五：管理員審核（沒有權杖 → 錯誤密碼 → 登入 → 核准 → 退件 → 併發 409 → 輸出端轉義 → 登出）
 *
 * 三個待審品牌由 API 註冊，之後都由管理員在後台頁面操作。核准後品牌可以登入、出現在公開品牌列表；
 * 退件後登入回 403、不出現。併發那一步模擬「另一位管理員先處理了」：清單還是舊的，
 * 按下核准應回 409，畫面提示並重新載入。最後用攔截的假資料確認品牌名稱以文字顯示、不被當成 HTML 執行。
 */
const { FE, api, adminLogin, ADMIN_USERNAME, ADMIN_PASSWORD, launch, newSession, projectErrors } = require('./harness');
const report = require('./report');

const LOGIN_URL = /\/Admin\/login\.html$/;
const BRANDS_URL = /\/Admin\/brands\.html$/;

/** 從目前的頁沿著「下一頁」往後找，找到含該品牌的那一頁就停在那裡；找不到回 false。 */
async function findOnPendingList(page, name, maxPages = 50) {
  for (let i = 0; i < maxPages; i++) {
    const names = await page.$$eval('#brand-list button[data-brand-name]', (els) => els.map((e) => e.dataset.brandName));
    if (names.includes(name)) return true;
    const next = page.locator('#next-page');
    if (!(await next.isEnabled())) return false;
    const before = await page.textContent('#page-info');
    await next.click();
    await page.waitForFunction((prev) => document.getElementById('page-info').textContent !== prev, before, { timeout: 10000 });
  }
  return false;
}

const actionButton = (page, name, action) =>
  page.locator(`#brand-list li:has(button[data-brand-name="${name}"]) button[data-action="${action}"]`);

/** 等待提示出現；逾時回 false，由呼叫端記錄成具名的失敗，不讓整個流程中斷。 */
const waitForNotice = (page, text) =>
  page.waitForFunction(
    (t) => (document.getElementById('brand-status')?.textContent || '').includes(t),
    text,
    { timeout: 10000 },
  ).then(() => true, () => false);

/** 等待某品牌從清單消失；逾時回 false。 */
const waitForRowGone = (page, name) =>
  page.waitForFunction(
    (n) => ![...document.querySelectorAll('#brand-list button[data-brand-name]')].some((e) => e.dataset.brandName === n),
    name,
    { timeout: 10000 },
  ).then(() => true, () => false);

module.exports = async function run() {
  console.log('\n═══ 流程五：管理員審核 ═══');
  let step = 0, failed = 0;
  const log = (s) => console.log(`  ${++step}. ${s}`);
  const fail = (s) => { failed++; report.fail(s); console.log(`  ✗ ${s}`); };

  // 帳號只用英數與底線（不含中文），品牌名稱用中文數字，以時間戳區分每次執行
  const tag = String(Date.now()).slice(-7);
  const mk = (letter, zh) => ({ name: `審核測試${zh}${tag}`, account: `review_${letter}_${tag}`, password: 'demo1234' });
  const A = mk('a', '甲'), B = mk('b', '乙'), C = mk('c', '丙');

  for (const b of [A, B, C]) {
    const j = await api('/api/brand-auth/register', { method: 'POST', body: { name: b.name, account: b.account, password: b.password } });
    if (String(j.code) !== '200') throw new Error(`註冊測試品牌失敗：${JSON.stringify(j).slice(0, 200)}`);
  }

  const browser = await launch();
  try {
    const S = await newSession(browser, {}, { viewport: { width: 1280, height: 900 }, label: 'admin' });
    const page = S.page;

    // 1. 沒有權杖：清單頁直接導向登入頁
    await page.goto(FE + '/Admin/brands.html', { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
    await page.waitForURL(LOGIN_URL, { timeout: 10000 }).catch(() => {});
    if (!LOGIN_URL.test(page.url())) fail(`沒有權杖時應導向登入頁，實際停在 ${page.url()}`);
    log('沒有權杖：清單頁導向登入頁');

    // 2. 錯誤密碼：顯示錯誤、不存權杖、停在登入頁
    await page.fill('#admin-username', ADMIN_USERNAME);
    await page.fill('#admin-password', 'wrong-password-0000');
    await page.click('#admin-login-submit');
    await page.waitForFunction(
      () => !document.getElementById('admin-login-error').classList.contains('hidden'),
      null,
      { timeout: 10000 },
    ).catch(() => {});
    const loginError = String(await page.textContent('#admin-login-error')).trim();
    const tokenAfterWrong = await page.evaluate(() => localStorage.getItem('adminToken'));
    if (!loginError.includes('帳號或密碼錯誤')) fail(`錯誤密碼應顯示「帳號或密碼錯誤」，實際為「${loginError}」`);
    if (tokenAfterWrong !== null) fail('錯誤密碼不應存下權杖');
    if (!LOGIN_URL.test(page.url())) fail('錯誤密碼不應離開登入頁');
    S.errors.length = 0;   // 預期中的 401
    log(`錯誤密碼：顯示「${loginError}」，未存權杖`);

    // 3. 正確密碼：進入清單，三個待審品牌都找得到
    await page.fill('#admin-password', ADMIN_PASSWORD);
    await page.click('#admin-login-submit');
    const onBrands = await page.waitForURL(BRANDS_URL, { timeout: 15000 }).then(() => true, () => false);
    if (!onBrands) {
      fail(`正確密碼應進入待審清單，實際停在 ${page.url()}`);
      return failed;   // 之後的步驟都依賴清單，無法繼續
    }
    await page.waitForFunction(
      () => /^共 \d+ 筆待審核$/.test(document.getElementById('pending-count')?.textContent || ''),
      null,
      { timeout: 15000 },
    ).catch(() => {});
    const savedToken = await page.evaluate(() => localStorage.getItem('adminToken'));
    if (!savedToken) fail('登入成功後應存下 adminToken');
    const foundA = await findOnPendingList(page, A.name);
    const foundB = await findOnPendingList(page, B.name);
    const foundC = await findOnPendingList(page, C.name);
    if (!foundA || !foundB || !foundC) fail(`待審清單應找得到三個測試品牌（甲：${foundA}，乙：${foundB}，丙：${foundC}）`);
    log(`正確密碼：進入待審清單，測試品牌可見（甲${foundA ? '✓' : '✗'} 乙${foundB ? '✓' : '✗'} 丙${foundC ? '✓' : '✗'}）`);

    // 4. 核准甲：清單移除；之後品牌可以登入、出現在公開品牌列表
    if (foundA) {
      await findOnPendingList(page, A.name);
      await actionButton(page, A.name, 'approve').click();
      if (!(await waitForNotice(page, `已核准「${A.name}」`))) fail('核准後應顯示「已核准」的提示');
      if (!(await waitForRowGone(page, A.name))) fail('核准後該品牌應從待審清單移除');
      const login = await api('/api/brand-auth/login', { method: 'POST', body: { account: A.account, password: A.password } });
      const pub = await api('/api/brands');
      const listed = ((pub && pub.data) || []).some((b) => b.brandName === A.name);
      if (String(login.code) !== '200') fail(`核准後品牌應可登入，實際 code=${login.code} ${login.msg || ''}`);
      if (!listed) fail('核准後品牌應出現在公開品牌列表');
      log(`核准甲：清單移除；品牌登入 ${login.code}；公開列表${listed ? '可見' : '不可見'}`);
    }

    // 5. 退件乙：確認視窗需要接受；清單移除；之後品牌登入回 403、不出現在公開列表
    if (foundB) {
      await findOnPendingList(page, B.name);
      const dialogs = [];
      page.once('dialog', async (d) => {
        dialogs.push(d.message());
        await d.accept();
      });
      await actionButton(page, B.name, 'reject').click();
      if (!(await waitForNotice(page, `已退件「${B.name}」`))) fail('退件後應顯示「已退件」的提示');
      if (!(await waitForRowGone(page, B.name))) fail('退件後該品牌應從待審清單移除');
      if (!dialogs.some((m) => m.includes(B.name))) fail('退件前的確認提示應包含品牌名稱');
      const login = await api('/api/brand-auth/login', { method: 'POST', body: { account: B.account, password: B.password } });
      const pub = await api('/api/brands');
      const listed = ((pub && pub.data) || []).some((b) => b.brandName === B.name);
      if (String(login.code) !== '403') fail(`退件後品牌登入應回 403，實際 code=${login.code}`);
      if (listed) fail('退件後品牌不應出現在公開品牌列表');
      log(`退件乙：清單移除；品牌登入 ${login.code}；公開列表${listed ? '仍可見' : '不可見'}`);
    }

    // 6. 併發：另一位管理員先核准了丙，清單還是舊的；按下核准應回 409、提示並重新載入
    if (foundC) {
      await findOnPendingList(page, C.name);
      const cId = await actionButton(page, C.name, 'approve').getAttribute('data-brand-id');
      const other = await adminLogin();
      const approvedElsewhere = await api(`/api/admin/brands/${cId}/approve`, { method: 'POST', token: other.token });
      if (String(approvedElsewhere.code) !== '200') fail(`模擬另一位管理員核准失敗：${JSON.stringify(approvedElsewhere).slice(0, 200)}`);
      await actionButton(page, C.name, 'approve').click();
      const reloaded = await waitForNotice(page, '已重新載入清單');
      const gone = await waitForRowGone(page, C.name);
      if (!reloaded || !gone) fail('已被處理的品牌按下核准後，應提示並重新載入清單');
      const notice = String(await page.textContent('#brand-status')).trim();
      if (!notice.includes('只能處理待審核的品牌')) fail(`已被處理的品牌應顯示 409 的說明，實際為「${notice}」`);
      S.errors.length = 0;   // 預期中的 409
      log('併發：已被處理的品牌回 409，畫面提示並重新載入');
    }

    // 7. 輸出端：品牌名稱若含標籤，必須以文字顯示，不得被執行（以攔截的假資料驗證）
    const payload = '<img id="xss-probe" src="x" onerror="window.__xss=1">';
    const pendingRoute = '**/api/admin/brands/pending**';
    await page.route(pendingRoute, (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Access-Control-Allow-Origin': new URL(FE).origin },
      body: JSON.stringify({
        code: '200',
        msg: 'success',
        data: {
          brands: [{ brandId: 999001, name: payload, account: 'probe', createdAt: '2026-10-18T10:00:00' }],
          page: 0, size: 20, total: 1, totalPages: 1, hasNext: false,
        },
      }),
    }));
    await page.click('#refresh-btn');
    await page.waitForFunction(
      () => document.getElementById('pending-count')?.textContent === '共 1 筆待審核',
      null,
      { timeout: 10000 },
    ).catch(() => {});
    const probe = await page.evaluate((p) => ({
      injected: document.getElementById('xss-probe') !== null,
      triggered: window.__xss === 1,
      shown: (document.getElementById('brand-list')?.textContent || '').includes(p),
    }), payload);
    await page.unroute(pendingRoute);
    if (probe.injected || probe.triggered) fail('品牌名稱含標籤時不得被當成 HTML 執行');
    if (!probe.shown) fail('品牌名稱含標籤時應以原文字顯示');
    log(`輸出端：含標籤的品牌名稱${probe.injected || probe.triggered ? '被執行（失敗）' : '以原文字顯示、未執行'}`);

    // 8. 登出：權杖清除、回到登入頁；直接再進清單頁也會被導向登入頁
    await page.click('#logout-btn');
    await page.waitForURL(LOGIN_URL, { timeout: 10000 }).catch(() => {});
    const tokenAfterLogout = await page.evaluate(() => localStorage.getItem('adminToken'));
    if (tokenAfterLogout !== null) fail('登出後應清除 adminToken');
    await page.goto(FE + '/Admin/brands.html', { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
    await page.waitForURL(LOGIN_URL, { timeout: 10000 }).catch(() => {});
    if (!LOGIN_URL.test(page.url())) fail('登出後直接進入清單頁應導向登入頁');
    log('登出：權杖已清除，清單頁導向登入頁');

    const errs = projectErrors(S.errors);
    if (errs.length) fail(`前端有錯誤：\n      ${errs.join('\n      ')}`);
  } finally {
    await browser.close();
  }
  return failed;
};

if (require.main === module) module.exports().then((f) => process.exit(f ? 1 : 0));
