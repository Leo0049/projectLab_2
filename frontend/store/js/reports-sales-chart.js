/**
 * reports-sales-chart.js — 營收趨勢圖實作 (Chart.js)
 */
(() => {
    let salesChart = null;
    let chartData = {
        labels: [],
        thisWeek: [],
        lastWeek: []
    };

    const init = async () => {
        const ctx = document.getElementById('salesTrendsChart');
        if (!ctx) return;

        try {
            // 抓取數據
            const data = await window.StoreAPI.getSalesTrend();
            if (!data) return;
            chartData = data;

            // 初始化圖表
            renderChart('thisWeek');

            // 綁定切換按鈕
            const btnThis = document.getElementById('toggleThisWeek');
            const btnLast = document.getElementById('toggleLastWeek');

            if (btnThis && btnLast) {
                btnThis.addEventListener('click', () => {
                    updateBtnUI(btnThis, btnLast);
                    renderChart('thisWeek');
                });
                btnLast.addEventListener('click', () => {
                    updateBtnUI(btnLast, btnThis);
                    renderChart('lastWeek');
                });
            }

        } catch (err) {
            console.error('[SalesChart] 初始化失敗:', err);
        }
    };

    const updateBtnUI = (activeBtn, inactiveBtn) => {
        activeBtn.className = 'flex items-center gap-2 px-3 py-1.5 rounded-lg bg-primary/10 text-primary text-xs font-bold border border-primary/20';
        const activeDot = activeBtn.querySelector('span:first-child');
        if (activeDot) activeDot.className = 'size-3 rounded-full bg-primary';
        
        inactiveBtn.className = 'flex items-center gap-2 px-3 py-1.5 rounded-lg bg-slate-50 text-slate-400 text-xs font-bold border border-slate-100';
        const inactiveDot = inactiveBtn.querySelector('span:first-child');
        if (inactiveDot) inactiveDot.className = 'size-3 rounded-full bg-slate-300';
    };

    const renderChart = (mode) => {
        const canvas = document.getElementById('salesTrendsChart');
        if (!canvas) return;
        const ctx = canvas.getContext('2d');
        const isDark = document.documentElement.classList.contains('dark');
        
        const dataValues = chartData[mode] || [];
        const label = mode === 'thisWeek' ? '本週營收' : '上週營收';
        const color = '#ec5b13'; // Primary

        if (salesChart) {
            salesChart.destroy();
        }

        salesChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: chartData.labels,
                datasets: [{
                    label: label,
                    data: dataValues,
                    borderColor: color,
                    backgroundColor: 'rgba(236, 91, 19, 0.1)',
                    borderWidth: 4,
                    pointBackgroundColor: '#ffffff',
                    pointBorderColor: color,
                    pointBorderWidth: 2,
                    pointRadius: 6,
                    pointHoverRadius: 8,
                    fill: true,
                    tension: 0.4 // 平滑曲線
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: {
                    intersect: false,
                    mode: 'index',
                },
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        enabled: true,
                        backgroundColor: isDark ? '#1e293b' : '#ffffff',
                        titleColor: isDark ? '#f1f5f9' : '#1e293b',
                        bodyColor: color,
                        titleFont: { size: 14, weight: 'bold' },
                        bodyFont: { size: 16, weight: '900' },
                        padding: 12,
                        borderColor: 'rgba(236, 91, 19, 0.2)',
                        borderWidth: 1,
                        displayColors: false,
                        callbacks: {
                            label: (context) => {
                                const val = context.parsed.y;
                                return val !== null ? ` NT$ ${val.toLocaleString()}` : ' 無數據';
                            }
                        }
                    }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        grid: {
                            color: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)',
                            drawBorder: false
                        },
                        ticks: {
                            color: '#94a3b8',
                            font: { size: 11, weight: 'bold' },
                            callback: (val) => `$${val.toLocaleString()}`
                        }
                    },
                    x: {
                        grid: { display: false },
                        ticks: {
                            color: '#94a3b8',
                            font: { size: 12, weight: 'bold' }
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
