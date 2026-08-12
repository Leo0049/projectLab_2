(() => {
    function computeStars(score) {
        const s = Math.max(0, Math.min(5, Number(score) || 0));
        const full = Math.floor(s);
        const rest = s - full;
        let hasHalf = false;
        let fullFinal = full;
        if (rest >= 0.75) { fullFinal += 1; }
        else if (rest >= 0.25) { hasHalf = true; }
        const empty = Math.max(0, 5 - fullFinal - (hasHalf ? 1 : 0));
        return { full: Math.min(5, fullFinal), half: hasHalf ? 1 : 0, empty };
    }

    function renderStars(container, score) {
        if (!container) return;
        while (container.firstChild) container.removeChild(container.firstChild);
        const { full, half, empty } = computeStars(score);
        for (let i = 0; i < full; i++) {
            const s = document.createElement('span');
            s.className = 'material-symbols-outlined filled-icon text-primary';
            s.textContent = 'star';
            container.appendChild(s);
        }
        if (half) {
            const s = document.createElement('span');
            s.className = 'material-symbols-outlined text-primary';
            s.textContent = 'star_half';
            container.appendChild(s);
        }
        for (let i = 0; i < empty; i++) {
            const s = document.createElement('span');
            s.className = 'material-symbols-outlined text-slate-300 dark:text-slate-700';
            s.textContent = 'star';
            container.appendChild(s);
        }
    }

    function clampPct(value) {
        const n = Number(value);
        return Number.isFinite(n) ? Math.max(0, Math.min(100, n)) : 0;
    }

    function openModal(el)  { el.classList.remove('hidden'); }
    function closeModal(el) { el.classList.add('hidden'); }

    function init() {
        const modalEl    = document.querySelector('[data-hq-low-rating-modal]');
        const backdropEl = document.querySelector('[data-hq-low-rating-backdrop]');
        const closeEls   = Array.from(document.querySelectorAll('[data-hq-low-rating-close]'));

        const titleEl   = document.querySelector('[data-hq-low-rating-title]');
        const scoreEl   = document.querySelector('[data-hq-low-rating-score]');
        const starsEl   = document.querySelector('[data-hq-low-rating-stars]');
        const totalEl   = document.querySelector('[data-hq-low-rating-total]');
        const rateEl    = document.querySelector('[data-hq-low-rating-rate]');
        const rateBarEl = document.querySelector('[data-hq-low-rating-rate-bar]');

        if (!modalEl || !backdropEl || closeEls.length === 0) return;
        if (!titleEl || !scoreEl || !starsEl || !totalEl || !rateEl || !rateBarEl) return;

        function fillModal(storeName, score, reviewCount, dist) {
            // ─── 標題 & 評分 ──────────────────────────────────
            titleEl.textContent  = `${storeName} - 評分詳情`;
            scoreEl.textContent  = Number.isFinite(Number(score))
                ? Number(score).toFixed(1) : '—';
            renderStars(starsEl, score);

            // 共計：顯示此分店的評論總數
            const total = Number(reviewCount || 0);
            totalEl.textContent = `共計 ${total.toLocaleString('en-US')} 則評論`;

            // 評分率：此店評論數佔品牌總評論數的比例
            const data = window._brandReputationData || {};
            const brandTotal = Number(data.totalReviewCount || 0);
            const ratePct = brandTotal > 0
                ? clampPct(Math.round((total / brandTotal) * 100))
                : 0;
            rateEl.textContent     = `${ratePct}%`;
            rateBarEl.style.width  = `${ratePct}%`;

            // ─── 評分分佈：使用此店的每星級評論數 ──────────────
            const distObj = dist || {};
            const distTotal = [1,2,3,4,5].reduce((s, k) => s + Number(distObj[k] || 0), 0) || 1;
            [5, 4, 3, 2, 1].forEach(k => {
                const count = Number(distObj[String(k)] || 0);
                const pct = clampPct(Math.round((count / distTotal) * 100));
                const bar = modalEl.querySelector(`[data-hq-low-rating-dist-bar="${k}"]`);
                const pctEl = modalEl.querySelector(`[data-hq-low-rating-dist-pct="${k}"]`);
                if (bar)   bar.style.width   = `${pct}%`;
                if (pctEl) pctEl.textContent = count.toLocaleString('en-US');
            });
        }

        document.addEventListener('click', (e) => {
            const btn = e.target?.closest?.('[data-hq-low-rating-open]');
            if (!btn) return;

            const row       = btn.closest('tr');
            const storeName = row?.dataset?.storeName
                || row?.querySelector('td')?.textContent?.trim()
                || '分店';
            const score       = Number(row?.dataset?.storeRating) || 0;
            const reviewCount = Number(row?.dataset?.storeReviewCount) || 0;
            let dist = {};
            try { dist = JSON.parse(row?.dataset?.storeDist || '{}'); } catch (_) {}

            fillModal(storeName, score, reviewCount, dist);
            openModal(modalEl);
        });

        closeEls.forEach(el => el.addEventListener('click', () => closeModal(modalEl)));
        backdropEl.addEventListener('click', () => closeModal(modalEl));
        document.addEventListener('keydown', e => {
            if (e.key !== 'Escape' || modalEl.classList.contains('hidden')) return;
            closeModal(modalEl);
        });

        closeModal(modalEl);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
