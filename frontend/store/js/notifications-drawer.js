(() => {
  const STORAGE_KEY = 'btpro.notifications.v1';
  const DRAWER_ATTR = 'data-notifications-drawer';

  const bellButtons = Array.from(document.querySelectorAll('[data-notifications-bell]'));
  if (bellButtons.length === 0) return;

  const safeParse = (json) => {
    try {
      return JSON.parse(json);
    } catch {
      return null;
    }
  };

  const readStore = () => {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { v: 1, items: [] };

    const parsed = safeParse(raw);
    if (!parsed || typeof parsed !== 'object') return { v: 1, items: [] };

    const items = Array.isArray(parsed.items) ? parsed.items : [];
    return { v: 1, items };
  };

  const writeStore = (next, meta = {}) => {
    const payload = {
      v: 1,
      updatedAt: Date.now(),
      items: Array.isArray(next?.items) ? next.items : [],
      ...meta,
    };

    localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
    window.dispatchEvent(
      new CustomEvent('btpro:notifications-changed', {
        detail: { source: meta.source || 'local' },
      })
    );
  };

  const getItems = () => readStore().items.slice();

  const setItems = (items, source = 'set') => {
    writeStore({ items: Array.isArray(items) ? items.slice() : [] }, { source });
  };

  const hasUnread = (items) => (items || []).some((n) => Boolean(n && n.unread));

  const updateAllDots = () => {
    const items = getItems();
    const show = hasUnread(items);

    for (const bell of bellButtons) {
      const dot = bell.querySelector('[data-notifications-dot]');
      if (!dot) continue;
      dot.classList.toggle('hidden', !show);
    }
  };

  const headerEl = document.querySelector('header.sticky') || document.querySelector('header');

  const ensureDrawer = () => {
    const existing = document.querySelector(`[${DRAWER_ATTR}]`);
    if (existing) return existing;

    const drawer = document.createElement('aside');
    drawer.setAttribute(DRAWER_ATTR, '');
    drawer.className =
      'hidden fixed right-0 bg-white dark:bg-slate-900 border-l border-primary/10 shadow-sm w-[360px] max-w-[90vw] overflow-hidden z-30';

    drawer.innerHTML = `
      <div class="h-full flex flex-col">
        <div class="px-5 py-4 border-b border-primary/10">
          <div class="flex items-center gap-2" data-notifications-tabs>
            <button type="button" data-notifications-tab="platform" class="px-4 py-1.5 rounded-lg text-sm font-bold bg-primary/10 text-primary whitespace-nowrap transition-colors" aria-selected="true">平台通知</button>
            <button type="button" data-notifications-tab="order" class="px-4 py-1.5 rounded-lg text-sm font-medium text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800 whitespace-nowrap transition-colors" aria-selected="false">訂單通知</button>
          </div>
        </div>
        <div class="flex-1 overflow-y-auto" data-notifications-list></div>
      </div>
    `;

    document.body.appendChild(drawer);
    return drawer;
  };

  const ACTIVE_CLASSES = ['bg-primary/10', 'text-primary', 'font-bold'];
  const INACTIVE_CLASSES = [
    'text-slate-500',
    'dark:text-slate-400',
    'hover:bg-slate-50',
    'dark:hover:bg-slate-800',
    'font-medium',
  ];

  const setTabState = (btn, isActive) => {
    for (const cls of ACTIVE_CLASSES) btn.classList.toggle(cls, isActive);
    for (const cls of INACTIVE_CLASSES) btn.classList.toggle(cls, !isActive);
    btn.setAttribute('aria-selected', String(isActive));
  };

  const escapeHtml = (value) => {
    return String(value || '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  };

  const formatTime = (n) => {
    const raw = (n && (n.timeLabel || n.time || n.createdAt)) ?? '';
    if (!raw) return '';

    if (typeof raw === 'string') return raw;

    if (typeof raw === 'number') {
      const d = new Date(raw);
      const hh = String(d.getHours()).padStart(2, '0');
      const mm = String(d.getMinutes()).padStart(2, '0');
      return `${hh}:${mm}`;
    }

    return '';
  };

  const renderList = (drawer, tabKey) => {
    const listEl = drawer.querySelector('[data-notifications-list]');
    if (!listEl) return;

    const items = getItems()
      .filter((n) => String(n?.type || '').trim() === tabKey)
      .sort((a, b) => Number(b?.createdAt || 0) - Number(a?.createdAt || 0));

    if (items.length === 0) {
      listEl.innerHTML = `
        <div class="p-6">
          <div class="text-sm font-medium text-slate-600 dark:text-slate-400">目前沒有通知</div>
        </div>
      `;
      return;
    }

    listEl.innerHTML = items
      .map((n) => {
        const title = escapeHtml(n.title || '通知');
        const body = escapeHtml(n.body || '');
        const time = escapeHtml(formatTime(n));

        return `
          <div class="px-5 py-4 border-b border-primary/5">
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <div class="text-sm font-bold text-slate-900 dark:text-white truncate">${title}</div>
                ${body ? `<div class="mt-1 text-sm text-slate-600 dark:text-slate-400">${body}</div>` : ''}
              </div>
              ${time ? `<div class="text-xs font-medium text-slate-400 shrink-0">${time}</div>` : ''}
            </div>
          </div>
        `;
      })
      .join('');
  };

  const applyDrawerLayout = (drawer) => {
    const headerHeight = headerEl ? headerEl.getBoundingClientRect().height : 0;
    drawer.style.top = `${Math.max(0, headerHeight)}px`;
    drawer.style.height = `calc(100vh - ${Math.max(0, headerHeight)}px)`;
  };

  let isOpen = false;
  let currentTab = 'platform';

  const openDrawer = () => {
    const drawer = ensureDrawer();
    applyDrawerLayout(drawer);

    drawer.classList.remove('hidden');
    isOpen = true;

    // Mark all as read when user opens the drawer.
    const items = getItems();
    const changed = items.map((n) => (n ? { ...n, unread: false } : n));
    setItems(changed, 'mark-read');

    renderList(drawer, currentTab);
    updateAllDots();
  };

  const closeDrawer = () => {
    const drawer = document.querySelector(`[${DRAWER_ATTR}]`);
    if (!drawer) return;
    drawer.classList.add('hidden');
    isOpen = false;
  };

  const toggleDrawer = () => {
    if (isOpen) closeDrawer();
    else openDrawer();
  };

  const setupDrawerTabs = (drawer) => {
    const tabsRoot = drawer.querySelector('[data-notifications-tabs]');
    if (!tabsRoot) return;

    const tabButtons = Array.from(tabsRoot.querySelectorAll('button[data-notifications-tab]'));
    if (tabButtons.length === 0) return;

    const applyTab = (tabKey) => {
      currentTab = tabKey;
      for (const btn of tabButtons) {
        const key = (btn.getAttribute('data-notifications-tab') || '').trim();
        setTabState(btn, key === tabKey);
      }
      renderList(drawer, currentTab);
    };

    tabsRoot.addEventListener('click', (event) => {
      const target = event.target instanceof Element ? event.target.closest('button[data-notifications-tab]') : null;
      if (!target) return;
      const tabKey = (target.getAttribute('data-notifications-tab') || '').trim();
      if (!tabKey) return;
      applyTab(tabKey);
    });

    applyTab(currentTab);
  };

  for (const bell of bellButtons) {
    bell.addEventListener('click', () => {
      const drawer = ensureDrawer();
      setupDrawerTabs(drawer);
      toggleDrawer();
    });
  }

  window.addEventListener('resize', () => {
    const drawer = document.querySelector(`[${DRAWER_ATTR}]`);
    if (!drawer || drawer.classList.contains('hidden')) return;
    applyDrawerLayout(drawer);
  });

  window.addEventListener('btpro:notifications-changed', () => {
    updateAllDots();

    const drawer = document.querySelector(`[${DRAWER_ATTR}]`);
    if (!drawer || drawer.classList.contains('hidden')) return;
    renderList(drawer, currentTab);
  });

  window.addEventListener('storage', (event) => {
    if (event.key !== STORAGE_KEY) return;
    window.dispatchEvent(new CustomEvent('btpro:notifications-changed', { detail: { source: 'storage' } }));
  });

  // Public helper (optional): add notifications from console.
  window.BTNotifications = {
    getItems,
    addPlatform: (title, body, timeLabel) => {
      const items = getItems();
      items.unshift({
        id: String(Date.now()),
        type: 'platform',
        title: String(title || '平台通知'),
        body: String(body || ''),
        timeLabel: timeLabel ? String(timeLabel) : '',
        createdAt: Date.now(),
        unread: true,
      });
      setItems(items, 'add');
    },
    addOrder: (title, body, timeLabel) => {
      const items = getItems();
      items.unshift({
        id: String(Date.now()),
        type: 'order',
        title: String(title || '訂單通知'),
        body: String(body || ''),
        timeLabel: timeLabel ? String(timeLabel) : '',
        createdAt: Date.now(),
        unread: true,
      });
      setItems(items, 'add');
    },
    markAllRead: () => {
      const items = getItems().map((n) => (n ? { ...n, unread: false } : n));
      setItems(items, 'mark-read');
    },
  };

  // Initialize dot state on load.
  updateAllDots();
})();
