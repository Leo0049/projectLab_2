/**
 * 一次跑完所有 UI 驗證：全頁面普掃 + 三條主線流程。
 * 任何一項失敗就以非 0 結束，供 CI 判斷。
 *
 *   node scripts/ui/run-all.js
 *
 * 每個失敗的檢查項目會印一行 ::error 註解（GitHub Actions 會標在執行結果上）；
 * 有設定 GITHUB_STEP_SUMMARY 時，另把逐項結果表與合計寫進 step summary。
 */
const fs = require('fs');

const suites = [
  ['全頁面普掃', './page-sweep'],
  ['點餐流程', './flow-order'],
  ['轉盤流程', './flow-wheel'],
  ['揪團流程', './flow-group'],
];

/**
 * 套件只回傳失敗數。每條失敗的檢查會印一行 "  ✗ <檢查名稱>"（flow-*.js 的 fail()），
 * page-sweep 則印檔名、錯誤內容縮排在其下。這裡攔下這些輸出，好讓註解與摘要能點名是哪一項；
 * 轉送給 console 的內容不變，畫面上的輸出與原本相同。
 */
async function runSuite(name, mod) {
  const checks = []; // { check, detail: [] }
  let current = null;
  const originalLog = console.log;
  console.log = (...args) => {
    originalLog(...args);
    for (const line of args.map(String).join(' ').split('\n')) {
      const mark = /^\s*✗ (.*)$/.exec(line);
      if (mark) {
        current = { check: mark[1].trim(), detail: [] };
        checks.push(current);
      } else if (current && /^\s{4,}\S/.test(line)) {
        current.detail.push(line.trim());
      } else {
        current = null;
      }
    }
  };

  try {
    const failures = await require(mod)();
    return { name, failures, checks };
  } catch (e) {
    console.error(`\n  ✗ ${name} 執行中斷：${e && e.message}`);
    checks.push({ check: '執行中斷', detail: [String(e && e.message)] });
    return { name, failures: checks.length, checks };
  } finally {
    console.log = originalLog;
  }
}

/** 把一個套件展開成摘要的列：每條失敗一列；沒有失敗的套件只有一列 */
function rowsFor(r) {
  if (!r.failures) return [{ check: '整個套件', ok: true, detail: '' }];
  const rows = r.checks.map((c) => ({ check: c.check, ok: false, detail: c.detail.join('\n') }));
  const unnamed = r.failures - r.checks.length;
  if (unnamed > 0) rows.push({ check: `另外 ${unnamed} 項`, ok: false, detail: '未能辨識檢查名稱，請見上方輸出' });
  return rows;
}

// 註解與表格都是單行：換行改成空白；註解裡的 % 要跳脫，否則 GitHub 會當成控制序列
const oneLine = (s) => String(s).replace(/\r?\n/g, ' ');
const annotationText = (s) => oneLine(s).replace(/%/g, '%25');
const tableCell = (s) => oneLine(s).replace(/\|/g, '\\|');

/** ::error title=<套件>::<檢查>: <細節>；細節為空或檢查名稱已以冒號結尾時不重複加冒號 */
function errorAnnotation(suite, row) {
  const check = annotationText(row.check);
  if (!row.detail) return `::error title=${suite}::${check}`;
  const sep = /[:：]$/.test(row.check) ? ' ' : ': ';
  return `::error title=${suite}::${check}${sep}${annotationText(row.detail)}`;
}

function writeStepSummary(results, total) {
  const file = process.env.GITHUB_STEP_SUMMARY;
  if (!file) return;
  const passed = results.filter((r) => !r.failures).length;
  const lines = ['', '### UI 驗證結果', '', '| 套件 | 檢查項目 | 結果 | 說明 |', '| --- | --- | --- | --- |'];
  for (const r of results) {
    for (const row of rowsFor(r)) {
      lines.push(`| ${tableCell(r.name)} | ${tableCell(row.check)} | ${row.ok ? '✓ 通過' : '✗ 失敗'} | ${tableCell(row.detail)} |`);
    }
  }
  lines.push('', `**合計：** ${passed}/${results.length} 個套件通過，${total} 項失敗`, '');
  try {
    fs.appendFileSync(file, lines.join('\n'));
  } catch (e) {
    console.warn(`無法寫入 GITHUB_STEP_SUMMARY：${e.message}`);
  }
}

(async () => {
  const results = [];
  for (const [name, mod] of suites) results.push(await runSuite(name, mod));

  console.log(`\n${'='.repeat(46)}`);
  for (const r of results) console.log(`  ${r.failures ? '✗' : '✓'} ${r.name}${r.failures ? `（${r.failures} 項失敗）` : ''}`);
  const total = results.reduce((s, r) => s + r.failures, 0);
  console.log(`${'='.repeat(46)}`);

  for (const r of results) {
    for (const row of rowsFor(r)) {
      if (!row.ok) console.log(errorAnnotation(r.name, row));
    }
  }

  writeStepSummary(results, total);
  process.exit(total ? 1 : 0);
})();
