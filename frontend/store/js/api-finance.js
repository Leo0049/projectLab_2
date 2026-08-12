/**
 * api-finance.js — 財務管理收入總覽
 * Stale-while-revalidate：先顯示持久快取，背景靜默更新
 */
(() => {
    let pieChart   = null;
    let trendChart = null;

    const fmt = (n) => `$${Number(n || 0).toLocaleString()}`;

    const updateText = (selector, val) => {
        const el = document.querySelector(selector);
        if (el) el.textContent = val;
    };

    const RAINBOW_COLORS = ['#ef4444', '#f97316', '#f59e0b', '#10b981', '#3b82f6', '#6366f1', '#8b5cf6'];

    const renderPieChart = (categories) => {
        const canvas = document.getElementById('categoryPieChart');
        if (!canvas) return;
        const ctx    = canvas.getContext('2d');
        const labels = categories.map(c => c.name);
        const values = categories.map(c => c.revenue);
        const colors = labels.map((_, i) => RAINBOW_COLORS[i % RAINBOW_COLORS.length]);
        if (pieChart) pieChart.destroy();
        pieChart = new Chart(ctx, {
            type: 'doughnut',
            data: { labels, datasets: [{ data: values, backgroundColor: colors, borderWidth: 0, hoverOffset: 10 }] },
            options: {
                responsive: true, maintainAspectRatio: false,
                plugins: {
                    legend: { display: false },
                    tooltip: { callbacks: { label: (ctx) => ` ${ctx.label}: ${fmt(ctx.parsed)}` } }
                },
                cutout: '70%'
            }
        });
    };

    const renderCategoryList = (categories) => {
        const tableBody = document.getElementById('categorizedRevenueTableBody');
        if (!tableBody) return;
        
        tableBody.innerHTML = categories.map((c, index) => {
            const color    = RAINBOW_COLORS[index % RAINBOW_COLORS.length];
            const rev      = Number(c.revenue || 0);
            const count    = Number(c.orderCount || 0);
            const discount = Number(c.discountAmount || 0);
            const trend    = Number(c.trend || 0);
            const commission = Math.round(rev * 0.3);
            const net      = rev - commission;
            const trendClass = trend > 0 ? 'text-green-500' : trend < 0 ? 'text-red-500' : 'text-slate-400';
            const trendIcon  = trend > 0 ? 'trending_up' : trend < 0 ? 'trending_down' : 'flatware';
            const trendText  = trend === 0 ? '—' : `${trend > 0 ? '+' : ''}${trend}%`;
            return `
                <tr data-revenue-detail-row data-revenue-category="${c.name}">
                  <td class="px-2 md:px-8 py-3 md:py-4 align-middle text-left">
                    <div class="flex items-center gap-2 md:gap-3 md:pl-4">
                      <div class="hidden md:flex size-8 rounded items-center justify-center flex-shrink-0" style="background-color: ${color}20; color: ${color};">
                        <i class="bi bi-cup-straw text-base"></i>
                      </div>
                      <span class="inline-flex items-center gap-1 md:gap-2 whitespace-nowrap">
                        <span style="color: ${color}" aria-hidden="true"><span class="inline-block w-2 h-2 md:w-2.5 md:h-2.5 rounded-full bg-current"></span></span>
                        <span class="text-xs md:text-sm font-bold text-slate-700 dark:text-slate-200">${c.name}</span>
                      </span>
                    </div>
                  </td>
                  <td class="px-2 md:px-8 py-3 md:py-4 text-xs md:text-sm font-black align-middle text-center text-slate-900 dark:text-white"><span data-revenue-detail-sales>$${rev.toLocaleString()}</span></td>
                  <td class="px-2 md:px-8 py-3 md:py-4 text-xs md:text-sm font-bold text-slate-500 dark:text-slate-400 align-middle text-center"><span data-revenue-detail-orders>${count.toLocaleString()}</span></td>
                  <td class="hidden md:table-cell px-8 py-4 text-sm font-bold text-green-600 align-middle text-center"><span data-revenue-detail-discount>-$${discount.toLocaleString()}</span></td>
                  <td class="px-2 md:px-8 py-3 md:py-4 text-xs md:text-sm font-black align-middle text-center text-green-600 dark:text-green-400"><span data-revenue-detail-net>$${net.toLocaleString()}</span></td>
                  <td class="px-2 md:px-8 py-3 md:py-4 text-center align-middle">
                    <div class="flex items-center justify-center gap-1 ${trendClass} font-black text-xs">
                        ${trend !== 0 ? `<span class="material-symbols-outlined text-xs">${trendIcon}</span>` : ''}
                        <span data-revenue-detail-trend>${trendText}</span>
                    </div>
                  </td>
                </tr>`;
        }).join('');
    };

    const renderTrendChart = (trendData) => {
        const canvas = document.getElementById('financeTrendChart');
        if (!canvas) return;
        const ctx    = canvas.getContext('2d');
        if (trendChart) trendChart.destroy();
        const datasets = trendData.series.map((s, i) => {
            const color = RAINBOW_COLORS[i % RAINBOW_COLORS.length];
            return {
                label: s.name, data: s.data,
                borderColor: color,
                backgroundColor: color + '20',
                fill: true, tension: 0.4, pointRadius: 4, pointHoverRadius: 6
            };
        });
        trendChart = new Chart(ctx, {
            type: 'line',
            data: { labels: trendData.labels, datasets },
            options: {
                responsive: true, maintainAspectRatio: false,
                interaction: { mode: 'index', intersect: false },
                scales: {
                    y: { beginAtZero: true, grid: { color: 'rgba(0,0,0,0.05)' }, ticks: { font: { size: 10 }, callback: (val) => '$' + val.toLocaleString() } },
                    x: { grid: { display: false }, ticks: { font: { size: 10, weight: 'bold' } } }
                },
                plugins: {
                    legend: { display: true, position: 'top', labels: { boxWidth: 12, usePointStyle: true, font: { size: 11 } } },
                    tooltip: { callbacks: { label: (ctx) => ` ${ctx.dataset.label}: ${fmt(ctx.parsed.y)}` } }
                }
            }
        });
    };

    const applyCategorized = (data) => {
        if (!data) return;
        updateText('[data-finance-total]',      fmt(data.totalRevenue));
        updateText('[data-finance-commission]', fmt(data.commission));
        updateText('[data-finance-net]',        fmt(data.netIncome));
        renderPieChart(data.categories);
        renderCategoryList(data.categories);
    };

    const init = async () => {
        const container = document.getElementById('financeOverviewContainer');
        if (!container) return;

        // 1. 持久快取 → 立即顯示
        const snapCat   = window.StoreAPI.getFinanceCategorizedSnapshot();
        const snapTrend = window.StoreAPI.getFinanceTrendSnapshot();
        if (snapCat)   applyCategorized(snapCat);
        if (snapTrend) renderTrendChart(snapTrend);
        if (snapCat || snapTrend) container.classList.remove('opacity-0');

        // 2. 背景靜默刷新
        try {
            const data = await window.StoreAPI.refreshFinanceCategorized();
            if (data) {
                applyCategorized(data);
                window.StoreAPI.saveFinanceCategorizedSnapshot(data);
                container.classList.remove('opacity-0');
            }
        } catch (err) {
            if (!snapCat) console.error('[Finance] 分類載入失敗:', err);
        }

        try {
            const trendData = await window.StoreAPI.refreshFinanceTrend();
            if (trendData) {
                renderTrendChart(trendData);
                window.StoreAPI.saveFinanceTrendSnapshot(trendData);
                container.classList.remove('opacity-0');
            }
        } catch (err) {
            if (!snapTrend) console.error('[Finance] 趨勢載入失敗:', err);
        }

        container.classList.remove('opacity-0');
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
