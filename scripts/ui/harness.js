/**
 * UI 流程測試的共用工具：開瀏覽器、登入、截圖、蒐集前端錯誤。
 *
 * 環境變數：
 *   API_BASE      後端位址（預設 http://127.0.0.1:8082）
 *   FE_BASE       靜態前端位址（預設 http://127.0.0.1:5500）
 *   HEADED=1      有頭模式，方便本機肉眼看流程
 *   E2E_CDN_DIR   離線環境用：放 CDN 檔的資料夾，有設定才會攔截外部請求
 *   CHROME_PATH   自備 Chromium 執行檔路徑（沒設就用 Playwright 內建的）
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const API = process.env.API_BASE || 'http://127.0.0.1:8082';
const FE = process.env.FE_BASE || 'http://127.0.0.1:5500';
const SHOT_DIR = process.env.E2E_SHOT_DIR || path.join(__dirname, '..', '..', 'target', 'e2e-shots');

// 只有在離線環境（有提供 E2E_CDN_DIR）才需要把 CDN 換成本機檔案。
// 一般有網路的機器（含 GitHub Actions）直接讓它連出去即可。
const CDN_DIR = process.env.E2E_CDN_DIR || '';
const CDN_MAP = [
  ['cdn.tailwindcss.com', 'tailwind.js', 'application/javascript'],
  ['leaflet@1.9.4/dist/leaflet.js', 'leaflet.js', 'application/javascript'],
  ['leaflet@1.9.4/dist/leaflet.css', 'leaflet.css', 'text/css'],
  ['unpkg.com/lucide', 'lucide.js', 'application/javascript'],
  ['chart.js', 'chart.js', 'application/javascript'],
  ['sockjs', 'sockjs.js', 'application/javascript'],
  ['stomp', 'stomp.js', 'application/javascript'],
];

async function api(path, { method = 'GET', token, body } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = 'Bearer ' + token;
  const r = await fetch(API + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await r.text();
  try { return JSON.parse(text); } catch (e) { return { code: String(r.status), raw: text }; }
}

async function login(phone, password = 'demo1234') {
  const j = await api('/api/auth/login', { method: 'POST', body: { phone, password } });
  if (!j || !j.data || !j.data.token) throw new Error('登入失敗：' + JSON.stringify(j).slice(0, 200));
  return j.data;
}

/** 註冊一個新顧客（本機 sms.mode=mock，不需要 Firebase），回傳登入資訊 */
async function newCustomer(prefix, name) {
  const phone = prefix + String(Date.now()).slice(-6);
  await api('/api/auth/register', { method: 'POST', body: { phone, password: 'demo1234', name } });
  const u = await login(phone);
  u.phone = phone;
  return u;
}

async function corporateLogin(account, password = 'demo1234') {
  const j = await api('/api/auth/corporate-login', { method: 'POST', body: { account, password } });
  if (!j || !j.data || !j.data.token) throw new Error('後台登入失敗：' + JSON.stringify(j).slice(0, 200));
  return j.data;
}

async function launch() {
  return chromium.launch({
    headless: !process.env.HEADED,
    ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}),
  });
}

/**
 * 開一個已登入的瀏覽器分頁。
 * 回傳 { page, errors, shot }；errors 會累積 console 錯誤、未捕捉例外、
 * 4xx/5xx 的 API 回應，以及包在 200 裡的 code=500。
 */
async function newSession(browser, storage, { viewport = { width: 390, height: 844 }, label = 'shot' } = {}) {
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: 2, locale: 'zh-TW' });
  const errors = [];

  if (CDN_DIR) {
    await ctx.route('**/*', route => {
      const u = route.request().url();
      if (u.includes('fonts.googleapis.com')) return route.fulfill({ status: 200, contentType: 'text/css', body: '' });
      const hit = CDN_MAP.find(([m]) => u.includes(m));
      if (hit && fs.existsSync(path.join(CDN_DIR, hit[1])))
        return route.fulfill({ status: 200, contentType: hit[2], body: fs.readFileSync(path.join(CDN_DIR, hit[1])) });
      if (/^https?:\/\/(?!127\.0\.0\.1|localhost)/.test(u)) return route.abort();
      return route.continue();
    });
  }

  const page = await ctx.newPage();
  page.on('console', m => { if (m.type() === 'error') errors.push('[console] ' + m.text().slice(0, 180)); });
  page.on('pageerror', e => errors.push('[pageerror] ' + String(e).slice(0, 180)));
  page.on('response', async res => {
    if (!res.url().includes('/api/')) return;
    const p = res.url().replace(API, '').replace(FE, '');
    if (res.status() >= 400) { errors.push(`[http ${res.status()}] ${p}`); return; }
    try {
      if ((res.headers()['content-type'] || '').includes('json')) {
        const b = await res.json();
        if (b && (b.code === '500' || b.code === 500)) errors.push(`[body 500] ${p} ${String(b.msg).slice(0, 60)}`);
      }
    } catch (e) { /* 回應可能已被消費，略過 */ }
  });

  // localStorage 必須在同網域的文件上設定。
  // 這裡刻意指向一個不存在的路徑：靜態伺服器回的 404 頁沒有任何腳本，
  // 不會產生干擾。若改用真實頁面（例如 login.html），該頁自己的
  // Firebase 載入失敗會被算進待測頁面的錯誤清單裡。
  await page.goto(FE + '/__e2e_set_storage__', { waitUntil: 'domcontentloaded' }).catch(() => {});
  await page.evaluate(kv => { for (const [k, v] of Object.entries(kv)) localStorage.setItem(k, v); }, storage);

  let n = 0;
  const shot = async (name) => {
    n++;
    const file = path.join(SHOT_DIR, `${label}-${String(n).padStart(2, '0')}-${name}.png`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await page.screenshot({ path: file });
  };

  return { ctx, page, errors, shot };
}

/** 顧客登入後要塞進 localStorage 的內容 */
function customerStorage(u) {
  return {
    JOIN_TOKEN: u.token, JOIN_USER_ID: String(u.userId),
    token: u.token, userId: String(u.userId),
    userLat: '25.0336', userLng: '121.5435', userLocationName: '台北市大安區',
  };
}

/** 只把「專案自身」的錯誤留下：外部 CDN 在離線或受限網路下失敗不算 */
function projectErrors(errors) {
  const ignore = /cdnjs\.cloudflare|cdn\.jsdelivr|fonts\.googleapis|fonts\.gstatic|unpkg\.com|cdn\.tailwindcss|ERR_FAILED|Failed to load resource|net::ERR_/;
  return [...new Set(errors)].filter(e => !ignore.test(e));
}

module.exports = { API, FE, api, login, newCustomer, corporateLogin, launch, newSession, customerStorage, projectErrors };
