/**
 * 使用者可控字串的輸出端跳逸檢查（A-02 的回歸守門）。
 *
 * 規格名稱、配料名稱、顧客快照都是品牌或顧客可以寫進去的字串，前端會把它們插進 innerHTML。
 * 這支檢查不啟動後端：把頁面裡負責「把這些字串變成 HTML」的函式原始碼抽出來，放進空白頁的
 * 真實 DOM 執行，灌入含標籤的字串，然後確認：
 *   1. 產生的 DOM 只有預期的容器與 <span>，沒有 img／svg／b 等元素，也沒有 on* 事件屬性；
 *   2. 標籤的文字與原字串一致（轉義沒有吃掉內容，也沒有重複轉義成 &amp;）；
 *   3. 攻擊向量沒有真的執行（window.__xss 未被設定）。
 *
 * 只能檢查列在 TARGETS 與 escapeHelpers 的函式。它抓的是「函式被改壞、或這些函式的轉義被拿掉」，
 * 不是通用的靜態分析——新頁面漏寫轉義時要靠 code review，或把新函式加進來。
 *
 *   node scripts/ui/check-output-escaping.js
 *
 * 作為 run-all.js 的一個套件執行；失敗時以 "  ✗ <檢查名稱>" 回報。
 */
const fs = require('fs');
const path = require('path');
const { launch } = require('./harness');

const ROOT = path.resolve(__dirname, '..', '..');

// 攻擊向量：標籤注入、屬性跳出、單引號跳出，以及應該原樣顯示的一般字元
const PAYLOADS = [
  '<img src=x onerror="window.__xss=1">',
  '"><svg onload="window.__xss=1">',
  "x' onerror='window.__xss=1",
  '<b>粗體</b> & "引號"',
];

const CHECKOUT = 'frontend/Customer/checkout.html';
const GROUP_ORDER = 'frontend/Customer/group_order.html';

// 允許的輸出結構：外層容器（DIV）與標籤（SPAN），而且只能帶 class 屬性
const ALLOWED_TAGS = new Set(['DIV', 'SPAN']);

function read(rel) {
  return fs.readFileSync(path.join(ROOT, rel), 'utf8');
}

/** 從 `function NAME(` 開始，依大括號配對抽出整個函式宣告（這些目標函式的字串裡沒有不成對的大括號） */
function extractFunction(src, name) {
  const start = src.search(new RegExp(`function\\s+${name}\\s*\\(`));
  if (start < 0) throw new Error(`找不到 function ${name}`);
  let depth = 0;
  for (let i = src.indexOf('{', start); i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1);
  }
  throw new Error(`function ${name} 大括號不成對`);
}

/**
 * group_order.html 頂部的 `var esc = window.esc || (window.esc = (v) => …)))`。
 * 宣告內含 '&amp;' 這類字串，不能用分號斷句，改為抽到該行的 `)))` 為止。
 */
function extractEscExpression(src) {
  const start = src.indexOf('var esc = window.esc');
  if (start < 0) throw new Error('找不到 var esc 宣告');
  const end = src.indexOf(')));', start);
  if (end < 0) throw new Error('var esc 宣告結尾不符預期');
  return src.slice(start + 'var esc = '.length, end + 3);
}

/** 在空白頁的真實 DOM 裡執行 run(arg)，回傳其結果；每次都是乾淨的頁面 */
async function inBlankPage(browser, run, arg) {
  const page = await browser.newPage();
  try {
    await page.setContent('<!doctype html><meta charset="utf-8"><body><div id="out"></div></body>');
    return await page.evaluate(run, arg);
  } finally {
    await page.close();
  }
}

/**
 * 在空白頁把 markup 放進容器，檢查結構與文字。
 * 攻擊向量可能是非同步觸發（img 的 onerror），所以先等一小段時間再讀取 window.__xss。
 */
async function inspectMarkup(browser, markup) {
  return inBlankPage(browser, async (html) => {
    const out = document.getElementById('out');
    out.innerHTML = html;
    await new Promise((resolve) => setTimeout(resolve, 100));
    const elements = Array.from(out.querySelectorAll('*'));
    return {
      unexpected: elements
        .filter((el) => !['DIV', 'SPAN'].includes(el.tagName)
          || Array.from(el.attributes).some((a) => a.name !== 'class'))
        .map((el) => el.outerHTML.slice(0, 80)),
      texts: elements.filter((el) => el.tagName === 'SPAN').map((el) => el.textContent),
      triggered: window.__xss === 1,
    };
  }, markup);
}

