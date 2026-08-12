/**
 * api-finance-commissions.js — 平台抽成明細實作
 */
(() => {
    const init = async () => {
        const container   = document.getElementById('commissionContainer');
        const tableBody   = document.getElementById('commissionTableBody');
        const cardList    = document.getElementById('commissionCardList');
        const periodFilter = document.getElementById('periodFilter');

        if (!container || !tableBody) return;

        const LOADING_TABLE = '<tr><td colspan="6" class="px-6 py-12 text-center text-slate-400 font-bold">資料讀取中...</td></tr>';
        const LOADING_CARD  = '<div class="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm p-8 text-center text-slate-400 font-bold text-sm">資料讀取中...</div>';
        const EMPTY_TABLE   = '<tr><td colspan="6" class="px-6 py-12 text-center text-slate-400 font-bold">此期間尚無抽成明細數據</td></tr>';
        const EMPTY_CARD    = '<div class="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm p-8 text-center text-slate-400 font-bold text-sm">此期間尚無抽成明細數據</div>';
        const ERROR_TABLE   = '<tr><td colspan="6" class="px-6 py-12 text-center text-red-400 font-bold">資料載入失敗，請稍後再試</td></tr>';
        const ERROR_CARD    = '<div class="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm p-8 text-center text-red-400 font-bold text-sm">資料載入失敗，請稍後再試</div>';

        const loadData = async (period = 'month') => {
            tableBody.innerHTML = LOADING_TABLE;
            if (cardList) cardList.innerHTML = LOADING_CARD;

            try {
                const data = await window.StoreAPI.getFinanceCommissions(period);

                if (!data || data.length === 0) {
                    tableBody.innerHTML = EMPTY_TABLE;
                    if (cardList) cardList.innerHTML = EMPTY_CARD;
                    return;
                }

                renderTable(data);
            } catch (err) {
                console.error('[Commissions] 載入失敗:', err);
                tableBody.innerHTML = ERROR_TABLE;
                if (cardList) cardList.innerHTML = ERROR_CARD;
            }
        };

        const formatDate = (createdAt) => {
            if (!createdAt) return '—';
            const d = new Date(createdAt);
            return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        };

        const renderTable = (list) => {
            // ── 桌面表格 ──────────────────────────
            tableBody.innerHTML = list.map(item => {
                const total      = Number(item.amount || 0);
                const commission = Math.round(total * 0.3);
                const net        = total - commission;
                const dateStr    = formatDate(item.createdAt);

                return `
                    <tr class="hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-colors">
                        <td class="px-6 py-5 align-middle text-center">
                            <span class="font-bold text-slate-900 dark:text-white">#${item.orderNo || item.orderId}</span>
                        </td>
                        <td class="px-6 py-5 text-sm text-slate-600 dark:text-slate-400 align-middle text-center">${dateStr}</td>
                        <td class="px-6 py-5 text-sm font-medium text-slate-900 dark:text-white text-center align-middle">$${total.toLocaleString()}</td>
                        <td class="px-6 py-5 text-sm font-medium text-primary text-center align-middle">-$${commission.toLocaleString()}</td>
                        <td class="px-6 py-5 text-sm font-bold text-green-600 dark:text-green-400 text-center align-middle">$${net.toLocaleString()}</td>
                        <td class="px-6 py-5 align-middle text-center">
                            <div class="flex justify-center">${renderStatusBadge('COMPLETED')}</div>
                        </td>
                    </tr>
                `;
            }).join('');

            // ── 行動卡片 ──────────────────────────
            if (!cardList) return;
            cardList.innerHTML = list.map(item => {
                const total      = Number(item.amount || 0);
                const commission = Math.round(total * 0.3);
                const net        = total - commission;
                const dateStr    = formatDate(item.createdAt);

                return `
                    <div class="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm overflow-hidden">
                        <div class="px-4 py-3 flex items-center justify-between border-b border-slate-100 dark:border-slate-800">
                            <span class="font-bold text-slate-900 dark:text-white text-sm">#${item.orderNo || item.orderId}</span>
                            ${renderStatusBadge('COMPLETED')}
                        </div>
                        <div class="px-4 pt-2 pb-1">
                            <span class="text-[11px] text-slate-400 font-medium">下單日期：${dateStr}</span>
                        </div>
                        <div class="grid grid-cols-3 gap-2 p-3">
                            <div class="bg-slate-50 dark:bg-slate-800 rounded-lg p-2.5 text-center">
                                <div class="text-[10px] text-slate-400 font-medium mb-1">訂單總額</div>
                                <div class="text-sm font-bold text-slate-900 dark:text-white">$${total.toLocaleString()}</div>
                            </div>
                            <div class="bg-primary/5 rounded-lg p-2.5 text-center">
                                <div class="text-[10px] text-slate-400 font-medium mb-1">平台抽成</div>
                                <div class="text-sm font-bold text-primary">-$${commission.toLocaleString()}</div>
                            </div>
                            <div class="bg-green-50 dark:bg-green-900/20 rounded-lg p-2.5 text-center">
                                <div class="text-[10px] text-slate-400 font-medium mb-1">實收金額</div>
                                <div class="text-sm font-bold text-green-600 dark:text-green-400">$${net.toLocaleString()}</div>
                            </div>
                        </div>
                    </div>
                `;
            }).join('');
        };

        const renderStatusBadge = (status) => {
            if (status === 'COMPLETED') {
                return `<span class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400"><span class="size-1.5 rounded-full bg-green-500"></span>已入帳</span>`;
            }
            return `<span class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400"><span class="size-1.5 rounded-full bg-amber-500"></span>待撥款</span>`;
        };

        if (periodFilter) {
            periodFilter.addEventListener('change', (e) => loadData(e.target.value));
        }

        await loadData(periodFilter?.value || 'month');
        container.classList.remove('opacity-0');
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
