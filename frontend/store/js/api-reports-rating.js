/**
 * api-reports-rating.js — 評分星數報表
 * Stale-while-revalidate：先顯示持久快取，背景靜默更新
 */
(() => {
    const renderStars = (avg, size = 'text-2xl') => {
        const stars = [];
        for (let i = 1; i <= 5; i++) {
            let icon, filled;
            if (avg >= i)            { icon = 'star';       filled = true;  }
            else if (avg >= i - 0.5) { icon = 'star_half';  filled = true;  }
            else                     { icon = 'star_border'; filled = false; }
            const fillStyle = filled ? "font-variation-settings:'FILL' 1" : '';
            stars.push(`<span class="material-symbols-outlined ${size}" style="${fillStyle}">${icon}</span>`);
        }
        return stars.join('');
    };

    const applyData = (data) => {
        if (!data) return;
        const avg = Number(data.avgRating || 0);
        const avgEl   = document.querySelector('[data-avg-rating]');
        const starsEl = document.querySelector('[data-avg-stars]');
        const totalEl = document.querySelector('[data-total-reviews]');
        if (avgEl)   avgEl.textContent   = avg.toFixed(1);
        if (starsEl) starsEl.innerHTML   = renderStars(avg, 'text-2xl');
        if (totalEl) totalEl.textContent = `${data.totalReviews || 0} 則評分`;

        const dist  = data.distribution || {};
        const total = Number(data.totalReviews) || 1;
        for (let i = 1; i <= 5; i++) {
            const count = Number(dist[i] || 0);
            const pct   = total > 0 ? Math.round((count / total) * 100) : 0;
            const barEl   = document.querySelector(`[data-rating-bar="${i}"]`);
            const countEl = document.querySelector(`[data-rating-count="${i}"]`);
            if (barEl)   barEl.style.width   = `${pct}%`;
            if (countEl) countEl.textContent = count;
        }
    };

    const init = async () => {
        const container = document.getElementById('ratingReportContainer');
        if (!container) return;

        // 1. 持久快取 → 立即顯示
        const snap = window.StoreAPI.getRatingStatsSnapshot();
        if (snap) applyData(snap);

        // 2. 背景靜默刷新
        try {
            const data = await window.StoreAPI.refreshRatingStats();
            if (data) {
                applyData(data);
                window.StoreAPI.saveRatingStatsSnapshot(data);
            }
        } catch (err) {
            if (!snap) console.error('[RatingReport] 載入失敗:', err);
        }

        // 移除隱藏狀態
        const root = document.getElementById('mainContentFade');
        if (root) root.classList.replace('invisible', 'visible');
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
