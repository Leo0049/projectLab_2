/**
 * 一次跑完所有 UI 驗證：全頁面普掃 + 三條主線流程。
 * 任何一項失敗就以非 0 結束，供 CI 判斷。
 *
 *   node scripts/ui/run-all.js
 */
const suites = [
  ['全頁面普掃', './page-sweep'],
  ['點餐流程', './flow-order'],
  ['轉盤流程', './flow-wheel'],
  ['揪團流程', './flow-group'],
];

(async () => {
  const results = [];
  for (const [name, mod] of suites) {
    let failures;
    try {
      failures = await require(mod)();
    } catch (e) {
      console.error(`\n  ✗ ${name} 執行中斷：${e && e.message}`);
      failures = 1;
    }
    results.push([name, failures]);
  }

  console.log(`\n${'='.repeat(46)}`);
  for (const [name, f] of results) console.log(`  ${f ? '✗' : '✓'} ${name}${f ? `（${f} 項失敗）` : ''}`);
  const total = results.reduce((s, [, f]) => s + f, 0);
  console.log(`${'='.repeat(46)}`);
  process.exit(total ? 1 : 0);
})();
