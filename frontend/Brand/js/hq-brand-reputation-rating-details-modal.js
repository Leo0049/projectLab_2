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

    function clampPct(v) {
        const n = Number(v);
        return Number.isFinite(n) ? Math.max(0, Math.min(100, n)) : 0;
    }

    function openModal(el) { el.classList.remove('hidden'); }
    function closeModal(el) { el.classList.add('hidden'); }

    function init() {
        const openBtn     = document.querySelector('[data-hq-brand-rating-details-open]');
        const modalEl     = document.querySelector('[data-hq-brand-rating-details-modal]');
        const backdropEl  = document.querySelector('[data-hq-brand-rating-details-backdrop]');
        const closeEls    = Array.from(document.querySelectorAll('[data-hq-brand-rating-details-close]'));
        const scoreEl     = document.querySelector('[data-hq-brand-rating-details-score]');
        const starsEl     = document.querySelector('[data-hq-brand-rating-details-stars]');
        const totalEl     = document.querySelector('[data-hq-brand-rating-details-total]');
        const rateEl      = document.querySelector('[data-hq-brand-rating-details-rate]');
        const rateBarEl   = document.querySelector('[data-hq-brand-rating-details-rate-bar]');

        if (!openBtn || !modalEl || !backdropEl || closeEls.length === 0) return;
        if (!scoreEl || !starsEl || !totalEl || !rateEl || !rateBarEl) return;

        function fillModal() {
            const data = window._brandReputationData || {};
            const score      = Number(data.avgRating || 0);
            const ratedCount = Number(data.ratedCount || 0);
            const storeCount = Number(data.storeCount || 0);
            const rate       = storeCount > 0 ? Math.round((ratedCount / storeCount) * 100) : 0;

            scoreEl.textContent = score.toFixed(1);
            renderStars(starsEl, score);
            const totalReviewCount = Number(data.totalReviewCount || 0);
            totalEl.textContent = `共計 ${totalReviewCount.toLocaleString('en-US')} 則評論`;

            const ratePct = clampPct(rate);
            rateEl.textContent   = `${ratePct}%`;
            rateBarEl.style.width = `${ratePct}%`;

            // Distribution from ratingDistribution map (star → count)
            const dist = data.ratingDistribution || {};
            const total = [5, 4, 3, 2, 1].reduce((acc, k) => acc + Number(dist[k] || 0), 0) || 1;

            [5, 4, 3, 2, 1].forEach(k => {
                const count = Number(dist[k] || 0);
                const pct   = clampPct(Math.round((count / total) * 100));
                const bar   = modalEl.querySelector(`[data-hq-brand-rating-details-dist-bar="${k}"]`);
                const pctEl = modalEl.querySelector(`[data-hq-brand-rating-details-dist-pct="${k}"]`);
                if (bar)   bar.style.width       = `${pct}%`;
                if (pctEl) pctEl.textContent     = count.toLocaleString('en-US');
            });
        }

        openBtn.addEventListener('click', () => { fillModal(); openModal(modalEl); });
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
