/**
 * 流程三：發起揪團 → 團員加入加點 → 團員付款 → 團長鎖定送出
 * 兩個瀏覽器 context 分飾團長與團員，全程操作真實 UI。
 *
 * 順序不能顛倒：團員必須在 OPEN 狀態下付款，
 * 一旦團長鎖定（LOCKED），團員的控制項就會被隱藏。
 */
const { FE, api, login, newCustomer, launch, newSession, customerStorage, projectErrors } = require('./harness');

let step = 0, failed = 0;
const log = (s) => console.log(`  ${++step}. ${s}`);
const fail = (s) => { failed++; console.log(`  ✗ ${s}`); };

async function addDrink(page, shot, tag, token) {
  // 帶 token 就是「加進這個揪團」——揪團頁的「新增商品」正是導到這個網址
  const url = token ? `/Customer/store.html?id=1&token=${token}` : '/Customer/store.html?id=1';
  await page.goto(FE + url, { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(2500);
  const drinks = await page.$$('[data-product-id]');
  if (!drinks.length) throw new Error('店家頁沒有飲品');
  await drinks[0].click();
  await page.waitForTimeout(1500);
  let n = 0;
  for (const b of await page.$$('#modal-spec-container button, [data-spec-id], .spec-option')) {
    if (await b.isVisible().catch(() => false)) { await b.click().catch(() => {}); n++; }
    if (n >= 3) break;
  }
  await page.waitForTimeout(500);
  await page.click('#modal-add-to-cart');
  await page.waitForTimeout(2000);
  await shot(tag);
}

const groupInfo = async (token, host) =>
  (await api(`/api/group-orders/${token}`, { token: host.token })).data || {};

module.exports = async function run() {
  console.log('\n═══ 流程三：揪團 → 加入 → 結帳 ═══');
  const host = await login('0912000000');
  const member = await newCustomer('0955', '揪團團員');
  await api('/api/wallet/topup', { method: 'POST', token: member.token, body: { amount: 500 } });

  const browser = await launch();
  try {
    const H = await newSession(browser, customerStorage(host), { label: 'group-host' });
    const M = await newSession(browser, customerStorage(member), { label: 'group-member' });
    log(`團長 ${host.userId} / 團員 ${member.phone}(${member.userId})`);

    await addDrink(H.page, H.shot, 'host-cart');
    log('團長加入一杯飲品');

    await H.page.goto(FE + '/Customer/cart_details.html', { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
    await H.page.waitForTimeout(3000);
    // 先點選購物車卡片，沒選時「開始揪團」是停用的
    const card = await H.page.$('#personal-carts-container > *');
    if (!card) { await H.shot('no-cart-card'); fail('購物車頁沒有個人購物車卡片'); return failed; }
    await card.click();
    await H.page.waitForTimeout(2500);
    log('選取個人購物車');

    const groupBtn = await H.page.$('#group-order-btn');
    if (!groupBtn || !await groupBtn.isEnabled()) { await H.shot('group-disabled'); fail('「開始揪團」不可按'); return failed; }
    await groupBtn.click();
    await H.page.waitForTimeout(4000);
    await H.shot('created');
    log('按下「開始揪團」');

    const token = await H.page.evaluate(() => {
      const v = (document.getElementById('group-link-input') || {}).value;
      const m = v && v.match(/token=([A-Za-z0-9]+)/);
      return m ? m[1] : (location.href.match(/token=([A-Za-z0-9]+)/) || [])[1] || null;
    });
    if (!token) { fail('揪團建立後拿不到分享 token'); return failed; }
    log(`取得分享 token：${token}`);

    await H.page.goto(FE + `/Customer/group_order.html?token=${token}`, { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
    await H.page.waitForTimeout(3500);
    await H.shot('host-group-page');
    log('團長進入揪團頁');

    await M.page.goto(FE + `/Customer/group_order.html?token=${token}`, { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
    await M.page.waitForTimeout(3500);
    await M.shot('member-join');
    log('團員用分享連結開啟揪團頁');

    const before = ((await groupInfo(token, host)).items || []).length;
    await addDrink(M.page, M.shot, 'member-add', token);
    const after = ((await groupInfo(token, host)).items || []).length;
    if (after > before) log(`團員加點成功：品項 ${before} → ${after}`);
    else { fail(`團員加點後品項沒有增加（${before} → ${after}）`); return failed; }

    // 團員付自己的份（必須在 OPEN 狀態）
    await M.page.goto(FE + `/Customer/group_order.html?token=${token}`, { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
    await M.page.waitForTimeout(3500);
    const mBtn = await M.page.$('#btn-trigger-member-checkout');
    if (!mBtn || !await mBtn.isEnabled() || !await mBtn.isVisible()) {
      await M.shot('member-pay-unavailable'); fail('團員的「為我的餐點結帳」不可用'); return failed;
    }
    await mBtn.click();
    await M.page.waitForTimeout(2500);
    await M.page.evaluate(() => {
      const r = document.querySelector('input[name="member-payment"][value="STORE_CREDIT"], input[name="payment"][value="STORE_CREDIT"]');
      if (r) { r.checked = true; r.dispatchEvent(new Event('change', { bubbles: true })); r.click(); }
    });
    await M.page.waitForTimeout(1000);
    const mOk = await M.page.$('#btn-confirm-member-pay');
    if (!mOk) { fail('團員付款彈窗找不到確認鈕'); return failed; }
    await mOk.click();
    await M.page.waitForTimeout(2500);
    const mConfirm = await M.page.$('#confirm-ok-btn');
    if (mConfirm) await mConfirm.click();
    await M.page.waitForTimeout(4000);
    await M.shot('member-paid');
    log('團員完成自己的付款');

    // 團長鎖定
    await H.page.goto(FE + `/Customer/group_order.html?token=${token}`, { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
    await H.page.waitForTimeout(3500);
    const statusBefore = (await groupInfo(token, host)).status;
    const lockBtn = await H.page.$('#btn-main-action');
    if (!lockBtn || !await lockBtn.isEnabled()) { await H.shot('lock-disabled'); fail('團長主要動作鈕不可按'); return failed; }
    log(`團長主要動作鈕：「${((await H.page.textContent('#btn-main-action')) || '').replace(/\s+/g, ' ').trim()}」`);
    await lockBtn.click();
    await H.page.waitForTimeout(2000);
    const ok1 = await H.page.$('#confirm-ok-btn');
    if (ok1) { await ok1.click(); log('確認鎖定'); }
    await H.page.waitForTimeout(4500);
    await H.shot('locked');

    // 鎖定後同頁展開結帳：選自取（預設外送，驗證沒過會停用送出鈕）＋儲值金，再送出
    if ((await groupInfo(token, host)).status === 'LOCKED') {
      const pickup = await H.page.$('#btn-pickup');
      if (pickup) { await pickup.click().catch(() => {}); await H.page.waitForTimeout(1500); log('選擇自取'); }
      await H.page.evaluate(() => {
        const r = document.querySelector('input[name="payment"][value="STORE_CREDIT"]');
        if (r) { r.checked = true; r.dispatchEvent(new Event('change', { bubbles: true })); r.click(); }
      });
      await H.page.waitForTimeout(1200);
      const submit = await H.page.$('#btn-main-action');
      if (submit && await submit.isEnabled()) {
        log(`鎖定後送出：「${((await H.page.textContent('#btn-main-action')) || '').replace(/\s+/g, ' ').trim()}」`);
        await submit.click();
        await H.page.waitForTimeout(2000);
        const ok2 = await H.page.$('#confirm-ok-btn');
        if (ok2) { await ok2.click(); log('確認送出訂單'); }
        await H.page.waitForTimeout(5000);
        await H.shot('submitted');
      } else {
        await H.shot('submit-disabled');
        fail('鎖定後送出鈕仍為停用');
      }
    }

    const statusAfter = (await groupInfo(token, host)).status;
    if (statusAfter === 'SUBMITTED' || statusAfter === 'PREPARING')
      log(`揪團送出成功：狀態 ${statusBefore} → ${statusAfter}`);
    else
      fail(`結帳後揪團狀態是 ${statusAfter}（預期 SUBMITTED）`);

    const errs = projectErrors([...H.errors, ...M.errors]);
    if (errs.length) fail(`前端有錯誤：\n      ${errs.join('\n      ')}`);
  } finally {
    await browser.close();
  }
  return failed;
};

if (require.main === module) module.exports().then(f => process.exit(f ? 1 : 0));
