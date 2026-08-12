/**
 * hq-finance-api.js — 財務匯總頁面 API 串接
 * 負責：3張概況卡片、各區域營收表格
 * 趨勢圖由 hq-brand-financial-overview-revenue-trend.js 負責
 */
(async () => {
  BrandAPI.requireAuth();
  BrandAPI.renderAdminHeader();

  const fmt = n => `$ ${Number(n || 0).toLocaleString()}`;

  function setEl(sel, val) {
    const el = document.querySelector(sel);
    if (el) el.textContent = val;
  }

  function renderTrend(wrapSel, iconSel, trendSel, trendVal) {
    const wrap = document.querySelector(wrapSel);
    const icon = document.querySelector(iconSel);
    const span = document.querySelector(trendSel);
    if (!wrap || !icon || !span) return;
    const up = trendVal >= 0;
    wrap.className = wrap.className.replace(/text-\w+-\d+/g, '').trim()
      + ` text-${up ? 'emerald' : 'red'}-500`;
    icon.textContent = up ? 'trending_up' : 'trending_down';
    span.textContent = `${up ? '+' : ''}${trendVal.toFixed(1)}%`;
  }

  function renderRegionTable(regions) {
    const tbody = document.querySelector('[data-finance-region-tbody]');
    if (!tbody) return;

    if (!regions || regions.length === 0) {
      tbody.innerHTML = `<tr><td colspan="5" class="px-6 py-8 text-center text-sm text-slate-400">此期間無資料</td></tr>`;
      setEl('[data-finance-region-total-stores]', '0');
      setEl('[data-finance-region-total-rev]', '$ 0');
      setEl('[data-finance-region-total-com]', '-$ 0');
      setEl('[data-finance-region-total-net]', '$ 0');
      return;
    }

    tbody.innerHTML = regions.map(r => `
      <tr class="hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors">
        <td class="px-6 py-4 font-bold text-center">${r.regionName}</td>
        <td class="px-6 py-4 text-slate-600 dark:text-slate-400 text-center tabular-nums">${Number(r.storeCount || 0)}</td>
        <td class="px-6 py-4 font-medium text-center tabular-nums whitespace-nowrap">${fmt(r.totalRevenue)}</td>
        <td class="px-6 py-4 text-slate-500 text-center tabular-nums whitespace-nowrap">-$ ${Number(r.commission || 0).toLocaleString()}</td>
        <td class="px-6 py-4 text-center font-bold text-slate-800 dark:text-slate-100 tabular-nums whitespace-nowrap">${fmt(r.netIncome)}</td>
      </tr>`).join('');

    // Footer totals
    const totalStores = regions.reduce((s, r) => s + Number(r.storeCount || 0), 0);
    const totalRev = regions.reduce((s, r) => s + Number(r.totalRevenue || 0), 0);
    const totalCom = regions.reduce((s, r) => s + Number(r.commission || 0), 0);
    const totalNet = regions.reduce((s, r) => s + Number(r.netIncome || 0), 0);
    setEl('[data-finance-region-total-stores]', totalStores);
    setEl('[data-finance-region-total-rev]', fmt(totalRev));
    setEl('[data-finance-region-total-com]', `-$ ${totalCom.toLocaleString()}`);
    setEl('[data-finance-region-total-net]', fmt(totalNet));
  }

  // 摘要卡片：固定顯示當月資料
  async function loadSummaryCards() {
    try {
      const overview = await BrandAPI.getFinanceOverview('month');
      const now = new Date();
      const monthLabel = `${now.getFullYear()} 年 ${now.getMonth() + 1} 月`;

      // 標題副標（若有 data-finance-period-label 元素）
      setEl('[data-finance-period-label]', monthLabel);

      setEl('[data-finance-total]', fmt(overview.totalRevenue));
      renderTrend(
        '[data-finance-rev-trend-wrap]',
        '[data-finance-rev-trend-icon]',
        '[data-finance-rev-trend]',
        Number(overview.revenueTrend || 0)
      );

      setEl('[data-finance-commission]', `-$ ${Number(overview.commission || 0).toLocaleString()}`);

      setEl('[data-finance-net-income]', fmt(overview.netIncome));
      renderTrend(
        '[data-finance-net-trend-wrap]',
        '[data-finance-net-trend-icon]',
        '[data-finance-net-trend]',
        Number(overview.netIncomeTrend || 0)
      );
    } catch (e) {
      console.error('載入財務摘要失敗', e);
      BrandAPI.toast('載入財務摘要失敗：' + e.message, 'error');
    }
  }

  // 區域表格：跟隨 period 按鈕
  async function loadRegionTable(period) {
    try {
      const regions = await BrandAPI.getFinanceRegions(period);
      renderRegionTable(regions);
    } catch (e) {
      console.error('載入區域財務資料失敗', e);
    }
  }

  // 週期按鈕只影響區域表格（與趨勢圖共用同一組按鈕）
  let currentPeriod = 'month';
  document.querySelectorAll('[data-hq-revenue-trend-range]').forEach(btn => {
    btn.addEventListener('click', async () => {
      currentPeriod = btn.dataset.hqRevenueTrendRange;
      await loadRegionTable(currentPeriod);
    });
  });

  await Promise.all([loadSummaryCards(), loadRegionTable(currentPeriod)]);
})();
