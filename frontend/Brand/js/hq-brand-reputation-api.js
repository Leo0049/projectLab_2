/**
 * hq-brand-reputation-api.js — 全品牌口碑監控 動態資料載入
 */
(async () => {
  BrandAPI.requireAuth();
  BrandAPI.renderAdminHeader();

  // ─── 星星渲染工具 ──────────────────────────────────────────
  function computeStars(score) {
    const s = Math.max(0, Math.min(5, Number(score) || 0));
    const full = Math.floor(s), rest = s - full;
    let fullFinal = full, hasHalf = false;
    if (rest >= 0.75) fullFinal++;
    else if (rest >= 0.25) hasHalf = true;
    const empty = Math.max(0, 5 - fullFinal - (hasHalf ? 1 : 0));
    return { full: Math.min(5, fullFinal), half: hasHalf ? 1 : 0, empty };
  }

  function renderStarRow(container, score) {
    if (!container) return;
    container.innerHTML = '';
    const { full, half, empty } = computeStars(score);
    for (let i = 0; i < full; i++) container.insertAdjacentHTML('beforeend',
      `<span class="material-symbols-outlined filled-icon text-lg text-primary">star</span>`);
    if (half) container.insertAdjacentHTML('beforeend',
      `<span class="material-symbols-outlined text-lg text-primary">star_half</span>`);
    for (let i = 0; i < empty; i++) container.insertAdjacentHTML('beforeend',
      `<span class="material-symbols-outlined text-lg text-slate-300 dark:text-slate-700">star</span>`);
  }

  // ─── 更新摘要卡片 ──────────────────────────────────────────
  function updateSummaryCard(data) {
    const avg = Number(data.avgRating || 0);
    const el = document.querySelector('[data-rep-avg]');
    if (el) el.textContent = avg.toFixed(1);

    renderStarRow(document.querySelector('[data-rep-stars]'), avg);

    const lbl = document.querySelector('[data-rep-period-label]');
    if (lbl) lbl.textContent = '累計評分';

    const countEl = document.querySelector('[data-rep-count]');
    if (countEl) countEl.textContent = Number(data.totalReviewCount || 0).toLocaleString();

    const rateEl = document.querySelector('[data-rep-rate]');
    if (rateEl) {
      const completed = Number(data.totalCompletedOrders || 0);
      const reviewed  = Number(data.totalReviewCount || 0);
      const rate = completed > 0 ? Math.round((reviewed / completed) * 100) : 0;
      rateEl.textContent = rate + '%';
    }
  }

  // ─── 更新星級分佈 ─────────────────────────────────────────
  function updateStarDistribution(dist) {
    if (!dist) return;
    const total = [1,2,3,4,5].reduce((s, star) => s + Number(dist[String(star)] || 0), 0);
    for (let star = 1; star <= 5; star++) {
      const count = Number(dist[String(star)] || 0);
      const pct = total > 0 ? Math.round((count / total) * 100) : 0;
      const bar = document.querySelector(`[data-rep-dist-bar="${star}"]`);
      const pctEl = document.querySelector(`[data-rep-dist-pct="${star}"]`);
      if (bar) bar.style.width = pct + '%';
      if (pctEl) pctEl.textContent = pct + '%';
    }
  }

  // ─── 更新最低評分分店表格 ──────────────────────────────────
  function updateLowRatedTable(stores) {
    const tbody = document.querySelector('table tbody');
    if (!tbody) return;
    if (!stores || stores.length === 0) {
      tbody.innerHTML = `<tr><td colspan="3" class="px-6 py-8 text-center text-sm text-slate-400">目前無評分數據</td></tr>`;
      return;
    }
    tbody.innerHTML = '';
    stores.forEach(s => {
      const rating = Number(s.avgRating || 0);
      const colorCls = rating < 3 ? 'text-red-500' : 'text-orange-500';
      const reviewCount = Number(s.reviewCount || 0);
      const distJson = JSON.stringify(s.ratingDistribution || {}).replace(/"/g, '&quot;');
      tbody.insertAdjacentHTML('beforeend', `
        <tr class="hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-colors"
            data-store-name="${(s.storeName||'').replace(/"/g,'&quot;')}"
            data-store-rating="${rating.toFixed(1)}"
            data-store-review-count="${reviewCount}"
            data-store-dist="${distJson}">
          <td class="px-6 py-4 font-bold text-sm">${s.storeName || '-'}</td>
          <td class="px-6 py-4 text-center">
            <div class="flex items-center justify-center gap-1 ${colorCls} font-bold">
              <span>${rating.toFixed(1)}</span>
              <span class="material-symbols-outlined text-sm filled-icon">star</span>
            </div>
          </td>
          <td class="px-6 py-4 text-right">
            <button class="p-2 rounded-full hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                    type="button" aria-label="查看評分詳情" data-hq-low-rating-open>
              <span class="material-symbols-outlined text-slate-400">chevron_right</span>
            </button>
          </td>
        </tr>`);
    });
  }

  // ─── 更新區域滿意度 ───────────────────────────────────────
  function updateRegionBars(regions) {
    const container = document.querySelector('.flex-1.flex.flex-col.justify-around');
    if (!container || !regions || regions.length === 0) return;
    const labels = ['北部區域', '中部區域', '南部區域', '東部區域'];
    const maxRating = 5;

    container.innerHTML = '';
    regions.slice(0, 4).forEach((r, idx) => {
      const pct = Math.round((r.avgRating / maxRating) * 100);
      const rating = Number(r.avgRating || 0).toFixed(1);
      const label = r.regionName || labels[idx] || `區域 ${idx + 1}`;
      const satText = pct >= 85 ? '滿意' : pct >= 75 ? '良好' : '需加強';
      const opacityCls = idx === 0 ? 'bg-primary/80' : idx === 1 ? 'bg-primary/60' : idx === 2 ? 'bg-primary/40' : 'bg-primary/30';
      container.insertAdjacentHTML('beforeend', `
        <div class="space-y-2">
          <div class="flex justify-between text-sm">
            <span class="font-medium">${label}</span>
            <span class="text-primary font-bold">${rating}</span>
          </div>
          <div class="h-6 bg-slate-100 dark:bg-slate-800 rounded-lg overflow-hidden relative">
            <div class="absolute inset-y-0 left-0 ${opacityCls} rounded-r-lg" style="width:${pct}%"></div>
            <span class="absolute inset-0 flex items-center px-3 text-[10px] font-bold text-white uppercase tracking-wider">${satText}</span>
          </div>
        </div>`);
    });
  }

  // ─── 載入資料 ─────────────────────────────────────────────
  async function load() {
    try {
      const data = await BrandAPI.getBrandReputation();
      window._brandReputationData = data;
      updateSummaryCard(data);
      updateStarDistribution(data.ratingDistribution || {});
      updateLowRatedTable(data.lowRatedStores || []);
      updateRegionBars(data.regionSatisfaction || []);
    } catch (e) {
      console.error('載入口碑資料失敗', e);
      BrandAPI.toast('載入口碑資料失敗：' + e.message, 'error');
    }
  }

  await load();
})();
