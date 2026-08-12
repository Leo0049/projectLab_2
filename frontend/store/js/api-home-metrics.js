/**
 * api-home-metrics.js — 首頁今日概覽指標
 * Stale-while-revalidate：先顯示持久快取，背景靜默更新
 */
(() => {
  const updateTrend = (key, trendPct) => {
    const badge = document.querySelector(`[data-home-trend="${key}"]`);
    if (!badge) return;

    const iconEl  = badge.querySelector('[data-home-trend-icon]');
    const valueEl = badge.querySelector('[data-home-trend-value]');
    const pct = Number(trendPct) || 0;

    badge.classList.remove(
      'text-green-500', 'bg-green-50',
      'text-red-500',   'bg-red-50',
      'text-slate-400', 'bg-slate-50'
    );

    if (pct > 0) {
      badge.classList.add('text-green-500', 'bg-green-50');
      if (iconEl)  iconEl.textContent  = 'trending_up';
      if (valueEl) valueEl.textContent = `+${Math.abs(pct).toFixed(1)}%`;
    } else if (pct < 0) {
      badge.classList.add('text-red-500', 'bg-red-50');
      if (iconEl)  iconEl.textContent  = 'trending_down';
      if (valueEl) valueEl.textContent = `−${Math.abs(pct).toFixed(1)}%`;
    } else {
      badge.classList.add('text-slate-400', 'bg-slate-50');
      if (iconEl)  iconEl.textContent  = 'trending_flat';
      if (valueEl) valueEl.textContent = '—';
    }
  };

  const renderHotRanking = (products) => {
    const container = document.getElementById('hotRankingList');
    if (!container) return;

    if (!products || products.length === 0) {
      container.innerHTML = '<p class="text-sm text-slate-400 font-bold text-center py-4">今日尚無銷售資料</p>';
      return;
    }

    const max = Math.max(...products.map(p => p.count), 1);
    container.innerHTML = products.map((p, i) => `
      <div class="flex items-center gap-3">
        <span class="w-5 text-xs font-black text-slate-400 italic shrink-0">${String(i + 1).padStart(2, '0')}</span>
        <div class="flex-1 min-w-0">
          <div class="flex items-center justify-between mb-1">
            <span class="text-sm font-bold text-slate-700 dark:text-slate-200 truncate">${p.productName}</span>
            <span class="text-xs font-black text-slate-900 dark:text-white ml-2 shrink-0">${p.count} 杯</span>
          </div>
          <div class="h-1.5 w-full rounded-full bg-slate-100 dark:bg-slate-700 overflow-hidden">
            <div class="h-full rounded-full bg-primary transition-all duration-500" style="width: ${(p.count / max) * 100}%"></div>
          </div>
        </div>
      </div>
    `).join('');
  };

  const applyMetrics = (res) => {
    if (!res) return;
    const revEl = document.querySelector('[data-home-metric="revenue"]');
    const ordEl = document.querySelector('[data-home-metric="orders"]');
    const aovEl = document.querySelector('[data-home-metric="aov"]');
    if (revEl) revEl.textContent = `$${Number(res.totalRevenue  || 0).toLocaleString()}`;
    if (ordEl) ordEl.textContent = res.orderCount ?? 0;
    if (aovEl) aovEl.textContent = `$${Number(res.avgOrderValue || 0).toLocaleString()}`;
    updateTrend('revenue', res.revenueTrend);
    updateTrend('orders',  res.orderTrend);
    updateTrend('aov',     res.aovTrend);
  };

  const init = async () => {
    // 1. 持久快取 → 立即顯示
    const snapDash     = window.StoreAPI.getDashboardSnapshot();
    const snapAnalytics = window.StoreAPI.getAnalyticsSnapshot();
    if (snapDash) applyMetrics(snapDash);
    if (snapAnalytics?.topProducts) renderHotRanking(snapAnalytics.topProducts);

    // 2. 背景靜默刷新
    try {
      const res = await window.StoreAPI.refreshDashboard();
      applyMetrics(res);
      window.StoreAPI.saveDashboardSnapshot(res);
    } catch (err) {
      if (!snapDash) console.error('[home-metrics]', err);
    }

    try {
      const analytics = await window.StoreAPI.refreshAnalytics();
      if (analytics?.topProducts) renderHotRanking(analytics.topProducts);
      window.StoreAPI.saveAnalyticsSnapshot(analytics);
    } catch (err) {
      if (!snapAnalytics) console.error('[home-hot-ranking]', err);
    }

    // 3. 每 5 分鐘定期刷新
    setInterval(async () => {
      try {
        const res = await window.StoreAPI.refreshDashboard();
        applyMetrics(res);
        window.StoreAPI.saveDashboardSnapshot(res);
      } catch {}
    }, 5 * 60 * 1000);
  };

  init();
})();
