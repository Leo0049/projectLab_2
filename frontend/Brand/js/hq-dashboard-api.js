(async () => {
  BrandAPI.requireAuth();
  BrandAPI.renderAdminHeader();

  const dateEl = document.querySelector('[data-dashboard-date]');
  if (dateEl) {
    const now = new Date();
    dateEl.textContent = `今日營運狀態：${now.getFullYear()}年${now.getMonth()+1}月${now.getDate()}日`;
  }

  // ─── 更新 trend badge（替換整個 span，避免雙箭頭）────
  function updateTrendBadge(sel, value, isPercent, prefix) {
    const el = document.querySelector(sel);
    if (!el) return;
    const v = Number(value || 0);
    const up = v >= 0;
    const icon = up ? 'arrow_upward' : 'arrow_downward';
    const color = up ? 'text-green-500' : 'text-red-500';
    const bg    = up ? 'bg-green-50 dark:bg-green-900/20' : 'bg-red-50 dark:bg-red-900/20';
    const text  = isPercent
      ? `${up?'+':''}${Math.abs(v).toFixed(1)}% (對比昨日)`
      : `${prefix||''}${up?'+':'-'}${prefix==='$'?'':''}${Math.abs(v).toFixed(0)} (對比昨日)`;

    el.className = `flex items-center ${color} text-sm font-bold ${bg} px-2 py-1 rounded-lg`;
    el.innerHTML = `<span class="material-symbols-outlined text-sm mr-1">${icon}</span>${text}`;
  }

  try {
    const ov = await BrandAPI.getDashboardOverview();

    const revEl = document.querySelector('[data-dashboard-revenue]');
    if (revEl) revEl.textContent = `$ ${Number(ov.total_revenue||0).toLocaleString()}`;
    updateTrendBadge('[data-dashboard-revenue-badge]', ov.revenue_trend, true);

    const ordEl = document.querySelector('[data-dashboard-orders]');
    if (ordEl) ordEl.innerHTML = `${Number(ov.total_orders||0).toLocaleString()} <span class="text-lg font-normal text-slate-400">筆</span>`;
    updateTrendBadge('[data-dashboard-orders-badge]', ov.order_trend, true);

    const aovEl = document.querySelector('[data-dashboard-aov]');
    if (aovEl) aovEl.textContent = `$ ${Number(ov.avg_order_value||0).toFixed(0)}`;
    updateTrendBadge('[data-dashboard-aov-badge]', ov.aov_trend, false, '$');
  } catch(e) { console.error('概覽失敗', e); }

  try {
    const stores = await BrandAPI.getTopStores();
    const tbody = document.querySelector('[data-dashboard-top-stores]');
    if (tbody && Array.isArray(stores)) {
      tbody.innerHTML = '';
      stores.forEach((s, i) => {
        const rank = i + 1;
        let rankCell;
        if (rank === 1)      rankCell = `<span class="w-6 h-6 rounded-full bg-yellow-400 text-white inline-flex items-center justify-center text-xs font-bold">1</span>`;
        else if (rank === 2) rankCell = `<span class="w-6 h-6 rounded-full bg-slate-300 text-white inline-flex items-center justify-center text-xs font-bold">2</span>`;
        else                 rankCell = `<span class="w-6 h-6 inline-flex items-center justify-center text-xs font-bold text-slate-500">${rank}</span>`;
        const trendIcon = s.trend==='UP'
          ? `<span class="material-symbols-outlined text-green-500">arrow_upward</span>`
          : s.trend==='DOWN'
            ? `<span class="material-symbols-outlined text-red-500">arrow_downward</span>`
            : `<span class="material-symbols-outlined text-slate-400">horizontal_rule</span>`;
        tbody.insertAdjacentHTML('beforeend', `
          <tr class="hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
            <td class="px-6 py-4 whitespace-nowrap">${rankCell}</td>
            <td class="px-6 py-4 whitespace-nowrap text-sm font-semibold">${s.storeName||''}</td>
            <td class="px-6 py-4 whitespace-nowrap text-right text-sm font-bold">$ ${Number(s.revenue||0).toLocaleString()}</td>
            <td class="px-6 py-4 whitespace-nowrap text-center">${trendIcon}</td>
          </tr>`);
      });
    }
  } catch(e) { console.error('排行失敗', e); }

  try {
    const st = await BrandAPI.getStoreStatus();
    ['open','closed','total'].forEach(k => {
      const el = document.querySelector(`[data-dashboard-store-${k}]`);
      if (el) el.textContent = st[k] ?? 0;
    });
  } catch(e) { console.error('分店狀態失敗', e); }
})();
