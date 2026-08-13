/**
 * 流程二：轉盤抽優惠券
 * 首頁浮動入口 → 選品牌 → 轉盤 → 中獎 → 優惠券入帳 → 當日不可再抽
 * 用當天還沒抽過的新帳號，確保一定抽得到。
 */
const { FE, api, newCustomer, launch, newSession, customerStorage, projectErrors } = require('./harness');

let step = 0, failed = 0;
const log = (s) => console.log(`  ${++step}. ${s}`);
const fail = (s) => { failed++; console.log(`  ✗ ${s}`); };

const couponCount = async (u) =>
  ((await api('/api/coupons/my', { token: u.token })).data || []).length;

module.exports = async function run() {
  console.log('\n═══ 流程二：轉盤抽優惠券 ═══');
  const user = await newCustomer('0933', '轉盤測試員');
  const browser = await launch();
  try {
    const { page, errors, shot } = await newSession(browser, customerStorage(user), { label: 'wheel' });
    log(`新帳號 ${user.phone}（當日尚未抽過）`);

    const before = await couponCount(user);
    log(`抽獎前優惠券數：${before}`);

    await page.goto(FE + '/Customer/index.html', { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
    await page.waitForTimeout(3000);

    const fab = await page.$('#floating-roulette-btn');
    if (!fab) { fail('首頁找不到轉盤入口按鈕'); return failed; }
    await fab.click({ force: true });
    await page.waitForTimeout(2500);
    await shot('modal');
    if (await page.evaluate(() => document.getElementById('roulette-modal').classList.contains('hidden'))) {
      fail('轉盤視窗沒有打開'); return failed;
    }
    log('轉盤視窗開啟');

    const brands = await page.$$('#roulette-brands-grid > *');
    if (!brands.length) { await shot('no-brands'); fail('品牌清單是空的'); return failed; }
    log(`列出 ${brands.length} 個品牌`);

    await brands[0].click();
    await page.waitForTimeout(2500);
    await shot('wheel');
    if (await page.evaluate(() => document.getElementById('roulette-step-wheel').classList.contains('hidden'))) {
      fail('選了品牌之後沒有進到轉盤畫面'); return failed;
    }
    log(`進入轉盤：${((await page.textContent('#roulette-store-name')) || '').trim()}`);

    const spin = await page.$('#spin-btn');
    if (!spin || !await spin.isEnabled()) { fail('抽獎按鈕不存在或停用'); return failed; }
    await spin.click();
    log('按下「立即抽獎」');

    await page.waitForTimeout(9000);   // 轉盤動畫
    await shot('result');
    if (await page.evaluate(() => document.getElementById('roulette-step-result').classList.contains('hidden'))) {
      await shot('no-result'); fail('轉完之後沒有顯示中獎結果'); return failed;
    }
    const text = await page.evaluate(() => document.getElementById('roulette-step-result').innerText);
    log(`中獎畫面：${text.replace(/\s+/g, ' ').trim().slice(0, 50)}`);

    const after = await couponCount(user);
    if (after > before) log(`優惠券入帳：${before} → ${after}`);
    else fail(`畫面顯示中獎，但優惠券沒有增加（${before} → ${after}）`);

    const again = await api('/api/coupons/spin', { method: 'POST', token: user.token, body: { brandId: 1 } });
    if (again.code === '200') fail('每日限抽一次沒有生效，第二次仍然抽中');
    else log(`當日第二次抽獎被擋下：${String(again.msg).slice(0, 30)}`);

    const errs = projectErrors(errors);
    if (errs.length) fail(`前端有錯誤：\n      ${errs.join('\n      ')}`);
  } finally {
    await browser.close();
  }
  return failed;
};

if (require.main === module) module.exports().then(f => process.exit(f ? 1 : 0));
