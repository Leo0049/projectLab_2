/**
 * home-sales-trend-tooltip.js — 本週銷售趨勢圖 (修正版 - 強制重繪)
 */
(function () {
    let chartInstance = null;

    async function initChart() {
        const ctx = document.getElementById('weeklySalesChart');
        if (!ctx) return;

        try {
            const data = await window.StoreAPI.getSalesTrend();
            if (!data || !data.thisWeek || !data.labels) {
                console.warn('[SalesTrend] API 回傳數據結構不符:', data);
                return;
            }

            // 直接使用後端提供的標籤與數據
            const labels = data.labels;
            const thisWeekData = data.thisWeek.map(v => Number(v || 0));

            if (chartInstance) chartInstance.destroy();

            chartInstance = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: labels,
                    datasets: [{
                        label: '銷售額',
                        data: thisWeekData,
                        backgroundColor: '#ffedd5',
                        hoverBackgroundColor: '#fed7aa',
                        borderRadius: 12,
                        borderSkipped: false,
                        // 調整佔比讓長條圖變粗
                        categoryPercentage: 1.0,
                        barPercentage: 0.9,
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    animation: {
                        duration: 800,
                        easing: 'easeOutQuart'
                    },
                    plugins: {
                        legend: { display: false },
                        tooltip: {
                            enabled: true,
                            backgroundColor: 'rgba(34, 22, 16, 0.9)',
                            padding: 12,
                            titleFont: { size: 14, weight: 'bold' },
                            bodyFont: { size: 13 },
                            cornerRadius: 8,
                            displayColors: false,
                            callbacks: {
                                label: (context) => ` $${context.parsed.y.toLocaleString()}`
                            }
                        }
                    },
                    scales: {
                        y: {
                            display: false,
                            beginAtZero: true,
                            suggestedMax: Math.max(...thisWeekData, 1000) * 1.2
                        },
                        x: {
                            grid: { display: false, border: false },
                            ticks: {
                                color: '#94a3b8',
                                font: { size: 12, weight: '600' },
                                padding: 10
                            },
                            border: { display: false }
                        }
                    }
                }
            });

            // 渲染成功後顯示容器
            const container = document.getElementById('salesChartContainer');
            if (container) container.classList.remove('opacity-0');
        } catch (err) {
            console.error('[SalesTrend] 初始化失敗:', err);
        }
    }

    // 延遲初始化，確保 DOM 完全渲染且佈局計算完成
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => setTimeout(initChart, 300));
    } else {
        setTimeout(initChart, 300);
    }

    // 監聽視窗縮放以重新調整圖表
    window.addEventListener('resize', () => {
        if (chartInstance) chartInstance.resize();
    });
})();
