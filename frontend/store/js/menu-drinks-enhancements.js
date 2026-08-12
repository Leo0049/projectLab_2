(function () {
  const root = document.getElementById('menuDrinksRoot');
  if (!root) return;

  const htmlEl = document.documentElement;

  const STATUS_CLASSES = {
    '供應中': [
      'bg-emerald-50',
      'dark:bg-emerald-900/10',
      'border-emerald-200',
      'dark:border-emerald-800/50',
      'text-emerald-700',
      'dark:text-emerald-400',
    ],
    '補貨中': [
      'bg-amber-50',
      'dark:bg-amber-900/10',
      'border-amber-200',
      'dark:border-amber-800/50',
      'text-amber-700',
      'dark:text-amber-400',
    ],
    '今日已售完': [
      'bg-rose-50',
      'dark:bg-rose-900/10',
      'border-rose-200',
      'dark:border-rose-800/50',
      'text-rose-700',
      'dark:text-rose-400',
    ],
  };

  const ALL_STATUS_CLASSES = Object.values(STATUS_CLASSES).flat();

  const DROPDOWN_READY_ATTR = 'data-ui-status-dropdown-ready';

  let statusDropdownIdCounter = 0;

  const STATUS_BUTTON_CLASSES = {
    '供應中': ['bg-green-100', 'dark:bg-green-900/30', 'text-green-700', 'dark:text-green-400'],
    '補貨中': ['bg-amber-100', 'dark:bg-amber-900/30', 'text-amber-700', 'dark:text-amber-400'],
    '今日已售完': ['bg-red-100', 'dark:bg-red-900/30', 'text-red-700', 'dark:text-red-400'],
  };

  const ALL_STATUS_BUTTON_CLASSES = Object.values(STATUS_BUTTON_CLASSES).flat();

  const ACTIVE_DROPDOWN_BUTTON_CLASSES = ['border-primary', 'ring-1', 'ring-primary'];
  const INACTIVE_DROPDOWN_BUTTON_CLASSES = ['border-slate-200', 'dark:border-slate-700'];

  const DROPDOWN_BUTTON_CLASSES =
    'w-full px-4 py-2 text-sm font-bold text-slate-700 dark:text-slate-200 border rounded-lg ' +
    'bg-white dark:bg-slate-900 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors inline-flex items-center justify-between gap-2';

  const DROPDOWN_MENU_CLASSES =
    'hidden bg-white dark:bg-slate-800 ' +
    'border border-slate-200 dark:border-slate-700 rounded-xl shadow-sm p-2 z-50';

  const DROPDOWN_OPTION_CLASSES =
    'w-full text-left px-3 py-2 rounded-lg text-sm font-bold ' +
    'text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700/50';

  const STATUS_OPTION_CLASSES = {
    '供應中': ['bg-green-50', 'dark:bg-green-900/20', 'hover:bg-green-100', 'dark:hover:bg-green-900/30'],
    '補貨中': ['bg-amber-50', 'dark:bg-amber-900/20', 'hover:bg-amber-100', 'dark:hover:bg-amber-900/30'],
    '今日已售完': ['bg-red-50', 'dark:bg-red-900/20', 'hover:bg-red-100', 'dark:hover:bg-red-900/30'],
  };

  function positionStatusMenuForButton(menuEl, buttonEl) {
    const rect = buttonEl.getBoundingClientRect();
    menuEl.style.position = 'fixed';
    menuEl.style.left = `${Math.round(rect.left)}px`;
    menuEl.style.width = `${Math.round(rect.width)}px`;

    const margin = 8;
    const belowTop = rect.bottom + margin;
    menuEl.style.top = `${Math.round(belowTop)}px`;

    const menuRect = menuEl.getBoundingClientRect();
    const overflowBottom = menuRect.bottom > window.innerHeight - margin;
    if (overflowBottom) {
      const aboveTop = Math.max(margin, rect.top - margin - menuRect.height);
      menuEl.style.top = `${Math.round(aboveTop)}px`;
    }
  }

  function applyStatusButtonColors(buttonEl, selectedLabel) {
    if (!buttonEl) return;

    // Remove base background to ensure status colors win.
    buttonEl.classList.remove('bg-white', 'dark:bg-slate-900');

    for (const cls of ALL_STATUS_BUTTON_CLASSES) {
      buttonEl.classList.remove(cls);
    }

    const classes = STATUS_BUTTON_CLASSES[selectedLabel];
    if (!classes) return;
    for (const cls of classes) {
      buttonEl.classList.add(cls);
    }
  }

  let currentSearchQuery = '';
  let currentCategoryFilter = '全部';
  let lastCategoryBeforeSearch = null;

  function getSelectedLabel(selectEl) {
    const option = selectEl.options[selectEl.selectedIndex];
    return (option?.textContent || selectEl.value || '').trim();
  }

  function resolveTokenColors(tokenClasses) {
    const probe = document.createElement('span');
    probe.className = tokenClasses.join(' ');
    probe.textContent = 'A';
    probe.style.position = 'absolute';
    probe.style.left = '-99999px';
    probe.style.top = '0';

    document.body.appendChild(probe);
    const computed = window.getComputedStyle(probe);
    const backgroundColor = computed.backgroundColor;
    const color = computed.color;
    probe.remove();

    return { backgroundColor, color };
  }

  function isStatusSelect(selectEl) {
    const labels = Array.from(selectEl.options).map((o) => (o.textContent || '').trim());
    return labels.includes('供應中') && labels.includes('補貨中') && labels.includes('今日已售完');
  }

  function setDropdownOpen(wrapperEl, open) {
    const btn = wrapperEl.querySelector('[data-ui-status-button]');
    if (!btn) return;

    const id = wrapperEl.getAttribute('data-ui-status-id');
    if (!id) return;

    const menu = document.querySelector(
      `[data-ui-status-menu][data-ui-status-id="${CSS.escape(id)}"]`
    );
    if (!menu) return;

    if (open) {
      if (menu.parentElement !== document.body) {
        menu.setAttribute('data-ui-status-portal', 'true');
        document.body.appendChild(menu);
      }

      // Measure & position without flashing.
      menu.classList.remove('hidden');
      const prevVisibility = menu.style.visibility;
      menu.style.visibility = 'hidden';
      positionStatusMenuForButton(menu, btn);
      menu.style.visibility = prevVisibility;
    } else {
      if (menu.getAttribute('data-ui-status-portal') === 'true') {
        menu.removeAttribute('data-ui-status-portal');
        wrapperEl.appendChild(menu);
      }
      menu.classList.add('hidden');
    }

    btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    wrapperEl.setAttribute('data-ui-status-open', open ? 'true' : 'false');

    for (const cls of ACTIVE_DROPDOWN_BUTTON_CLASSES) {
      btn.classList.toggle(cls, Boolean(open));
    }
    for (const cls of INACTIVE_DROPDOWN_BUTTON_CLASSES) {
      btn.classList.toggle(cls, !open);
    }
  }

  function isDropdownOpen(wrapperEl) {
    return wrapperEl.getAttribute('data-ui-status-open') === 'true';
  }

  function upgradeStatusSelectToDropdown(selectEl) {
    if (!selectEl || !(selectEl instanceof HTMLSelectElement)) return;
    if (!isStatusSelect(selectEl)) return;
    if (selectEl.getAttribute(DROPDOWN_READY_ATTR) === 'true') return;

    const parent = selectEl.parentElement;
    if (!parent) return;

    const wrapper = document.createElement('div');
    const wrapperClasses = ['relative', 'w-full'];
    if (selectEl.classList.contains('flex-1')) wrapperClasses.push('flex-1');
    wrapper.className = wrapperClasses.join(' ');
    wrapper.setAttribute('data-ui-status-dropdown', '');

    parent.insertBefore(wrapper, selectEl);
    wrapper.appendChild(selectEl);

    // Keep the original <select> for semantics + any existing logic.
    selectEl.classList.add('sr-only');

    const button = document.createElement('button');
    button.type = 'button';
    button.className = DROPDOWN_BUTTON_CLASSES;
    button.setAttribute('data-ui-status-button', '');
    button.setAttribute('aria-haspopup', 'menu');
    button.setAttribute('aria-expanded', 'false');
    button.disabled = Boolean(selectEl.disabled);

    const label = document.createElement('span');
    label.setAttribute('data-ui-status-label', '');
    label.textContent = getSelectedLabel(selectEl) || '—';

    const chevron = document.createElement('span');
    chevron.className = 'material-symbols-outlined text-base text-slate-400';
    chevron.textContent = 'expand_more';

    button.appendChild(label);
    button.appendChild(chevron);

    const menu = document.createElement('div');
    menu.className = DROPDOWN_MENU_CLASSES;
    menu.setAttribute('data-ui-status-menu', '');
    menu.setAttribute('role', 'menu');

    const dropdownId = `ui-status-${(statusDropdownIdCounter += 1)}`;
    wrapper.setAttribute('data-ui-status-id', dropdownId);
    menu.setAttribute('data-ui-status-id', dropdownId);

    for (const optionEl of Array.from(selectEl.options)) {
      const optionLabel = (optionEl.textContent || optionEl.value || '').trim();
      if (!optionLabel) continue;

      const optBtn = document.createElement('button');
      optBtn.type = 'button';
      optBtn.className = DROPDOWN_OPTION_CLASSES;

      const optionClasses = STATUS_OPTION_CLASSES[optionLabel];
      if (optionClasses) {
        for (const cls of optionClasses) optBtn.classList.add(cls);
      }
      optBtn.textContent = optionLabel;

      optBtn.addEventListener('click', () => {
        selectEl.value = optionEl.value;
        label.textContent = getSelectedLabel(selectEl) || optionLabel;
        selectEl.dispatchEvent(new Event('change', { bubbles: true }));
        setDropdownOpen(wrapper, false);
      });

      menu.appendChild(optBtn);
    }

    wrapper.appendChild(button);
    wrapper.appendChild(menu);

    // Initial (inactive) border state
    for (const cls of INACTIVE_DROPDOWN_BUTTON_CLASSES) {
      button.classList.add(cls);
    }
    wrapper.setAttribute('data-ui-status-open', 'false');

    applyStatusButtonColors(button, getSelectedLabel(selectEl));

    button.addEventListener('click', (e) => {
      e.preventDefault();
      if (button.disabled) return;
      setDropdownOpen(wrapper, !isDropdownOpen(wrapper));
    });

    const repositionIfOpen = () => {
      if (!isDropdownOpen(wrapper)) return;
      positionStatusMenuForButton(menu, button);
    };

    window.addEventListener('scroll', repositionIfOpen, true);
    window.addEventListener('resize', repositionIfOpen);

    document.addEventListener('click', (e) => {
      if (!isDropdownOpen(wrapper)) return;
      const target = e.target;
      if (!(target instanceof Node)) return;
      if (wrapper.contains(target)) return;
      if (menu.contains(target)) return;
      setDropdownOpen(wrapper, false);
    });

    document.addEventListener('keydown', (e) => {
      if (!isDropdownOpen(wrapper)) return;
      if (e.key === 'Escape') setDropdownOpen(wrapper, false);
    });

    selectEl.addEventListener('change', () => {
      label.textContent = getSelectedLabel(selectEl) || '—';
      applyStatusButtonColors(button, getSelectedLabel(selectEl));
    });

    selectEl.setAttribute(DROPDOWN_READY_ATTR, 'true');
  }

  function applyOptionStatusStyles(selectEl) {
    if (!isStatusSelect(selectEl)) return;

    const optionColors = {
      '供應中': resolveTokenColors(STATUS_CLASSES['供應中']),
      '補貨中': resolveTokenColors(STATUS_CLASSES['補貨中']),
      '今日已售完': resolveTokenColors(STATUS_CLASSES['今日已售完']),
    };

    for (const optionEl of Array.from(selectEl.options)) {
      const label = (optionEl.textContent || '').trim();
      const colors = optionColors[label];
      if (!colors) continue;

      // Note: <option> styling support varies by browser/OS.
      optionEl.style.backgroundColor = colors.backgroundColor;
      optionEl.style.color = colors.color;
    }
  }

  function applySelectStatusStyles(selectEl) {
    const label = getSelectedLabel(selectEl);

    // Ensure the select is styleable even if markup changes.
    selectEl.classList.add(
      'text-xs',
      'font-bold',
      'rounded-lg',
      'focus:ring-primary',
      'focus:border-primary'
    );

    for (const cls of ALL_STATUS_CLASSES) {
      selectEl.classList.remove(cls);
    }

    const statusClasses = STATUS_CLASSES[label];
    if (!statusClasses) return;

    for (const cls of statusClasses) {
      selectEl.classList.add(cls);
    }
  }

  function enhanceStatusSelects() {
    const selects = root.querySelectorAll('select');
    for (const selectEl of selects) {
      if (isStatusSelect(selectEl)) {
        upgradeStatusSelectToDropdown(selectEl);
        continue;
      }

      applyOptionStatusStyles(selectEl);
      applySelectStatusStyles(selectEl);
      selectEl.addEventListener('change', () => applySelectStatusStyles(selectEl));
    }
  }

  function reapplyAllOptionStatusStyles() {
    const selects = root.querySelectorAll('select');
    for (const selectEl of selects) {
      if (isStatusSelect(selectEl) && selectEl.getAttribute(DROPDOWN_READY_ATTR) === 'true') {
        continue;
      }
      applyOptionStatusStyles(selectEl);
      applySelectStatusStyles(selectEl);
    }
  }

  function enhanceCategoryCounts() {
    const sections = root.querySelectorAll('section');

    for (const sectionEl of sections) {
      const countBadge = sectionEl.querySelector('[data-category-count]');
      if (!countBadge) continue;

      const tbody = sectionEl.querySelector('tbody');
      if (!tbody) continue;

      const rows = Array.from(tbody.querySelectorAll('tr'));
      const count = rows.filter((tr) => {
        if (tr.classList.contains('hidden')) return false;
        const nameCell = tr.querySelector('td.text-sm.font-semibold');
        return Boolean(nameCell && nameCell.textContent && nameCell.textContent.trim());
      }).length;

      countBadge.textContent = `${count} 項`;
    }
  }

  function normalizeText(text) {
    return String(text || '').trim().toLowerCase();
  }

  function normalizeForSearch(text) {
    return normalizeText(text)
      .replace(/\s+/g, '')
      .replace(/[()（）\[\]【】{}\-–—_.,，。・·•:：/\\]/g, '');
  }

  function fuzzyMatch(haystack, needle) {
    const h = normalizeForSearch(haystack);
    const n = normalizeForSearch(needle);
    if (!n) return true;
    if (!h) return false;

    // Fast path: substring match
    if (h.includes(n)) return true;

    // Fuzzy path: subsequence match (e.g. "珍奶" matches "珍珠奶茶")
    let i = 0;
    for (const ch of n) {
      i = h.indexOf(ch, i);
      if (i === -1) return false;
      i++;
    }
    return true;
  }

  function getRowName(tr) {
    const nameCell =
      tr.querySelector('td.text-sm.font-semibold') ||
      tr.querySelector('td.font-semibold') ||
      tr.querySelector('td');
    return (nameCell?.textContent || '').trim();
  }

  function applySearchFilter(query) {
    const raw = String(query || '');
    const q = normalizeText(raw);
    currentSearchQuery = raw;

    // While searching: do not lock user into a specific category (especially limited/seasonal).
    if (q !== '' && !lastCategoryBeforeSearch) {
      lastCategoryBeforeSearch = currentCategoryFilter;
      currentCategoryFilter = '全部';
    }

    // Search cleared: restore previous category selection.
    if (q === '' && lastCategoryBeforeSearch) {
      currentCategoryFilter = lastCategoryBeforeSearch;
      lastCategoryBeforeSearch = null;
    }

    applyAllFilters(q);
  }

  function getCategoryFilterButtons() {
    return Array.from(root.querySelectorAll('[data-category-filter-button]'));
  }

  function showAllCategoryButtons() {
    const buttons = getCategoryFilterButtons();
    for (const buttonEl of buttons) {
      buttonEl.classList.remove('hidden');
    }
  }

  function showOnlyCategoryButtons(allowedLabels) {
    const allowed = new Set((allowedLabels || []).map((s) => String(s || '').trim()).filter(Boolean));
    const buttons = getCategoryFilterButtons();

    for (const buttonEl of buttons) {
      const label = (buttonEl.textContent || '').trim();
      buttonEl.classList.toggle('hidden', !allowed.has(label));
    }
  }

  function applyAllFilters(normalizedQueryOverride) {
    const qNorm =
      typeof normalizedQueryOverride === 'string'
        ? normalizedQueryOverride
        : normalizeText(currentSearchQuery);

    const sections = root.querySelectorAll('section');
    const matchedCategoryLabels = new Set();

    for (const sectionEl of sections) {
      const categoryLabel = getCategoryLabelFromSection(sectionEl);
      const matchesCategory =
        currentCategoryFilter === '全部' || (categoryLabel && categoryLabel === currentCategoryFilter);

      const tbody = sectionEl.querySelector('tbody');
      if (!tbody) {
        const shouldShow = matchesCategory && qNorm === '';
        sectionEl.classList.toggle('hidden', !shouldShow);
        continue;
      }

      const rows = Array.from(tbody.querySelectorAll('tr'));
      let visibleRowCount = 0;

      for (const tr of rows) {
        const name = getRowName(tr);
        if (!name) {
          tr.classList.remove('hidden');
          continue;
        }

        const matchesSearch = qNorm === '' || fuzzyMatch(name, currentSearchQuery);
        tr.classList.toggle('hidden', !matchesSearch);
        if (matchesSearch) visibleRowCount++;
      }

      if (qNorm !== '' && categoryLabel && visibleRowCount > 0) {
        matchedCategoryLabels.add(categoryLabel);
      }

      // When searching, hide empty sections to keep the page clean.
      const shouldShow = matchesCategory && (qNorm === '' || visibleRowCount > 0);
      sectionEl.classList.toggle('hidden', !shouldShow);
    }

    // While searching: only keep category options that have matches, and hide "全部".
    if (qNorm !== '') {
      const allowedButtons = Array.from(matchedCategoryLabels);
      showOnlyCategoryButtons(allowedButtons);

      // If only one category matches, auto-select it (so the pill shows as active).
      if (allowedButtons.length === 1) {
        currentCategoryFilter = allowedButtons[0];
      }

      // Update visible button active state.
      const buttons = getCategoryFilterButtons();
      for (const buttonEl of buttons) {
        if (buttonEl.classList.contains('hidden')) continue;
        const label = (buttonEl.textContent || '').trim();
        setFilterButtonState(buttonEl, label === currentCategoryFilter);
      }

      // If we auto-selected a category, re-apply section visibility accordingly.
      if (allowedButtons.length === 1) {
        for (const sectionEl of sections) {
          const categoryLabel = getCategoryLabelFromSection(sectionEl);
          const isTarget = categoryLabel && categoryLabel === currentCategoryFilter;
          sectionEl.classList.toggle('hidden', !isTarget);
        }
      }
    } else {
      // Search cleared: restore all category options and active state.
      showAllCategoryButtons();
      const buttons = getCategoryFilterButtons();
      for (const buttonEl of buttons) {
        const label = (buttonEl.textContent || '').trim();
        setFilterButtonState(buttonEl, label === currentCategoryFilter);
      }
    }

    enhanceCategoryCounts();
  }

  function getCategoryLabelFromSection(sectionEl) {
    const h3 = sectionEl.querySelector('h3');
    return (h3?.textContent || '').trim();
  }

  function setFilterButtonState(buttonEl, isActive) {
    const ACTIVE = ['bg-primary', 'text-white', 'shadow-sm', 'shadow-primary/20'];
    const INACTIVE = [
      'bg-slate-100',
      'dark:bg-slate-800',
      'text-slate-600',
      'dark:text-slate-300',
      'hover:bg-primary/10',
      'hover:text-primary',
    ];

    buttonEl.setAttribute('aria-pressed', isActive ? 'true' : 'false');

    buttonEl.classList.remove(...ACTIVE);
    buttonEl.classList.remove(...INACTIVE);
    buttonEl.classList.add(...(isActive ? ACTIVE : INACTIVE));
  }

  function applyCategoryFilter(filterLabel) {
    const normalized = (filterLabel || '').trim();
    currentCategoryFilter = normalized || '全部';

    const buttons = root.querySelectorAll('[data-category-filter-button]');
    for (const buttonEl of buttons) {
      const label = (buttonEl.textContent || '').trim();
      setFilterButtonState(buttonEl, label === normalized);
    }

    applyAllFilters();
  }

  function enhanceMenuSearch() {
    const inputEl = root.querySelector('[data-menu-drinks-search]');
    if (!inputEl) return;

    inputEl.addEventListener('input', () => {
      applySearchFilter(inputEl.value);
    });

    // Initialize from existing value (if any)
    applySearchFilter(inputEl.value);
  }

  function enhanceCategoryFilter() {
    const container = root.querySelector('[data-category-filter]');
    if (!container) return;

    const buttons = Array.from(container.querySelectorAll('[data-category-filter-button]'));
    if (buttons.length === 0) return;

    // Initialize: prefer the one currently styled as active; fallback to "全部".
    let initial = '全部';
    const activeBtn = buttons.find((b) => b.classList.contains('bg-primary'));
    if (activeBtn) initial = (activeBtn.textContent || '').trim() || initial;

    for (const buttonEl of buttons) {
      buttonEl.setAttribute('type', 'button');
      buttonEl.addEventListener('click', () => {
        const label = (buttonEl.textContent || '').trim();
        applyCategoryFilter(label);
      });
    }

    applyCategoryFilter(initial);
  }

  // Initial run
  enhanceCategoryCounts();
  enhanceStatusSelects();
  enhanceCategoryFilter();
  enhanceMenuSearch();

  // Re-apply option colors if light/dark mode toggles.
  const modeObserver = new MutationObserver(() => reapplyAllOptionStatusStyles());
  modeObserver.observe(htmlEl, { attributes: true, attributeFilter: ['class'] });
})();
