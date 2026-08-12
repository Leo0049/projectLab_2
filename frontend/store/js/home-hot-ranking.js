(function () {
    function parseCount(text) {
        const match = String(text || '').match(/(\d+)/);
        return match ? Number(match[1]) : 0;
    }

    function init() {
        const card = document.querySelector('[data-hot-ranking]');
        if (!card) return;

        const countEls = Array.from(card.querySelectorAll('[data-hot-rank-count]'));
        const barEls = Array.from(card.querySelectorAll('[data-hot-rank-bar]'));
        if (countEls.length === 0 || barEls.length === 0) return;

        const counts = countEls.map((el) => parseCount(el.textContent));
        const max = Math.max(...counts, 0);
        if (!Number.isFinite(max) || max <= 0) return;

        const len = Math.min(countEls.length, barEls.length);
        for (let i = 0; i < len; i++) {
            const value = counts[i];
            const percent = Math.max(0, Math.min(100, (value / max) * 100));
            barEls[i].style.width = `${percent}%`;
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
