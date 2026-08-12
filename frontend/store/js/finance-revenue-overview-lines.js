/**
 * finance-revenue-overview-lines.js — 各類別營收折線圖 (Chart.js 實作)
 * 支援動態分類勾選、本週/上週切換
 */
(() => {
    const MAX_VISIBLE = 4;
    const RAINBOW_COLORS = ['#ef4444', '#f97316', '#f59e0b', '#10b981', '#3b82f6', '#6366f1', '#8b5cf6'];

    let trendChart = null;
    let currentPeriod = 'this-week';
    let allSeries = []; // 原始資料 [{name, data}]
    let selectedCategories = []; // 目前勾選的名稱

    const init = async () => {
        const root = document.querySelector('[data-finance-revenue-overview-lines]');
        if (!root) return;

        // 1. 監聽日期區間切換 (本週/上週)
        const rangeOptions = root.querySelectorAll('[data-revenue-lines-range-option]');
        const rangeLabel = root.querySelector('[data-revenue-lines-range-label]');
        const rangeBtn = root.querySelector('[data-revenue-lines-range-button]');
        const rangeDropdown = root.querySelector('[data-revenue-lines-range-dropdown]');

        if (rangeBtn && rangeDropdown) {
            rangeBtn.onclick = (e) => {
                e.stopPropagation();
                rangeDropdown.classList.toggle('hidden');
            };
            document.addEventListener('click', () => rangeDropdown.classList.add('hidden'));
        }

        rangeOptions.forEach(opt => {
            opt.onclick = async () => {
                const val = opt.getAttribute('value');
                currentPeriod = val;
                if (rangeLabel) rangeLabel.textContent = opt.textContent;
                await fetchDataAndRender(root);
            };
        });

        // 2. 監聽分類選擇開關
        const pickerBtn = root.querySelector('[data-revenue-lines-button]');
        const pickerDropdown = root.querySelector('[data-revenue-lines-dropdown]');
        if (pickerBtn && pickerDropdown) {
            pickerBtn.onclick = (e) => {
                e.stopPropagation();
                pickerDropdown.classList.toggle('hidden');
            };
            document.addEventListener('click', () => pickerDropdown.classList.add('hidden'));
        }

        // 初始載入
        await fetchDataAndRender(root);
    };

    const fetchDataAndRender = async (root) => {
        try {
            const data = await window.StoreAPI.getFinanceCategoryTrend(currentPeriod);
            if (!data) return;

            allSeries = data.series || [];
            const labels = data.labels || [];
            
            // 如果是第一次載入，預設勾選前 4 個
            if (selectedCategories.length === 0) {
                selectedCategories = allSeries.slice(0, MAX_VISIBLE).map(s => s.name);
            }

            renderCategorySelector(root);
            drawChart(labels);
        } catch (err) {
            console.error('[RevenueLines] 載入失敗:', err);
        }
    };

    const renderCategorySelector = (root) => {
        const dropdown = root.querySelector('[data-revenue-lines-dropdown]');
        if (!dropdown) return;

        dropdown.onclick = (e) => e.stopPropagation(); // 防止點擊選項時關閉選單

        dropdown.innerHTML = `
            <div class="text-xs font-bold text-slate-500 dark:text-slate-400 mb-2 px-1">最多同時顯示 ${MAX_VISIBLE} 條折線</div>
            <div class="space-y-1">
                ${allSeries.map((s, i) => {
                    const checked = selectedCategories.includes(s.name);
                    const color = RAINBOW_COLORS[i % RAINBOW_COLORS.length];
                    return `
                        <label class="flex items-center gap-3 px-2 py-2 rounded-lg hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer">
                            <input type="checkbox" value="${s.name}" ${checked ? 'checked' : ''} 
                                class="category-checkbox size-4 rounded border-slate-300 text-primary focus:ring-primary" />
                            <span class="size-2.5 rounded-full" style="background-color: ${color}"></span>
                            <span class="text-sm font-bold text-slate-700 dark:text-slate-200">${s.name}</span>
                        </label>
                    `;
                }).join('')}
            </div>
        `;

        // 監聽勾選事件
        dropdown.querySelectorAll('.category-checkbox').forEach(cb => {
            cb.onchange = () => {
                const val = cb.value;
                if (cb.checked) {
                    if (selectedCategories.length >= MAX_VISIBLE) {
                        cb.checked = false;
                        window.StoreAPI.showToast(`最多僅能同時顯示 ${MAX_VISIBLE} 個類別`, 'error');
                        return;
                    }
                    selectedCategories.push(val);
                } else {
                    selectedCategories = selectedCategories.filter(c => c !== val);
                }
                drawChart(); // 重新繪圖
            };
        });
    };

    const drawChart = (labels) => {
        const canvas = document.getElementById('financeTrendChart');
        if (!canvas) return;
        const ctx = canvas.getContext('2d');

        // 如果 labels 沒傳進來（代表是從勾選觸發），則沿用舊的
        const chartLabels = labels || (trendChart ? trendChart.data.labels : []);

        if (trendChart) trendChart.destroy();

        const activeSeries = allSeries.filter(s => selectedCategories.includes(s.name));

        const datasets = activeSeries.map((s, i) => ({
            label: s.name,
            data: s.data,
            borderColor: RAINBOW_COLORS[i % RAINBOW_COLORS.length],
            backgroundColor: RAINBOW_COLORS[i % RAINBOW_COLORS.length] + '20',
            fill: true,
            tension: 0.4,
            pointRadius: 4,
            pointHoverRadius: 6
        }));

        trendChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: chartLabels,
                datasets: datasets
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: { mode: 'index', intersect: false },
                scales: {
                    y: { 
                        beginAtZero: true, 
                        grid: { color: 'rgba(0,0,0,0.05)' }, 
                        ticks: { font: { size: 10 }, callback: (v) => '$' + v.toLocaleString() } 
                    },
                    x: { grid: { display: false }, ticks: { font: { size: 10, weight: 'bold' } } }
                },
                plugins: { 
                    legend: { 
                        display: true, 
                        position: 'top', 
                        labels: { boxWidth: 12, usePointStyle: true, font: { size: 11 } } 
                    },
                    tooltip: {
                        callbacks: {
                            label: (ctx) => ` ${ctx.dataset.label}: $${ctx.parsed.y.toLocaleString()}`
                        }
                    }
                }
            }
        });
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
