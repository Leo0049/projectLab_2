/**
 * hq-reports-api.js — 全局報表（熱銷/滯銷品項）API 串接
 */
(async () => {
  BrandAPI.requireAuth();
  BrandAPI.renderAdminHeader();

  let currentPeriod = 'month';

  async function load(period) {
    try {
      const data = await BrandAPI.getProductRanking(period);

      const hotTbody  = document.querySelector('[data-hot-tbody]');
      const slowTbody = document.querySelector('[data-slow-tbody]');

      if (hotTbody) {
        hotTbody.innerHTML = '';
        (data.hot||[]).forEach(p => {
          hotTbody.insertAdjacentHTML('beforeend', `
            <tr class="hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-colors">
              <td class="px-6 py-4">
                <div class="flex items-center gap-3">
                  <span class="w-6 text-center font-bold ${p.rank<=3?'text-primary':''}">${String(p.rank).padStart(2,'0')}</span>
                  <span class="font-medium">${p.productName||''}</span>
                </div>
              </td>
              <td class="px-6 py-4 text-center font-bold tabular-nums">${Number(p.count||0).toLocaleString()}</td>
              <td class="px-6 py-4 text-right">
                <span class="inline-flex items-center justify-end gap-0.5 ${(p.trend||0)>=0?'text-green-600':'text-rose-500'} font-black tabular-nums">
                  <span class="material-symbols-outlined" style="font-size:14px;vertical-align:middle;line-height:1">${(p.trend||0)>=0?'arrow_upward':'arrow_downward'}</span>
                  <span style="vertical-align:middle">${Math.abs(p.trend||0).toFixed(1)}%</span>
                </span>
              </td>
            </tr>`);
        });
        if ((data.hot||[]).length===0)
          hotTbody.innerHTML = `<tr><td colspan="3" class="px-6 py-8 text-center text-sm text-slate-400">此期間無銷售數據</td></tr>`;
      }

      if (slowTbody) {
        slowTbody.innerHTML = '';
        (data.slow||[]).forEach(p => {
          slowTbody.insertAdjacentHTML('beforeend', `
            <tr class="hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-colors">
              <td class="px-6 py-4">
                <div class="flex items-center gap-3">
                  <span class="w-6 text-center font-bold text-rose-500">${String(p.rank).padStart(2,'0')}</span>
                  <span class="font-medium">${p.productName||''}</span>
                </div>
              </td>
              <td class="px-6 py-4 text-center font-bold text-rose-500">${Number(p.count||0).toLocaleString()}</td>
            </tr>`);
        });
        if ((data.slow||[]).length===0)
          slowTbody.innerHTML = `<tr><td colspan="2" class="px-6 py-8 text-center text-sm text-slate-400">此期間無銷售數據</td></tr>`;
      }
    } catch(e) {
      console.error('載入報表失敗', e);
      BrandAPI.toast('載入報表失敗：'+e.message,'error');
    }
  }

  // 週期切換
  document.querySelectorAll('[data-report-period]').forEach(btn => {
    btn.addEventListener('click', async () => {
      currentPeriod = btn.dataset.reportPeriod;
      document.querySelectorAll('[data-report-period]').forEach(b => {
        const active = b === btn;
        b.classList.toggle('bg-primary', active);
        b.classList.toggle('text-white', active);
        b.classList.toggle('text-slate-600', !active);
        b.classList.toggle('dark:text-slate-300', !active);
      });
      await load(currentPeriod);
    });
  });

  await load(currentPeriod);
})();
