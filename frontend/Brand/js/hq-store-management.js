(() => {
  const searchInput = document.querySelector('[data-hq-store-search]');
  const regionSelect = document.querySelector('[data-hq-store-region]');
  const regionButton = document.querySelector('[data-hq-store-region-button]');
  const regionLabel = document.querySelector('[data-hq-store-region-label]');
  const table = document.querySelector('[data-hq-store-table]');

  const createModal = document.querySelector('[data-hq-store-create-modal]');
  const createModalBackdrop =
    createModal?.querySelector('[data-hq-store-create-backdrop]') ?? null;
  const createModalCloseButtons = createModal
    ? Array.from(createModal.querySelectorAll('[data-hq-store-create-close]'))
    : [];
  const createModalOpenButton = document.querySelector(
    '[data-hq-store-create-open]'
  );

  const editModal = document.querySelector('[data-hq-store-edit-modal]');
  const editModalBackdrop =
    editModal?.querySelector('[data-hq-store-edit-backdrop]') ?? null;
  const editModalCloseButtons = editModal
    ? Array.from(editModal.querySelectorAll('[data-hq-store-edit-close]'))
    : [];

  const editNameInput = editModal?.querySelector('[data-hq-store-edit-name]') ?? null;
  const editRegionSelect =
    editModal?.querySelector('[data-hq-store-edit-region]') ?? null;
  const editManagerInput =
    editModal?.querySelector('[data-hq-store-edit-manager]') ?? null;
  const editPhoneInput = editModal?.querySelector('[data-hq-store-edit-phone]') ?? null;
  const editAddressInput =
    editModal?.querySelector('[data-hq-store-edit-address]') ?? null;

  let lastFocusedEl = null;

  const normalize = (value) => (value ?? '').toString().trim().toLowerCase();

  const ACTIVE_BUTTON_CLASSES = ['border-primary', 'ring-1', 'ring-primary'];
  const INACTIVE_BUTTON_CLASSES = ['border-slate-200', 'dark:border-slate-700'];

  const REGION_MENU_CLASSES =
    'hidden bg-white dark:bg-slate-800 ' +
    'border border-slate-200 dark:border-slate-700 rounded-xl shadow-sm p-2 z-50 ' +
    'max-h-64 overflow-y-auto';

  const REGION_OPTION_CLASSES =
    'w-full text-left px-3 py-2 rounded-lg text-sm font-medium ' +
    'text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700/50';

  let regionMenuEl = null;
  let regionMenuOpen = false;

  const applyRegionButtonState = (open) => {
    if (!regionButton) return;
    for (const cls of ACTIVE_BUTTON_CLASSES) regionButton.classList.toggle(cls, Boolean(open));
    for (const cls of INACTIVE_BUTTON_CLASSES) regionButton.classList.toggle(cls, !open);
  };

  const positionMenuForButton = (menuEl, buttonEl) => {
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
  };

  const getSelectedOptionLabel = (selectEl) => {
    if (!selectEl || !(selectEl instanceof HTMLSelectElement)) return '';
    return (selectEl.selectedOptions?.[0]?.textContent || '').trim();
  };

  const ensureRegionMenu = () => {
    if (!regionSelect || !regionButton) return null;
    if (regionMenuEl) return regionMenuEl;

    const menu = document.createElement('div');
    menu.className = REGION_MENU_CLASSES;
    menu.setAttribute('role', 'menu');
    menu.setAttribute('data-hq-store-region-menu', '');

    for (const opt of Array.from(regionSelect.options)) {
      const label = (opt.textContent || opt.value || '').trim();
      if (!label) continue;
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = REGION_OPTION_CLASSES;
      btn.textContent = label;
      btn.addEventListener('click', () => {
        regionSelect.value = opt.value;
        if (regionLabel) regionLabel.textContent = label;
        regionSelect.dispatchEvent(new Event('change', { bubbles: true }));
        setRegionMenuOpen(false);
      });
      menu.appendChild(btn);
    }

    document.body.appendChild(menu);
    regionMenuEl = menu;
    return menu;
  };

  const setRegionMenuOpen = (open) => {
    if (!regionButton) return;
    const menu = ensureRegionMenu();
    if (!menu) return;

    regionMenuOpen = Boolean(open);
    regionButton.setAttribute('aria-expanded', regionMenuOpen ? 'true' : 'false');
    applyRegionButtonState(regionMenuOpen);

    if (regionMenuOpen) {
      menu.classList.remove('hidden');
      const prevVisibility = menu.style.visibility;
      menu.style.visibility = 'hidden';
      positionMenuForButton(menu, regionButton);
      menu.style.visibility = prevVisibility;
    } else {
      menu.classList.add('hidden');
    }
  };

  const getRows = () => {
    if (!table) return [];
    const tbody = table.querySelector('tbody');
    if (!tbody) return [];
    return Array.from(tbody.querySelectorAll('tr'));
  };

  const getRowRegion = (row) => {
    const cells = row.querySelectorAll('td');
    const regionCell = cells[2];
    return normalize(regionCell?.textContent);
  };

  const applyFilters = () => {
    const rows = getRows();
    if (rows.length === 0) return;

    const query = normalize(searchInput?.value);
    const regionValue = regionSelect?.value ?? 'all';
    const region = normalize(regionValue);

    for (const row of rows) {
      const rowText = normalize(row.textContent);
      const matchesQuery = !query || rowText.includes(query);
      const matchesRegion = region === 'all' || getRowRegion(row) === region;
      row.classList.toggle('hidden', !(matchesQuery && matchesRegion));
    }
  };

  const openCreateModal = () => {
    if (!createModal) return;
    lastFocusedEl = document.activeElement;
    createModal.classList.remove('hidden');
    document.body.classList.add('overflow-hidden');

    const firstFocusable = createModal.querySelector(
      'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled])'
    );
    queueMicrotask(() => firstFocusable?.focus());
  };

  const closeCreateModal = () => {
    if (!createModal) return;
    createModal.dispatchEvent(new CustomEvent('hq-store-create-modal-will-close'));
    createModal.classList.add('hidden');
    document.body.classList.remove('overflow-hidden');
    (lastFocusedEl ?? createModalOpenButton)?.focus?.();
    lastFocusedEl = null;
  };

  const regionTextToValue = (text) => {
    const normalized = normalize(text);
    if (normalized === normalize('北部')) return 'north';
    if (normalized === normalize('中部')) return 'central';
    if (normalized === normalize('南部')) return 'south';
    return '';
  };

  const openEditModal = ({ name, regionText, manager, phone }) => {
    if (!editModal) return;
    lastFocusedEl = document.activeElement;

    if (editNameInput) editNameInput.value = name ?? '';
    if (editRegionSelect) {
      const value = regionTextToValue(regionText);
      editRegionSelect.value = value;
    }
    if (editManagerInput) editManagerInput.value = manager ?? '';
    if (editPhoneInput) editPhoneInput.value = phone ?? '';
    if (editAddressInput) editAddressInput.value = '';

    editModal.classList.remove('hidden');
    document.body.classList.add('overflow-hidden');

    queueMicrotask(() => editNameInput?.focus?.());
  };

  const closeEditModal = () => {
    if (!editModal) return;
    editModal.classList.add('hidden');
    document.body.classList.remove('overflow-hidden');
    lastFocusedEl?.focus?.();
    lastFocusedEl = null;
  };

  // Filters
  searchInput?.addEventListener('input', applyFilters);
  regionSelect?.addEventListener('change', applyFilters);
  applyFilters();

  // Region dropdown (custom menu for rounded corners)
  if (regionLabel && regionSelect) {
    regionLabel.textContent = getSelectedOptionLabel(regionSelect) || '所有區域';
  }

  if (regionButton && regionSelect) {
    // Ensure default inactive border classes exist (since Tailwind classes are static)
    for (const cls of INACTIVE_BUTTON_CLASSES) regionButton.classList.add(cls);

    regionButton.addEventListener('click', (e) => {
      e.preventDefault();
      setRegionMenuOpen(!regionMenuOpen);
    });

    const repositionIfOpen = () => {
      if (!regionMenuOpen) return;
      if (!regionMenuEl) return;
      positionMenuForButton(regionMenuEl, regionButton);
    };
    window.addEventListener('scroll', repositionIfOpen, true);
    window.addEventListener('resize', repositionIfOpen);

    document.addEventListener('click', (e) => {
      if (!regionMenuOpen) return;
      const target = e.target;
      if (!(target instanceof Node)) return;
      if (regionButton.contains(target)) return;
      if (regionMenuEl && regionMenuEl.contains(target)) return;
      setRegionMenuOpen(false);
    });
  }

  // Modal open/close
  createModalOpenButton?.addEventListener('click', openCreateModal);
  for (const btn of createModalCloseButtons) {
    btn.addEventListener('click', closeCreateModal);
  }

  for (const btn of editModalCloseButtons) {
    btn.addEventListener('click', closeEditModal);
  }

  createModalBackdrop?.addEventListener('click', (e) => {
    if (e.target === createModalBackdrop) closeCreateModal();
  });

  editModalBackdrop?.addEventListener('click', (e) => {
    if (e.target === editModalBackdrop) closeEditModal();
  });

  // Table edit (event delegation)
  table?.addEventListener('click', (e) => {
    const trigger = e.target.closest?.('[data-hq-store-edit-open]');
    if (!trigger) return;

    e.preventDefault();
    const row = trigger.closest('tr');
    if (!row) return;

    const cells = row.querySelectorAll('td');
    const name = cells[0]?.textContent?.trim() ?? '';
    const regionText = cells[2]?.textContent?.trim() ?? '';
    const manager = cells[3]?.textContent?.trim() ?? '';
    const phone = cells[4]?.textContent?.trim() ?? '';
    openEditModal({ name, regionText, manager, phone });
  });

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;

    if (regionMenuOpen) {
      setRegionMenuOpen(false);
      return;
    }

    if (editModal && !editModal.classList.contains('hidden')) {
      closeEditModal();
      return;
    }

    if (createModal && !createModal.classList.contains('hidden')) {
      closeCreateModal();
    }
  });
})();
