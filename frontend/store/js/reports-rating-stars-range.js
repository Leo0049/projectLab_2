(() => {
  const root = document.querySelector('[data-rating-range-picker]');
  if (!root) return;

  const button = root.querySelector('[data-rating-range-button]');
  const labelEl = root.querySelector('[data-rating-range-label]');
  const dropdown = root.querySelector('[data-rating-range-dropdown]');
  const options = Array.from(root.querySelectorAll('[data-rating-range-option]'));

  if (!button || !labelEl || !dropdown || options.length === 0) return;

  const ACTIVE_BORDER = ['border-primary', 'ring-1', 'ring-primary'];
  const INACTIVE_BORDER = ['border-slate-200', 'dark:border-slate-700'];

  const DATASETS = {
    yesterday: {
      label: '昨天',
      counts: { 5: 18, 4: 7, 3: 2, 2: 1, 1: 0 },
    },
    last7: {
      label: '過去 7 天',
      counts: { 5: 352, 4: 121, 3: 44, 2: 18, 1: 9 },
    },
    last30: {
      label: '過去 30 天',
      // Match the visible 30-day summary numbers in the HTML.
      counts: { 5: 1612, 4: 546, 3: 199, 2: 74, 1: 50 },
    },
  };

  function formatNumber(num) {
    return Number(num || 0).toLocaleString('en-US');
  }

  function setDropdownOpen(open) {
    dropdown.classList.toggle('hidden', !open);
    button.setAttribute('aria-expanded', open ? 'true' : 'false');

    for (const cls of ACTIVE_BORDER) button.classList.toggle(cls, open);
    for (const cls of INACTIVE_BORDER) button.classList.toggle(cls, !open);
  }

  function isDropdownOpen() {
    return !dropdown.classList.contains('hidden');
  }

  function computeTotal(counts) {
    return [1, 2, 3, 4, 5].reduce((sum, star) => sum + (Number(counts[star]) || 0), 0);
  }

  function computeAverage(counts) {
    const total = computeTotal(counts);
    if (total <= 0) return 0;

    const sum = [1, 2, 3, 4, 5].reduce(
      (acc, star) => acc + star * (Number(counts[star]) || 0),
      0
    );

    return sum / total;
  }

  function updateDistribution(rangeKey) {
    const dataset = DATASETS[rangeKey];
    if (!dataset) return;

    labelEl.textContent = dataset.label;

    const counts = dataset.counts;
    const total = computeTotal(counts);
    const avg = computeAverage(counts);

    // Update totals
    const totalEls = document.querySelectorAll('[data-rating-total]');
    for (const el of totalEls) {
      el.textContent = formatNumber(total);
    }

    const avgEl = document.querySelector('[data-rating-average]');
    if (avgEl) {
      avgEl.textContent = avg ? avg.toFixed(1) : '0.0';
    }

    // Update rows
    const rows = Array.from(document.querySelectorAll('[data-rating-dist-row]'));
    for (const row of rows) {
      const star = Number(row.getAttribute('data-rating-dist-row'));
      if (!star || star < 1 || star > 5) continue;

      const count = Number(counts[star]) || 0;
      const percent = total > 0 ? (count / total) * 100 : 0;
      const percentLabel = `${Math.round(percent)}%`;

      const bar = row.querySelector('[data-rating-dist-bar]');
      if (bar) bar.style.width = `${percent.toFixed(2)}%`;

      const percentEl = row.querySelector('[data-rating-dist-percent]');
      if (percentEl) percentEl.textContent = percentLabel;
    }

    // Update summary counts
    const fiveEl = document.querySelector('[data-rating-summary-five]');
    if (fiveEl) fiveEl.textContent = formatNumber(counts[5] || 0);

    const fourEl = document.querySelector('[data-rating-summary-four]');
    if (fourEl) fourEl.textContent = formatNumber(counts[4] || 0);

    const lowEl = document.querySelector('[data-rating-summary-low]');
    if (lowEl) lowEl.textContent = formatNumber((counts[1] || 0) + (counts[2] || 0));
  }

  // Button toggles the menu.
  button.addEventListener('click', (e) => {
    e.preventDefault();
    setDropdownOpen(!isDropdownOpen());
  });

  // Selecting an option updates the distribution.
  for (const opt of options) {
    opt.addEventListener('click', () => {
      const key = opt.getAttribute('value');
      updateDistribution(key);
      setDropdownOpen(false);
    });
  }

  document.addEventListener('click', (e) => {
    if (!isDropdownOpen()) return;
    const target = e.target;
    if (!(target instanceof Node)) return;
    if (root.contains(target)) return;
    setDropdownOpen(false);
  });

  document.addEventListener('keydown', (e) => {
    if (!isDropdownOpen()) return;
    if (e.key === 'Escape') setDropdownOpen(false);
  });

  // Init
  setDropdownOpen(false);
  updateDistribution('last30');
})();
