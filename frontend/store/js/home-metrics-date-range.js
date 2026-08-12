(function () {
  const rangeButtons = Array.from(document.querySelectorAll('button[data-date-range]'));
  if (rangeButtons.length === 0) return;

  const indicator = rangeButtons[0].parentElement?.querySelector('[data-date-range-indicator]') || null;

  const metricRevenueEl = document.querySelector('[data-home-metric="revenue"]');
  const metricOrdersEl = document.querySelector('[data-home-metric="orders"]');
  const metricAovEl = document.querySelector('[data-home-metric="aov"]');

  const deltaRevenueEl = document.querySelector('[data-home-delta="revenue"]');
  const deltaOrdersEl = document.querySelector('[data-home-delta="orders"]');
  const deltaAovEl = document.querySelector('[data-home-delta="aov"]');

  if (!metricRevenueEl || !metricOrdersEl || !metricAovEl) return;

  const parseNumeric = (raw) => {
    if (raw == null) return NaN;
    const str = String(raw).trim();
    if (!str) return NaN;
    const cleaned = str.replace(/[^0-9.+-]/g, '');
    const num = Number(cleaned);
    return Number.isFinite(num) ? num : NaN;
  };

  const setDelta = (deltaRoot, currentRaw, prevRaw) => {
    if (!deltaRoot) return;

    const iconEl = deltaRoot.querySelector('[data-home-delta-icon]');
    const valueEl = deltaRoot.querySelector('[data-home-delta-value]');
    if (!iconEl || !valueEl) return;

    const current = parseNumeric(currentRaw);
    const prev = parseNumeric(prevRaw);

    // Reset colors
    deltaRoot.classList.remove('text-green-500', 'text-red-500', 'text-slate-500', 'dark:text-slate-400');

    if (!Number.isFinite(current) || !Number.isFinite(prev) || prev === 0) {
      iconEl.textContent = 'trending_flat';
      valueEl.textContent = '—';
      deltaRoot.classList.add('text-slate-500', 'dark:text-slate-400');
      return;
    }

    const pct = ((current - prev) / prev) * 100;
    const absPct = Math.abs(pct);
    const pctText = `${pct > 0 ? '+' : pct < 0 ? '−' : ''}${absPct.toFixed(1)}%`;
    valueEl.textContent = pctText;

    if (pct > 0) {
      iconEl.textContent = 'trending_up';
      deltaRoot.classList.add('text-green-500');
    } else if (pct < 0) {
      iconEl.textContent = 'trending_down';
      deltaRoot.classList.add('text-red-500');
    } else {
      iconEl.textContent = 'trending_flat';
      deltaRoot.classList.add('text-slate-500', 'dark:text-slate-400');
    }
  };

  let activeButton = null;

  const ACTIVE_CLASSES = ['text-white'];
  const INACTIVE_CLASSES = [
    'text-slate-600',
    'dark:text-slate-400',
    'hover:bg-slate-50',
    'dark:hover:bg-slate-700/50',
  ];

  function moveIndicatorToButton(btn) {
    if (!indicator) return;
    const container = btn.parentElement;
    if (!container) return;

    const containerRect = container.getBoundingClientRect();
    const btnRect = btn.getBoundingClientRect();

    const x = btnRect.left - containerRect.left;
    indicator.style.width = `${btnRect.width}px`;
    indicator.style.transform = `translateX(${x}px)`;
  }

  function setActiveButton(activeBtn) {
    activeButton = activeBtn;
    for (const btn of rangeButtons) {
      const isActive = btn === activeBtn;
      btn.setAttribute('aria-pressed', String(isActive));

      for (const cls of ACTIVE_CLASSES) {
        btn.classList.toggle(cls, isActive);
      }

      for (const cls of INACTIVE_CLASSES) {
        btn.classList.toggle(cls, !isActive);
      }
    }

    // Defer layout reads until after class toggles.
    requestAnimationFrame(() => moveIndicatorToButton(activeBtn));
  }

  function setMetricText(el, value) {
    if (typeof value !== 'string') return;
    const trimmed = value.trim();
    if (!trimmed) return;
    el.textContent = trimmed;
  }

  function applyRangeFromButton(btn) {
    setActiveButton(btn);

    setMetricText(metricRevenueEl, btn.dataset.revenue);
    setMetricText(metricOrdersEl, btn.dataset.orders);
    setMetricText(metricAovEl, btn.dataset.aov);

    setDelta(deltaRevenueEl, btn.dataset.revenue, btn.dataset.revenuePrev);
    setDelta(deltaOrdersEl, btn.dataset.orders, btn.dataset.ordersPrev);
    setDelta(deltaAovEl, btn.dataset.aov, btn.dataset.aovPrev);
  }

  for (const btn of rangeButtons) {
    btn.addEventListener('click', () => applyRangeFromButton(btn));
  }

  const defaultBtn =
    rangeButtons.find((b) => b.classList.contains('bg-primary')) ||
    rangeButtons.find((b) => b.dataset.dateRange === 'today') ||
    rangeButtons[0];

  applyRangeFromButton(defaultBtn);

  window.addEventListener('resize', () => {
    if (!activeButton) return;
    moveIndicatorToButton(activeButton);
  });
})();
