#!/usr/bin/env node
/**
 * 離線語法檢查：抽出 HTML 內嵌的 <script>（不含 src=）逐段用 vm.Script 解析。
 *
 * 為什麼需要這支：本專案的顧客／門市頁面把主要邏輯寫在 HTML 的 inline script 裡，
 * 只跑 node --check 掃 frontend 底下的 .js 檢查不到這些程式碼（2026-10-11 複審時發現，
 * 改了 inline script 卻沒有工具能驗證語法）。vm.Script 只做 parse，不執行，
 * 因此不需要瀏覽器、不會碰到 DOM。
 *
 * 用法：node scripts/ui/check-inline-scripts.js <file.html> [more.html ...]
 * 不給參數時檢查 frontend/ 下所有 HTML。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..', '..');

function listHtml(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'vendor' || entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) listHtml(full, out);
    else if (entry.name.endsWith('.html')) out.push(full);
  }
  return out;
}

const targets = process.argv.slice(2).length
  ? process.argv.slice(2).map(f => path.resolve(f))
  : listHtml(path.join(ROOT, 'frontend'));

let scripts = 0;
const failures = [];

for (const file of targets) {
  if (!fs.existsSync(file)) {
    failures.push(`${file}: 找不到檔案`);
    continue;
  }
  const html = fs.readFileSync(file, 'utf8');
  const re = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi;
  let match;
  let index = 0;
  while ((match = re.exec(html)) !== null) {
    index += 1;
    scripts += 1;
    const code = match[1];
    // 型別不是 JS 的（例如 application/ld+json）不需要檢查
    const openTag = match[0].slice(0, match[0].indexOf('>'));
    if (/type\s*=\s*["'](?!text\/javascript|module)/i.test(openTag)) continue;
    try {
      new vm.Script(code, { filename: `${path.relative(ROOT, file)}#script${index}` });
    } catch (err) {
      failures.push(`${path.relative(ROOT, file)} (第 ${index} 段 inline script): ${err.message}`);
    }
  }
}

console.log(`inline scripts checked: ${scripts} in ${targets.length} html file(s)`);
if (failures.length) {
  console.error('語法錯誤：');
  for (const f of failures) console.error(` - ${f}`);
  process.exit(1);
}
console.log('OK');
