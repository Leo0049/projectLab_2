(function () {
    function setRowRestState(row, isRest) {
        const timeInputs = row.querySelectorAll('input[type="time"]');
        timeInputs.forEach((input) => {
            input.disabled = isRest;

            if (isRest) {
                input.classList.remove('bg-slate-50');
                input.classList.add('bg-slate-200', 'text-slate-500', 'cursor-not-allowed');
            } else {
                input.classList.remove('bg-slate-200', 'text-slate-500', 'cursor-not-allowed');
                input.classList.add('bg-slate-50', 'focus:ring-primary', 'focus:border-primary');
            }
        });

        const restLabel = Array.from(row.querySelectorAll('span')).find(
            (span) => span.textContent && span.textContent.trim() === '店休'
        );

        if (restLabel) {
            if (isRest) {
                restLabel.classList.remove('text-slate-500');
                restLabel.classList.add('font-medium', 'text-red-500');
            } else {
                restLabel.classList.remove('font-medium', 'text-red-500');
                restLabel.classList.add('text-slate-500');
            }
        }
    }

    function init() {
        const root = document.getElementById('businessHoursSettings');
        if (!root) return;

        const restToggles = root.querySelectorAll('input[type="checkbox"].sr-only.peer');

        restToggles.forEach((toggle) => {
            const row = toggle.closest('div.flex.items-center.gap-8');
            if (!row) return;

            setRowRestState(row, toggle.checked);

            toggle.addEventListener('change', () => {
                setRowRestState(row, toggle.checked);
            });
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
