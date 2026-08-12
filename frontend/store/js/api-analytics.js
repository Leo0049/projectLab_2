/**
 * api-analytics.js — 數據報表 API 串接
 * Stale-while-revalidate：先顯示持久快取，背景靜默更新
 */
(() => {
    const fmt = (n) => n == null ? '$0' : `$${Number(n).toLocaleString()}`;

    const updateText = (selector, val) => {
        const el = document.querySelector(selector);
        if (el) el.textContent = val;
    };

    const applyDashboard = (summary) => {
        if (!summary) return;
        updateText('[data-report-revenue]', fmt(summary.totalRevenue));
        updateText('[data-report-orders]',  summary.orderCount || 0);
        updateText('[data-report-aov]',     fmt(summary.avgOrderValue));
    };

    // 渲染時段分佈
    const renderHourlySales = (hourlyData) => {
        const container = document.querySelector('[data-analytics-hourly]');
        if (!container || !hourlyData) return;
        const maxCount = Math.max(...hourlyData.map(h => h.count), 1);
        container.innerHTML = hourlyData.map(h => `
            <div class="flex items-center gap-4">
                <span class="w-20 text-xs font-bold text-slate-500">${h.hour}:00 - ${Number(h.hour)+1}:00</span>
                <div class="h-2 flex-1 rounded-full bg-slate-100 dark:bg-slate-800 overflow-hidden">
                    <div class="h-full rounded-full bg-primary" style="width: ${(h.count/maxCount)*100}%"></div>
                </div>
                <span class="w-8 text-right text-xs font-bold text-slate-700 dark:text-slate-300">${h.count}單</span>
            </div>
        `).join('');
    };

    // 渲染今日排行小列表
    const renderMiniTopProducts = (products) => {
        const container = document.querySelector('[data-analytics-products]');
        if (!container || !products) return;
        container.innerHTML = products.map((p, i) => `
            <div class="flex items-center justify-between p-3 hover:bg-slate-50 dark:hover:bg-slate-800 rounded-xl transition-colors">
                <div class="flex items-center gap-3">
                    <span class="w-6 text-sm font-black text-slate-300 italic">${String(i+1).padStart(2, '0')}</span>
                    <span class="text-sm font-bold text-slate-700 dark:text-slate-200">${p.productName}</span>
                </div>
                <div class="text-right">
                    <p class="text-xs font-bold text-slate-900 dark:text-white">${p.count} 杯</p>
                    <p class="text-[10px] text-slate-400 font-medium">${fmt(p.revenue)}</p>
                </div>
            </div>
        `).join('');
    };

    // 渲染完整排行
    const renderFullRanking = (data) => {
        const hotContainer  = document.getElementById('hotSellersTableBody');
        const slowContainer = document.getElementById('lowSellersTableBody');
        if (hotContainer) {
            hotContainer.innerHTML = data.slice(0, 10).map((p, i) => `
                <tr class="hover:bg-primary/5 transition-colors">
                    <td class="px-6 py-4">
                        <span class="flex h-6 w-6 items-center justify-center rounded-full ${i < 3 ? 'bg-primary text-white' : 'bg-slate-100 text-slate-500'} text-xs font-bold">${i + 1}</span>
                    </td>
                    <td class="px-6 py-4 font-semibold text-slate-700 dark:text-slate-200">${p.productName}</td>
                    <td class="px-6 py-4 text-right font-bold text-primary">${p.count}</td>
                </tr>
            `).join('');
        }
        if (slowContainer) {
            const slowData = [...data].reverse().slice(0, 10);
            slowContainer.innerHTML = slowData.map((p, i) => `
                <tr class="hover:bg-red-50/50 dark:hover:bg-red-900/10 transition-colors">
                    <td class="px-6 py-4">
                        <span class="flex h-6 w-6 items-center justify-center rounded-full bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-400 text-xs font-bold">${i + 1}</span>
                    </td>
                    <td class="px-6 py-4 font-medium text-slate-700 dark:text-slate-300">${p.productName}</td>
                    <td class="px-6 py-4 text-right font-bold text-slate-900 dark:text-white">${p.count}</td>
                </tr>
            `).join('');
        }
    };

    // 渲染近期營收明細
    const renderRecentReportsTable = (reports) => {
        const tbody = document.getElementById('recentRevenueTableBody');
        if (!tbody || !reports) return;
        if (reports.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" class="px-3 md:px-6 py-8 md:py-10 text-center text-slate-400 font-bold">目前尚無歷史營收數據</td></tr>';
            return;
        }
        tbody.innerHTML = reports.map(r => `
            <tr class="hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
                <td class="px-3 md:px-6 py-3 md:py-4 font-bold text-xs md:text-sm text-slate-700 dark:text-slate-300 whitespace-nowrap">${r.date}</td>
                <td class="px-3 md:px-6 py-3 md:py-4 font-black text-xs md:text-sm text-primary whitespace-nowrap">${fmt(r.revenue)}</td>
                <td class="px-3 md:px-6 py-3 md:py-4 font-bold text-xs md:text-sm text-slate-600 dark:text-slate-400 whitespace-nowrap">${r.orderCount} 筆</td>
                <td class="px-3 md:px-6 py-3 md:py-4 text-xs md:text-sm text-slate-500 whitespace-nowrap">${fmt(r.avgOrderValue)}</td>
            </tr>
        `).join('');
    };

    const init = async () => {
        const isOverviewPage = !!document.getElementById('overviewReportContainer');
        const isRankingPage  = !!document.getElementById('hotSellersTableBody');

        // 1. 持久快取 → 立即顯示
        const snapDash     = window.StoreAPI.getDashboardSnapshot();
        const snapAnalytics = window.StoreAPI.getAnalyticsSnapshot();
        const snapReports  = window.StoreAPI.getRecentReportsSnapshot();
        const snapRanking  = window.StoreAPI.getProductRankingSnapshot();

        if (snapDash) applyDashboard(snapDash);
        if (isOverviewPage) {
            if (snapAnalytics) {
                renderHourlySales(snapAnalytics.hourlySales);
                renderMiniTopProducts(snapAnalytics.topProducts);
            }
            if (snapReports) renderRecentReportsTable(snapReports);
        }
        if (isRankingPage && snapRanking) renderFullRanking(snapRanking);

        // 2. 背景靜默刷新
        try {
            const summary = await window.StoreAPI.refreshDashboard();
            if (summary) {
                applyDashboard(summary);
                window.StoreAPI.saveDashboardSnapshot(summary);
            }
        } catch (err) {
            if (!snapDash) console.error('[Analytics] summary 載入失敗:', err);
        }

        if (isOverviewPage) {
            try {
                const analytics = await window.StoreAPI.refreshAnalytics();
                if (analytics) {
                    renderHourlySales(analytics.hourlySales);
                    renderMiniTopProducts(analytics.topProducts);
                    window.StoreAPI.saveAnalyticsSnapshot(analytics);
                }
            } catch (err) {
                if (!snapAnalytics) console.error('[Analytics] analytics 載入失敗:', err);
            }
            try {
                const recentReports = await window.StoreAPI.refreshRecentReports();
                if (recentReports) {
                    renderRecentReportsTable(recentReports);
                    window.StoreAPI.saveRecentReportsSnapshot(recentReports);
                }
            } catch (err) {
                if (!snapReports) console.error('[Analytics] recentReports 載入失敗:', err);
            }
        }

        if (isRankingPage) {
            try {
                const rankingData = await window.StoreAPI.refreshProductRanking();
                if (rankingData) {
                    renderFullRanking(rankingData);
                    window.StoreAPI.saveProductRankingSnapshot(rankingData);
                }
            } catch (err) {
                if (!snapRanking) console.error('[Analytics] ranking 載入失敗:', err);
            }
        }

        // 最後移除隱藏狀態
        const root = document.getElementById('mainContentFade');
        if (root) root.classList.replace('invisible', 'visible');
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
