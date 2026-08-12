const BrandAPI = (() => {
  const BASE = 'http://localhost:8082';

  const getToken     = () => localStorage.getItem('brandToken') || '';
  const getBrandId   = () => localStorage.getItem('brandId') || '';
  const getBrandName = () => localStorage.getItem('brandName') || 'Admin';
  const getBrandLogo = () => localStorage.getItem('brandLogoUrl') || '';
  const BRAND_MENU_SETUP_PAGE = 'hq-specs-and-toppings-management.html';
  const SIDEBAR_COLLAPSED_KEY = 'brandSidebarCollapsed';

  function getBrandLoginPage() {
    const { origin, pathname } = window.location;
    const normalizedPath = String(pathname || '/').replace(/\\/g, '/');
    const parts = normalizedPath.split('/').filter(Boolean);
    const frontendIdx = parts.lastIndexOf('frontend');
    if (frontendIdx >= 0) {
      return `${origin}/${parts.slice(0, frontendIdx + 1).join('/')}/Customer/auth/login.html`;
    }
    const brandIdx = parts.lastIndexOf('Brand');
    if (brandIdx >= 0) {
      const prefix = parts.slice(0, brandIdx).join('/');
      return `${origin}/${prefix ? `${prefix}/` : ''}Customer/auth/login.html`;
    }
    return `${origin}/Customer/auth/login.html`;
  }

  function injectResponsiveShellStyles() {
    if (document.getElementById('brand-shell-responsive-style')) return;

    const style = document.createElement('style');
    style.id = 'brand-shell-responsive-style';
    style.textContent = `
      body[data-brand-shell-ready="true"] [data-brand-sidebar-link] .material-symbols-outlined {
        flex-shrink: 0;
      }

      body[data-brand-shell-ready="true"] [data-brand-sidebar-link] {
        white-space: nowrap;
      }

      body[data-brand-shell-ready="true"] [data-brand-sidebar-label],
      body[data-brand-shell-ready="true"] [data-brand-sidebar-logout-label] {
        overflow: hidden;
      }

      body[data-brand-shell-ready="true"] [data-brand-sidebar] {
        overflow: hidden;
        transition: width 220ms ease, transform 220ms ease, box-shadow 220ms ease;
      }

      body[data-brand-shell-ready="true"] [data-brand-sidebar-link],
      body[data-brand-shell-ready="true"] [data-brand-sidebar-label],
      body[data-brand-shell-ready="true"] [data-brand-sidebar-logout-label],
      body[data-brand-shell-ready="true"] [data-brand-sidebar-logo-wrap] img {
        transition: opacity 180ms ease, transform 180ms ease, width 180ms ease, margin 180ms ease, gap 180ms ease;
      }

      body[data-brand-shell-ready="true"] [data-brand-sidebar-toggle],
      body[data-brand-shell-ready="true"] [data-brand-sidebar-close] {
        transition: background-color 180ms ease, color 180ms ease, border-color 180ms ease;
      }

      body[data-brand-shell-ready="true"] [data-brand-header-start] {
        position: relative;
        min-height: 2.5rem;
      }

      body[data-brand-shell-ready="true"] [data-brand-sidebar-controls] {
        position: absolute;
        left: 0;
        top: 50%;
        display: flex;
        align-items: center;
        gap: 0.5rem;
        transform: translateY(-50%);
      }

      @media (min-width: 1024px) {
        body[data-brand-shell-ready="true"] [data-brand-header-start] {
          padding-left: 3.5rem;
        }

        body[data-brand-shell-ready="true"][data-brand-sidebar-collapsed="true"] [data-brand-sidebar] {
          width: 5.75rem !important;
        }

        body[data-brand-shell-ready="true"][data-brand-sidebar-collapsed="true"] [data-brand-sidebar-branding] {
          padding-left: 1rem;
          padding-right: 1rem;
        }

        body[data-brand-shell-ready="true"][data-brand-sidebar-collapsed="true"] [data-brand-sidebar-logo-wrap] {
          width: 100%;
        }

        body[data-brand-shell-ready="true"][data-brand-sidebar-collapsed="true"] [data-brand-sidebar-logo-wrap] img {
          height: 3.5rem;
        }

        body[data-brand-shell-ready="true"][data-brand-sidebar-collapsed="true"] [data-brand-sidebar-link] {
          justify-content: center;
          gap: 0;
          padding-left: 0.75rem;
          padding-right: 0.75rem;
        }

        body[data-brand-shell-ready="true"][data-brand-sidebar-collapsed="true"] [data-brand-sidebar-label] {
          opacity: 0;
          width: 0;
          overflow: hidden;
          pointer-events: none;
        }
        body[data-brand-shell-ready="true"][data-brand-sidebar-collapsed="true"] [data-brand-sidebar-logout-label] {
          opacity: 0;
          width: 0;
          overflow: hidden;
          pointer-events: none;
        }
        body[data-brand-shell-ready="true"][data-brand-sidebar-collapsed="true"] [data-brand-sidebar-logout-wrap] button {
          justify-content: center;
        }

        body[data-brand-shell-ready="true"] [data-brand-sidebar-overlay],
        body[data-brand-shell-ready="true"] [data-brand-sidebar-close],
        body[data-brand-shell-ready="true"] [data-brand-sidebar-toggle-mobile] {
          display: none !important;
        }
      }

      @media (max-width: 1023.98px) {
        body[data-brand-shell-ready="true"] [data-brand-page-header] {
          padding-left: 1rem;
          padding-right: 1rem;
        }

        body[data-brand-shell-ready="true"] [data-brand-header-start] {
          padding-left: 3.25rem;
        }

        body[data-brand-shell-ready="true"] [data-brand-sidebar-toggle-desktop] {
          display: none !important;
        }

        body[data-brand-shell-ready="true"] [data-brand-sidebar-overlay] {
          position: fixed;
          inset: 0;
          z-index: 39;
          background: rgba(15, 23, 42, 0.45);
          opacity: 0;
          pointer-events: none;
          transition: opacity 220ms ease;
        }

        body[data-brand-shell-ready="true"] [data-brand-sidebar] {
          position: fixed !important;
          top: 0;
          left: 0;
          bottom: 0;
          z-index: 40;
          width: min(18rem, calc(100vw - 2.5rem)) !important;
          max-width: calc(100vw - 2.5rem);
          transform: translateX(-110%);
          box-shadow: 0 24px 48px rgba(15, 23, 42, 0.18);
        }

        body[data-brand-shell-ready="true"][data-brand-mobile-sidebar-open="true"] [data-brand-sidebar] {
          transform: translateX(0);
        }

        body[data-brand-shell-ready="true"][data-brand-mobile-sidebar-open="true"] [data-brand-sidebar-overlay] {
          opacity: 1;
          pointer-events: auto;
        }
      }
    `;

    document.head.appendChild(style);
  }

  function initResponsiveShell() {
    if (document.body?.dataset.brandShellReady === 'true') return;

    const sidebar = document.querySelector('aside');
    const header = document.querySelector('header');
    if (!sidebar || !header) return;

    injectResponsiveShellStyles();

    document.body.dataset.brandShellReady = 'true';
    document.body.dataset.brandSidebarCollapsed = localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === 'true' ? 'true' : 'false';
    document.body.dataset.brandMobileSidebarOpen = 'false';

    sidebar.setAttribute('data-brand-sidebar', 'true');
    header.setAttribute('data-brand-page-header', 'true');

    const branding = sidebar.querySelector(':scope > div');
    if (branding) {
      branding.setAttribute('data-brand-sidebar-branding', 'true');
      branding.classList.add('relative');
      const logoWrap = branding.querySelector('a');
      if (logoWrap) {
        logoWrap.setAttribute('data-brand-sidebar-logo-wrap', 'true');
      }

      if (!branding.querySelector('[data-brand-sidebar-close]')) {
        const closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.setAttribute('data-brand-sidebar-close', 'true');
        closeBtn.className = 'absolute right-3 top-3 inline-flex h-10 w-10 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-500 shadow-sm hover:bg-slate-50 hover:text-slate-900 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800';
        closeBtn.setAttribute('aria-label', '關閉側邊欄');
        closeBtn.innerHTML = '<span class="material-symbols-outlined text-[20px]">close</span>';
        branding.appendChild(closeBtn);
      }
    }

    sidebar.querySelectorAll('nav a').forEach(link => {
      link.setAttribute('data-brand-sidebar-link', 'true');
      const label = Array.from(link.children).find(child => !child.classList?.contains('material-symbols-outlined'));
      const text = label?.textContent?.trim();
      if (label) label.setAttribute('data-brand-sidebar-label', 'true');
      if (text) link.setAttribute('title', text);
    });

    let overlay = document.querySelector('[data-brand-sidebar-overlay]');
    if (!overlay) {
      overlay = document.createElement('button');
      overlay.type = 'button';
      overlay.setAttribute('data-brand-sidebar-overlay', 'true');
      overlay.setAttribute('aria-label', '關閉側邊欄遮罩');
      document.body.appendChild(overlay);
    }

    const leftGroup = header.querySelector(':scope > div') || header.firstElementChild;
    if (leftGroup) {
      leftGroup.setAttribute('data-brand-header-start', 'true');
    }

    if (leftGroup && !leftGroup.querySelector('[data-brand-sidebar-controls]')) {
      const controls = document.createElement('div');
      controls.setAttribute('data-brand-sidebar-controls', 'true');

      const desktopBtn = document.createElement('button');
      desktopBtn.type = 'button';
      desktopBtn.setAttribute('data-brand-sidebar-toggle', 'true');
      desktopBtn.setAttribute('data-brand-sidebar-toggle-desktop', 'true');
      desktopBtn.className = 'inline-flex h-10 w-10 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 hover:text-slate-900 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800';
      desktopBtn.setAttribute('aria-label', '切換側邊欄');

      const mobileBtn = document.createElement('button');
      mobileBtn.type = 'button';
      mobileBtn.setAttribute('data-brand-sidebar-toggle', 'true');
      mobileBtn.setAttribute('data-brand-sidebar-toggle-mobile', 'true');
      mobileBtn.className = 'inline-flex h-10 w-10 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 hover:text-slate-900 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800';
      mobileBtn.setAttribute('aria-label', '開啟側邊欄');

      mobileBtn.innerHTML = '<span class="material-symbols-outlined text-[20px]">menu</span>';
      controls.appendChild(mobileBtn);
      controls.appendChild(desktopBtn);
      leftGroup.prepend(controls);
    }

    const desktopBtn = header.querySelector('[data-brand-sidebar-toggle-desktop]');
    const mobileBtn = header.querySelector('[data-brand-sidebar-toggle-mobile]');
    const closeBtn = sidebar.querySelector('[data-brand-sidebar-close]');
    const desktopMedia = window.matchMedia('(min-width: 1024px)');

    function syncShellState() {
      const collapsed = document.body.dataset.brandSidebarCollapsed === 'true';
      const mobileOpen = document.body.dataset.brandMobileSidebarOpen === 'true';

      if (desktopBtn) {
        desktopBtn.setAttribute('aria-label', collapsed ? '展開側邊欄' : '收合側邊欄');
        desktopBtn.innerHTML = `<span class="material-symbols-outlined text-[20px]">${collapsed ? 'last_page' : 'first_page'}</span>`;
      }

      if (mobileBtn) {
        mobileBtn.setAttribute('aria-expanded', mobileOpen ? 'true' : 'false');
      }
    }

    function setCollapsed(nextCollapsed) {
      document.body.dataset.brandSidebarCollapsed = nextCollapsed ? 'true' : 'false';
      localStorage.setItem(SIDEBAR_COLLAPSED_KEY, nextCollapsed ? 'true' : 'false');
      syncShellState();
    }

    function setMobileOpen(nextOpen) {
      document.body.dataset.brandMobileSidebarOpen = nextOpen ? 'true' : 'false';
      syncShellState();
    }

    desktopBtn?.addEventListener('click', () => {
      if (!desktopMedia.matches) return;
      setCollapsed(document.body.dataset.brandSidebarCollapsed !== 'true');
    });

    mobileBtn?.addEventListener('click', () => {
      if (desktopMedia.matches) return;
      setMobileOpen(true);
    });

    closeBtn?.addEventListener('click', () => setMobileOpen(false));
    overlay.addEventListener('click', () => setMobileOpen(false));
    window.addEventListener('keydown', event => {
      if (event.key === 'Escape') {
        setMobileOpen(false);
      }
    });

    const handleBreakpointChange = event => {
      if (event.matches) {
        setMobileOpen(false);
      }
      syncShellState();
    };

    if (typeof desktopMedia.addEventListener === 'function') {
      desktopMedia.addEventListener('change', handleBreakpointChange);
    } else if (typeof desktopMedia.addListener === 'function') {
      desktopMedia.addListener(handleBreakpointChange);
    }

    syncShellState();
  }

  function saveAuth(data) {
    // 登入時清除所有舊快取，避免 stale data
    Object.keys(sessionStorage)
      .filter(k => k.startsWith('brandCache:'))
      .forEach(k => sessionStorage.removeItem(k));
    localStorage.setItem('brandToken', data.token);
    try {
      const p = JSON.parse(atob(data.token.split('.')[1]));
      localStorage.setItem('brandId', p.userId || p.sub || '');
    } catch(_) {}
    if (data.name)    localStorage.setItem('brandName', data.name);
    if (data.logoUrl) localStorage.setItem('brandLogoUrl', data.logoUrl);
  }
  const clearAuth = () => ['brandToken','brandId','brandName','brandLogoUrl'].forEach(k => localStorage.removeItem(k));
  const requireAuth = () => { if (!getToken()) window.location.href = getBrandLoginPage(); };

  function getPageScrollContainer() {
    return document.querySelector('main > .flex-1.overflow-y-auto')
      || document.querySelector('main .overflow-y-auto')
      || document.querySelector('main');
  }

  function initSidebarLogoutButton() {
    const sidebar = document.querySelector('aside[data-brand-sidebar], aside');
    if (!(sidebar instanceof HTMLElement)) return;

    let wrap = sidebar.querySelector('[data-brand-sidebar-logout-wrap]');
    if (!wrap) {
      wrap = document.createElement('div');
      wrap.setAttribute('data-brand-sidebar-logout-wrap', 'true');
      wrap.className = 'border-t border-primary/10 px-4 py-4 dark:border-slate-800';
      sidebar.appendChild(wrap);
    }

    let button = wrap.querySelector('[data-brand-sidebar-logout]');
    if (!button) {
      button = document.createElement('button');
      button.type = 'button';
      button.setAttribute('data-brand-sidebar-logout', 'true');
      button.className = 'flex w-full items-center justify-center gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 transition-colors hover:bg-primary/5 hover:text-primary dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200';
      button.innerHTML = '<span class="material-symbols-outlined text-[20px]">logout</span><span data-brand-sidebar-logout-label>登出</span>';
      wrap.appendChild(button);
    }

    if (!button.dataset.boundLogout) {
      button.addEventListener('click', () => {
        clearCache();
        clearAuth();
        window.location.href = getBrandLoginPage();
      });
      button.dataset.boundLogout = 'true';
    }
  }

  function initScrollTopButton() {
    if (!document.body) return;

    let button = document.getElementById('brand-scroll-top-button');
    if (!button) {
      button = document.createElement('button');
      button.id = 'brand-scroll-top-button';
      button.type = 'button';
      button.setAttribute('aria-label', '回到頁面頂端');
      button.className = 'fixed bottom-6 right-6 z-40 inline-flex h-12 w-12 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-700 shadow-lg shadow-slate-900/10 transition-all duration-200 hover:-translate-y-0.5 hover:bg-slate-50 hover:text-primary focus:outline-none focus:ring-2 focus:ring-primary/40 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800 opacity-0 pointer-events-none translate-y-3';
      button.innerHTML = '<span class="material-symbols-outlined text-[22px]">keyboard_arrow_up</span>';
      button.hidden = true;
      button.setAttribute('aria-hidden', 'true');
      document.body.appendChild(button);
    }

    button.setAttribute('aria-label', '回到頁面頂端');

    const scrollContainer = getPageScrollContainer();
    const scrollTop = () => {
      if (scrollContainer instanceof HTMLElement) {
        scrollContainer.scrollTo({ top: 0, behavior: 'smooth' });
        return;
      }
      window.scrollTo({ top: 0, behavior: 'smooth' });
    };

    if (!button.dataset.boundClick) {
      button.addEventListener('click', scrollTop);
      button.dataset.boundClick = 'true';
    }

    const toggleVisibility = () => {
      const scrollHeight = scrollContainer instanceof HTMLElement
        ? scrollContainer.scrollHeight
        : document.documentElement.scrollHeight;
      const clientHeight = scrollContainer instanceof HTMLElement
        ? scrollContainer.clientHeight
        : window.innerHeight;
      const scrollTopValue = scrollContainer instanceof HTMLElement
        ? scrollContainer.scrollTop
        : window.scrollY;
      const canScroll = scrollHeight - clientHeight > 120;
      const isVisible = canScroll && scrollTopValue > 240;
      button.hidden = !isVisible;
      button.setAttribute('aria-hidden', isVisible ? 'false' : 'true');
      button.classList.toggle('opacity-0', !isVisible);
      button.classList.toggle('pointer-events-none', !isVisible);
      button.classList.toggle('translate-y-3', !isVisible);
    };

    if (button._scrollTopVisibilityTarget && button._scrollTopVisibilityTarget !== scrollContainer && button._scrollTopVisibilityHandler) {
      button._scrollTopVisibilityTarget.removeEventListener('scroll', button._scrollTopVisibilityHandler);
    }

    if (button._scrollTopResizeHandler) {
      window.removeEventListener('resize', button._scrollTopResizeHandler);
      button._scrollTopResizeHandler = null;
    }

    if (scrollContainer instanceof HTMLElement) {
      scrollContainer.addEventListener('scroll', toggleVisibility, { passive: true });
      button._scrollTopVisibilityTarget = scrollContainer;
      button._scrollTopVisibilityHandler = toggleVisibility;
    } else if (!button.dataset.boundWindowVisibility) {
      window.addEventListener('scroll', toggleVisibility, { passive: true });
      button.dataset.boundWindowVisibility = 'true';
    }

    if (!button._scrollTopResizeHandler) {
      window.addEventListener('resize', toggleVisibility);
      button._scrollTopResizeHandler = toggleVisibility;
    }

    toggleVisibility();
  }

  function renderAdminHeader() {
    document.querySelectorAll('[data-brand-admin-name]').forEach(el => el.textContent = getBrandName());
    const logo = getBrandLogo();
    document.querySelectorAll('[data-brand-admin-avatar]').forEach(img => {
      img.src = logo || '';
      img.style.display = logo ? '' : 'none';
    });
    document.querySelectorAll('aside a[href="hq-menu-overview.html"]').forEach(link => {
      link.setAttribute('href', BRAND_MENU_SETUP_PAGE);
    });
    initResponsiveShell();
    initSidebarLogoutButton();
    initScrollTopButton();
  }

  function hasAnyEnabledSpecs(specs) {
    return ['ICE', 'SWEETNESS', 'SIZE'].some(type =>
      Array.isArray(specs?.[type]) && specs[type].some(spec => spec?.isEnabled === true)
    );
  }

  // ─── SessionStorage 快取 ───────────────────────────────────
  const CACHE_TTL    = 5 * 60 * 1000; // 5 分鐘
  const CACHE_PREFIX = 'brandCache:v2:';
  const PERSISTENT_CACHE_TTL = 24 * 60 * 60 * 1000;
  const PERSISTENT_CACHE_PREFIX = 'brandPersistentCache:v1:';
  const _cacheKey = key => `${getBrandId() || 'anonymous'}:${key}`;
  const _persistentCacheKey = key => PERSISTENT_CACHE_PREFIX + _cacheKey(key);
  const resourceRefreshInFlight = new Map();

  function _cacheGet(key) {
    try {
      const raw = sessionStorage.getItem(CACHE_PREFIX + _cacheKey(key));
      if (!raw) return null;
      const { data, exp } = JSON.parse(raw);
      if (Date.now() > exp) { sessionStorage.removeItem(CACHE_PREFIX + _cacheKey(key)); return null; }
      return data;
    } catch { return null; }
  }

  function _cacheSet(key, data) {
    try {
      sessionStorage.setItem(CACHE_PREFIX + _cacheKey(key), JSON.stringify({ data, exp: Date.now() + CACHE_TTL }));
    } catch {} // storage full — skip
  }

  /** 精確刪除一或多個 key */
  function _persistentCacheGet(key) {
    try {
      const raw = localStorage.getItem(_persistentCacheKey(key));
      if (!raw) return null;
      const { data, exp } = JSON.parse(raw);
      if (Date.now() > exp) {
        localStorage.removeItem(_persistentCacheKey(key));
        return null;
      }
      return data;
    } catch { return null; }
  }

  function _persistentCacheSet(key, data) {
    try {
      localStorage.setItem(_persistentCacheKey(key), JSON.stringify({ data, exp: Date.now() + PERSISTENT_CACHE_TTL }));
    } catch {}
  }

  function _persistentCacheDel(...keys) {
    keys.forEach(k => localStorage.removeItem(_persistentCacheKey(k)));
  }

  function _cacheDel(...keys) {
    keys.forEach(k => sessionStorage.removeItem(CACHE_PREFIX + _cacheKey(k)));
  }

  /** 刪除所有以 prefix 開頭的 key（例如刪除某商品所有 detail 快取） */
  function _cacheDelPrefix(prefix) {
    const scopedPrefix = CACHE_PREFIX + _cacheKey(prefix);
    Object.keys(sessionStorage)
      .filter(k => k.startsWith(scopedPrefix))
      .forEach(k => sessionStorage.removeItem(k));
  }

  function _persistentCacheDelPrefix(prefix) {
    const scopedPrefix = _persistentCacheKey(prefix);
    Object.keys(localStorage)
      .filter(k => k.startsWith(scopedPrefix))
      .forEach(k => localStorage.removeItem(k));
  }

  // ─── 底層 fetch ────────────────────────────────────────────
  async function request(path, opts = {}) {
    const headers = { 'Content-Type': 'application/json', ...(opts.headers||{}) };
    const token = getToken();
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const res = await fetch(BASE + path, { ...opts, headers });
    const json = await res.json();
    if (!res.ok || json.code !== '200') {
      if (res.status === 401 || res.status === 403) { clearAuth(); window.location.href = getBrandLoginPage(); }
      throw new Error(json.msg || `HTTP ${res.status}`);
    }
    return json.data;
  }

  /** GET 自動走快取；非 GET 直接打 API */
  async function cachedRequest(path, opts = {}) {
    const method = (opts.method || 'GET').toUpperCase();
    if (method !== 'GET') return request(path, opts);
    const hit = _cacheGet(path);
    if (hit !== null) return hit;
    const data = await request(path, opts);
    _cacheSet(path, data);
    return data;
  }

  async function refreshCachedRequest(path, opts = {}) {
    const method = (opts.method || 'GET').toUpperCase();
    if (method !== 'GET') return request(path, opts);

    const inFlightKey = `${method}:${path}`;
    const existing = resourceRefreshInFlight.get(inFlightKey);
    if (existing) return existing;

    const task = request(path, opts)
      .then((data) => {
        _cacheSet(path, data);
        return data;
      })
      .finally(() => {
        resourceRefreshInFlight.delete(inFlightKey);
      });

    resourceRefreshInFlight.set(inFlightKey, task);
    return task;
  }

  // ─── Auth ──────────────────────────────────────────────────
  async function login(account, password) {
    const res = await fetch(BASE + '/api/brand-auth/login', {
      method: 'POST', headers: {'Content-Type':'application/json'},
      body: JSON.stringify({account, password})
    });
    const json = await res.json();
    if (json.code !== '200') throw new Error(json.msg);
    saveAuth(json.data);
    return json.data;
  }

  // ─── Dashboard ────────────────────────────────────────────
  const getDashboardOverview = () => cachedRequest('/api/brand/dashboard/overview');
  const getTopStores         = () => cachedRequest('/api/brand/dashboard/top-stores');
  const getStoreStatus       = () => cachedRequest('/api/brand/dashboard/storestatus');

  // ─── Categories ───────────────────────────────────────────
  const getCategoriesSnapshot = () => _persistentCacheGet('/api/brand/categories');
  const saveCategoriesSnapshot = (data) => _persistentCacheSet('/api/brand/categories', data);
  const refreshCategories = () => refreshCachedRequest('/api/brand/categories').then(r => { saveCategoriesSnapshot(r); return r; });
  const getCategories  = () => cachedRequest('/api/brand/categories').then(r => { saveCategoriesSnapshot(r); return r; });
  const createCategory = (n, region = {}) => request('/api/brand/categories', {method:'POST', body:JSON.stringify({
    names: [n],
    northOffset:   Number(region.north   ?? 0),
    centralOffset: Number(region.central ?? 0),
    southOffset:   Number(region.south   ?? 0),
  })}).then(r => { _cacheDel('/api/brand/categories', '/api/brand/products'); _persistentCacheDel('/api/brand/categories', '/api/brand/products'); _cacheDelPrefix('/api/brand/products/'); _persistentCacheDelPrefix('/api/brand/products/'); return r; });
  const renameCategory = (id, name, region = {}) => request(`/api/brand/categories/${id}`, {method:'PUT', body:JSON.stringify({
    name,
    northOffset:   Number(region.north   ?? 0),
    centralOffset: Number(region.central ?? 0),
    southOffset:   Number(region.south   ?? 0),
  })}).then(r => { _cacheDel('/api/brand/categories', '/api/brand/products'); _persistentCacheDel('/api/brand/categories', '/api/brand/products'); _cacheDelPrefix('/api/brand/products/'); _persistentCacheDelPrefix('/api/brand/products/'); return r; });
  const moveCategoryUp = id => request(`/api/brand/categories/${id}/move-up`, {method:'PATCH'})
    .then(r => { _cacheDel('/api/brand/categories', '/api/brand/products'); _persistentCacheDel('/api/brand/categories', '/api/brand/products'); _cacheDelPrefix('/api/brand/products/'); _persistentCacheDelPrefix('/api/brand/products/'); return r; });
  const moveCategoryDown = id => request(`/api/brand/categories/${id}/move-down`, {method:'PATCH'})
    .then(r => { _cacheDel('/api/brand/categories', '/api/brand/products'); _persistentCacheDel('/api/brand/categories', '/api/brand/products'); _cacheDelPrefix('/api/brand/products/'); _persistentCacheDelPrefix('/api/brand/products/'); return r; });
  const reorderCategories = orderedCategoryIds => request('/api/brand/categories/reorder', {
    method:'PATCH',
    body: JSON.stringify({ orderedCategoryIds })
  }).then(r => { _cacheDel('/api/brand/categories', '/api/brand/products'); _persistentCacheDel('/api/brand/categories', '/api/brand/products'); _cacheDelPrefix('/api/brand/products/'); _persistentCacheDelPrefix('/api/brand/products/'); return r; });
  const deleteCategory = id => request(`/api/brand/categories/${id}`, {method:'DELETE'})
    .then(r => { _cacheDel('/api/brand/categories', '/api/brand/products'); _persistentCacheDel('/api/brand/categories', '/api/brand/products'); _cacheDelPrefix('/api/brand/products/'); _persistentCacheDelPrefix('/api/brand/products/'); return r; });

  // ─── Products ─────────────────────────────────────────────
  const getProductsSnapshot = () => _persistentCacheGet('/api/brand/products');
  const saveProductsSnapshot = (data) => _persistentCacheSet('/api/brand/products', data);
  const refreshProducts = () => refreshCachedRequest('/api/brand/products').then(r => { saveProductsSnapshot(r); return r; });
  const getProducts      = () => cachedRequest('/api/brand/products').then(r => { saveProductsSnapshot(r); return r; });
  const getProductDetail = id => cachedRequest(`/api/brand/products/${id}/detail`);
  const refreshProductDetail = id => refreshCachedRequest(`/api/brand/products/${id}/detail`);
  const createProduct    = p  => request('/api/brand/products', {method:'POST', body:JSON.stringify(p)})
    .then(r => { _cacheDel('/api/brand/products'); _persistentCacheDel('/api/brand/products'); return r; });
  const updateProduct    = (id, p) => request(`/api/brand/products/${id}`, {method:'PUT', body:JSON.stringify(p)})
    .then(r => { _cacheDel('/api/brand/products', `/api/brand/products/${id}/detail`); _persistentCacheDel('/api/brand/products', `/api/brand/products/${id}/detail`); return r; });
  const moveProductUp    = id => request(`/api/brand/products/${id}/move-up`, {method:'PATCH'})
    .then(r => { _cacheDel('/api/brand/products', `/api/brand/products/${id}/detail`); _persistentCacheDel('/api/brand/products', `/api/brand/products/${id}/detail`); return r; });
  const moveProductDown  = id => request(`/api/brand/products/${id}/move-down`, {method:'PATCH'})
    .then(r => { _cacheDel('/api/brand/products', `/api/brand/products/${id}/detail`); _persistentCacheDel('/api/brand/products', `/api/brand/products/${id}/detail`); return r; });
  const deleteProduct    = id => request(`/api/brand/products/${id}`, {method:'DELETE'})
    .then(r => { _cacheDel('/api/brand/products', `/api/brand/products/${id}/detail`); _persistentCacheDel('/api/brand/products', `/api/brand/products/${id}/detail`); return r; });

  // ─── Specs & Toppings ─────────────────────────────────────
  const getSpecsSnapshot = () => _persistentCacheGet('/api/brand/specs');
  const saveSpecsSnapshot = (data) => _persistentCacheSet('/api/brand/specs', data);
  const refreshSpecs = () => refreshCachedRequest('/api/brand/specs').then(r => { saveSpecsSnapshot(r); return r; });
  const getSpecs = () => cachedRequest('/api/brand/specs').then(r => { saveSpecsSnapshot(r); return r; });
  const addSpec  = (type, name) => request('/api/brand/specs', {method:'POST', body:JSON.stringify({type, name})})
    .then(r => { _cacheDel('/api/brand/specs'); _persistentCacheDel('/api/brand/specs'); return r; });
  const updateSpec    = (id, name) => request(`/api/brand/specs/${id}`, {method:'PATCH', body:JSON.stringify({name})})
    .then(r => { _cacheDel('/api/brand/specs'); _persistentCacheDel('/api/brand/specs'); return r; });
  const reorderSpecs  = (type, orderedSpecIds) => request('/api/brand/specs/reorder', {method:'PATCH', body:JSON.stringify({type, orderedSpecIds})})
    .then(r => { _cacheDel('/api/brand/specs'); _persistentCacheDel('/api/brand/specs'); return r; });
  const deleteSpec    = id => request(`/api/brand/specs/${id}`, {method:'DELETE'})
    .then(r => { _cacheDel('/api/brand/specs'); _persistentCacheDel('/api/brand/specs'); return r; });
  const toggleSpec    = id => request(`/api/brand/specs/${id}/toggle`, {method:'PATCH'})
    .then(r => { _cacheDel('/api/brand/specs'); _persistentCacheDel('/api/brand/specs'); return r; });
  const getToppingsSnapshot = () => _persistentCacheGet('/api/brand/toppings');
  const saveToppingsSnapshot = (data) => _persistentCacheSet('/api/brand/toppings', data);
  const refreshToppings = () => refreshCachedRequest('/api/brand/toppings').then(r => { saveToppingsSnapshot(r); return r; });
  const getToppings = () => cachedRequest('/api/brand/toppings').then(r => { saveToppingsSnapshot(r); return r; });
  const addTopping  = (name, price) => request('/api/brand/toppings', {method:'POST', body:JSON.stringify({name, price})})
    .then(r => { _cacheDel('/api/brand/toppings'); _persistentCacheDel('/api/brand/toppings'); return r; });
  const updateTopping = (id, name, price) => request(`/api/brand/toppings/${id}`, {method:'PATCH', body:JSON.stringify({name, price})})
    .then(r => { _cacheDel('/api/brand/toppings'); _persistentCacheDel('/api/brand/toppings'); return r; });
  const deleteTopping = id => request(`/api/brand/toppings/${id}`, {method:'DELETE'})
    .then(r => { _cacheDel('/api/brand/toppings'); _persistentCacheDel('/api/brand/toppings'); return r; });
  const toggleTopping = id => request(`/api/brand/toppings/${id}/toggle`, {method:'PATCH'})
    .then(r => { _cacheDel('/api/brand/toppings'); _persistentCacheDel('/api/brand/toppings'); return r; });

  // Reputation
  const getBrandReputation = (period = 'all') => cachedRequest(`/api/brand/reputation?period=${period}`);

  // ─── Stores & Regions ─────────────────────────────────────
  const getRegions     = () => cachedRequest('/api/brand/regions');
  const getBrandDetail = () => cachedRequest('/api/brand/stores');
  const createStore    = body     => request('/api/brand-auth/create-store', {method:'POST', body:JSON.stringify(body)})
    .then(r => { _cacheDel('/api/brand/stores'); return r; });
  async function createStoreWithImages(body, files = {}) {
    const token = getToken();
    const form = new FormData();

    Object.entries(body || {}).forEach(([key, value]) => {
      if (value === null || value === undefined || value === '') return;
      form.append(key, String(value));
    });

    Object.entries(files || {}).forEach(([key, file]) => {
      if (!file) return;
      form.append(key, file);
    });

    const res = await fetch(BASE + '/api/brand-auth/create-store-with-images', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}` },
      body: form,
    });
    const json = await res.json();
    if (!res.ok || json.code !== '200') {
      if (res.status === 401 || res.status === 403) { clearAuth(); window.location.href = getBrandLoginPage(); }
      throw new Error(json.msg || `HTTP ${res.status}`);
    }
    _cacheDel('/api/brand/stores');
    return json.data;
  }
  const updateStore    = (id, body) => request(`/api/brand/stores/${id}`, {method:'PUT', body:JSON.stringify(body)})
    .then(r => { _cacheDel('/api/brand/stores'); return r; });

  // ─── Image Upload ─────────────────────────────────────────
  async function uploadImage(file, folder = 'stores') {
    const token = getToken();
    const form = new FormData();
    form.append('file', file);
    form.append('folder', folder);
    const res = await fetch(BASE + '/api/brand/upload-image', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}` },
      body: form,
    });
    const json = await res.json();
    if (!res.ok || json.code !== '200') throw new Error(json.msg || `HTTP ${res.status}`);
    return json.data;
  }

  // ─── Finance & Analytics ──────────────────────────────────
  const getFinanceFull     = (p='month') => cachedRequest(`/api/brand/finance/full?period=${p}`);
  const getFinanceOverview = (p='month') => cachedRequest(`/api/brand/finance/overview?period=${p}`);
  const getFinanceTrend    = (p='month') => cachedRequest(`/api/brand/finance/trend?period=${p}`);
  const getFinanceRegions  = (p='month') => cachedRequest(`/api/brand/finance/regions?period=${p}`);
  const getProductRanking  = (p='month') => cachedRequest(`/api/brand/analytics/products?period=${p}`);

  // ─── Toast ────────────────────────────────────────────────
  function toast(msg, type='success') {
    const el = document.createElement('div');
    el.className = `fixed bottom-6 right-6 z-[9999] px-5 py-3 rounded-xl shadow-lg text-sm font-bold text-white ${type==='success'?'bg-green-500':'bg-red-500'}`;
    el.textContent = msg;
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 3000);
  }

  function showInfoDialog(message, options = {}) {
    const {
      title = '系統提示',
      confirmText = '確定',
      dismissible = true,
    } = options;

    document.querySelector('[data-brand-info-dialog]')?.remove();

    return new Promise((resolve) => {
      const root = document.createElement('div');
      root.setAttribute('data-brand-info-dialog', 'true');
      root.className = 'fixed inset-0 z-[10000] flex items-center justify-center p-4';
      root.innerHTML = `
        <div class="absolute inset-0 bg-slate-900/45 backdrop-blur-sm" data-brand-info-dialog-backdrop></div>
        <div class="relative w-full max-w-lg overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl">
          <div class="flex items-center justify-between gap-4 border-b border-slate-100 px-6 py-5">
            <div>
              <h3 class="text-xl font-bold text-slate-900">${title}</h3>
            </div>
            ${dismissible ? `
              <button type="button" data-brand-info-dialog-close class="inline-flex h-10 w-10 items-center justify-center rounded-full text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700" aria-label="關閉">
                <span class="material-symbols-outlined">close</span>
              </button>
            ` : ''}
          </div>
          <div class="px-6 py-6">
            <p class="whitespace-pre-line text-base leading-8 text-slate-700">${message}</p>
          </div>
          <div class="flex items-center justify-end gap-3 border-t border-slate-100 px-6 py-5">
            <button type="button" data-brand-info-dialog-confirm class="rounded-xl bg-primary px-6 py-2.5 text-base font-bold text-white transition-colors hover:bg-primary/90">
              ${confirmText}
            </button>
          </div>
        </div>
      `;

      const close = () => {
        root.remove();
        resolve();
      };

      root.querySelector('[data-brand-info-dialog-confirm]')?.addEventListener('click', close, { once: true });
      root.querySelector('[data-brand-info-dialog-close]')?.addEventListener('click', close, { once: true });
      if (dismissible) {
        root.querySelector('[data-brand-info-dialog-backdrop]')?.addEventListener('click', close, { once: true });
      }

      document.body.appendChild(root);
    });
  }

  /** 手動清除所有品牌快取（登出時呼叫） */
  function clearCache() {
    Object.keys(sessionStorage)
      .filter(k => k.startsWith(CACHE_PREFIX))
      .forEach(k => sessionStorage.removeItem(k));
    Object.keys(localStorage)
      .filter(k => k.startsWith(PERSISTENT_CACHE_PREFIX))
      .forEach(k => localStorage.removeItem(k));
  }

  return {
    getToken, getBrandId, getBrandName, getBrandLogo, saveAuth, clearAuth, requireAuth, renderAdminHeader,
    login,
    getDashboardOverview, getTopStores, getStoreStatus,
    getCategories, getCategoriesSnapshot, saveCategoriesSnapshot, refreshCategories, createCategory, renameCategory, moveCategoryUp, moveCategoryDown, reorderCategories, deleteCategory,
    getProducts, getProductsSnapshot, saveProductsSnapshot, refreshProducts, getProductDetail, refreshProductDetail, createProduct, updateProduct, moveProductUp, moveProductDown, deleteProduct,
    getSpecs, getSpecsSnapshot, saveSpecsSnapshot, refreshSpecs, addSpec, updateSpec, reorderSpecs, deleteSpec, toggleSpec,
    getToppings, getToppingsSnapshot, saveToppingsSnapshot, refreshToppings, addTopping, updateTopping, deleteTopping, toggleTopping,
    getBrandReputation,
    getRegions, getBrandDetail, createStore, createStoreWithImages, updateStore, uploadImage,
    getFinanceFull, getFinanceOverview, getFinanceTrend, getFinanceRegions, getProductRanking,
    toast, showInfoDialog, clearCache, hasAnyEnabledSpecs, BRAND_MENU_SETUP_PAGE
  };
})();