function verdict(result, expectedTexts) {
  const problems = [];
  if (result.triggered) problems.push('攻擊向量被執行（window.__xss 被設定）');
  if (result.unexpected.length) problems.push(`產生了非預期的元素：${result.unexpected.join(' | ')}`);
  const expected = JSON.stringify(expectedTexts);
  const actual = JSON.stringify(result.texts);
  if (expected !== actual) problems.push(`標籤文字不一致：預期 ${expected}，實際 ${actual}`);
  return problems.length ? problems.join('；') : null;
}

module.exports = async function run() {
  const checkoutSrc = read(CHECKOUT);
  const groupSrc = read(GROUP_ORDER);
  const failures = [];
  const browser = await launch();

  try {
    const escapeHtmlSrc = extractFunction(checkoutSrc, 'escapeHtml');
    const checkoutTagsSrc = extractFunction(checkoutSrc, 'createCustomizationTags');
    const groupEscExpr = extractEscExpression(groupSrc);
    const groupTagsSrc = extractFunction(groupSrc, 'createCustomizationTags');

    for (const payload of PAYLOADS) {
      const shown = JSON.stringify(payload).slice(0, 40);

      // 1) escapeHtml 本身：轉義後的文字必須與原字串完全相同，且不產生任何元素
      const roundTrip = await inBlankPage(browser, ([code, input]) => {
        const escapeHtml = new Function(`${code}; return escapeHtml;`)();
        const out = document.getElementById('out');
        out.innerHTML = escapeHtml(input);
        return {
          elements: out.querySelectorAll('*').length,
          text: out.textContent,
          triggered: window.__xss === 1,
        };
      }, [escapeHtmlSrc, payload]);
      const roundTripProblems = [];
      if (roundTrip.triggered) roundTripProblems.push('攻擊向量被執行');
      if (roundTrip.elements) roundTripProblems.push(`產生了 ${roundTrip.elements} 個元素`);
      if (roundTrip.text !== payload) roundTripProblems.push(`文字不一致：實際 ${JSON.stringify(roundTrip.text)}`);
      if (roundTripProblems.length) {
        failures.push({ label: `escapeHtml 往返 ${shown}`, detail: roundTripProblems.join('；') });
      }

      // 2) checkout：購物車品項的規格與配料標籤（名稱來自品牌後台）
      const checkoutMarkup = await inBlankPage(browser, ([code, input]) => {
        const NavAuth = { normalizeSpec: (s) => String(s ?? '').trim() };
        const createCustomizationTags = new Function('NavAuth', `${code}; return createCustomizationTags;`)(NavAuth);
        return createCustomizationTags(input);
      }, [`${escapeHtmlSrc}\n${checkoutTagsSrc}`, [payload]]).catch((e) => ({ error: e.message }));
      if (checkoutMarkup && checkoutMarkup.error) {
        failures.push({ label: `checkout.createCustomizationTags ${shown}`, detail: `函式執行失敗：${checkoutMarkup.error}` });
      } else {
        const result = await inspectMarkup(browser, checkoutMarkup);
        const problem = verdict(result, [payload.trim()]);
        if (problem) failures.push({ label: `checkout.createCustomizationTags ${shown}`, detail: problem });
      }

      // 3) group_order：群組頁的規格與配料標籤（esc 使用頁面自己的宣告）
      const groupMarkup = await inBlankPage(browser, ([escExpr, code, text]) => {
        const fakeWindow = {};
        const esc = new Function('window', `return ${escExpr};`)(fakeWindow);
        const NavAuth = { normalizeSpec: (s) => String(s ?? '').trim() };
        const createCustomizationTags = new Function('NavAuth', 'esc', `${code}; return createCustomizationTags;`)(NavAuth, esc);
        // 規格與配料同名時會去重，所以預期只剩一個標籤
        return createCustomizationTags({
          sizeSnapshot: text,
          iceSnapshot: '',
          sugarSnapshot: '',
          toppings: [{ name: text, price: 0 }],
        });
      }, [groupEscExpr, groupTagsSrc, payload]).catch((e) => ({ error: e.message }));
      if (groupMarkup && groupMarkup.error) {
        failures.push({ label: `group_order.createCustomizationTags ${shown}`, detail: `函式執行失敗：${groupMarkup.error}` });
      } else {
        const result = await inspectMarkup(browser, groupMarkup);
        const problem = verdict(result, [payload.trim()]);
        if (problem) failures.push({ label: `group_order.createCustomizationTags ${shown}`, detail: problem });
      }
    }
  } finally {
    await browser.close();
  }

  for (const f of failures) {
    console.log(`  ✗ ${f.label}`);
    console.log(`      ${f.detail}`);
  }
  if (!failures.length) {
    console.log(`  ✓ 輸出端跳逸：${PAYLOADS.length} 組攻擊向量在 3 個函式上全數被轉義`);
  }
  return failures.length;
};

if (require.main === module) {
  module.exports().then((n) => process.exit(n ? 1 : 0), (e) => {
    console.error(e);
    process.exit(1);
  });
}
