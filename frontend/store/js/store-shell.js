/**
 * store-shell.js
 * 門市後台 響應式 Shell：
 *   - 側邊欄縮合（桌面）
 *   - 側邊欄滑入（行動裝置）
 *   - 登出按鈕
 */
(function () {
  const SIDEBAR_COLLAPSED_KEY = 'storeSidebarCollapsed';
  const clearAuth = () => ['store_token', 'store_id'].forEach(k => localStorage.removeItem(k));
  const getLoginUrl = () => {
    try {
      const parts = window.location.pathname.split('/');
      const idx = parts.indexOf('frontend');
      if (idx >= 0) return window.location.origin + parts.slice(0, idx + 1).join('/') + '/Customer/auth/login.html';
    } catch (_) {}
    return '../Customer/auth/login.html';
  };

  // ─── 注入 RWD CSS ──────────────────────────────────────────────────────────
  function injectStyles() {
    if (document.getElementById('store-shell-style')) return;
    const style = document.createElement('style');
    style.id = 'store-shell-style';
    style.textContent = `
      /* ── 全域（桌面 + 行動）────────────────────────────────────────────── */

      /*
       * 關鍵修正 1：icon 在所有狀態都不縮小。
       * 原本只有 collapsed 狀態才設 flex-shrink:0，展開瞬間 icon 失去保護，
       * 被 flex 壓縮變小，造成視覺跳動。對齊品牌端的全域規則：
       *   aside nav a .material-symbols-outlined { flex-shrink: 0; }
       */
      body[data-store-shell-ready="true"] [data-store-sidebar-link] .material-symbols-outlined {
        flex-shrink: 0;
      }

      /*
       * 關鍵修正 2：禁止文字換行。
       * 展開時 label width 從 0 瞬間跳到 auto，若文字在過渡期間的窄寬度下換行，
       * 會造成 link 高度突然變高，所有選項一起往下跳。對齊品牌端：
       *   aside nav a { white-space: nowrap; }
       */
      body[data-store-shell-ready="true"] [data-store-sidebar-link] {
        white-space: nowrap;
      }

      /* 桌面收合動畫 */
      @media (min-width: 1024px) {
        body[data-store-shell-ready="true"] [data-store-sidebar] {
          transition: width 220ms ease, transform 220ms ease, box-shadow 220ms ease;
        }
        body[data-store-shell-ready="true"] [data-store-sidebar-link],
        body[data-store-shell-ready="true"] [data-store-sidebar-label],
        body[data-store-shell-ready="true"] [data-store-sidebar-logo-wrap] img {
          transition: opacity 180ms ease, transform 180ms ease, width 180ms ease, margin 180ms ease, gap 180ms ease;
        }
        body[data-store-shell-ready="true"] [data-store-sidebar-toggle],
        body[data-store-shell-ready="true"] [data-store-sidebar-close] {
          transition: background-color 180ms ease, color 180ms ease, border-color 180ms ease;
        }
        body[data-store-shell-ready="true"] [data-store-page-main] {
          transition: margin-left 220ms ease;
        }
      }

      /* Header toggle 按鈕容器 */
      body[data-store-shell-ready="true"] [data-store-header-start] {
        position: relative;
        min-height: 2.5rem;
      }
      body[data-store-shell-ready="true"] [data-store-sidebar-controls] {
        position: absolute;
        left: 0;
        top: 50%;
        transform: translateY(-50%);
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }

      /* ── 桌面 (≥1024px) ─────────────────────────────────────────────── */
      @media (min-width: 1024px) {
        body[data-store-shell-ready="true"] [data-store-header-start] {
          padding-left: 3.5rem;
        }
        body[data-store-shell-ready="true"] [data-store-page-main] {
          margin-left: 16rem; /* w-64 */
        }

        /* 縮合狀態 */
        body[data-store-shell-ready="true"][data-store-sidebar-collapsed="true"] [data-store-sidebar] {
          width: 5.75rem !important;
        }
        body[data-store-shell-ready="true"][data-store-sidebar-collapsed="true"] [data-store-sidebar-branding] {
          padding-left: 1rem;
          padding-right: 1rem;
        }
        body[data-store-shell-ready="true"][data-store-sidebar-collapsed="true"] [data-store-sidebar-logo-wrap] {
          width: 100%;
        }
        body[data-store-shell-ready="true"][data-store-sidebar-collapsed="true"] [data-store-sidebar-logo-wrap] img {
          height: 3.5rem;
        }
        body[data-store-shell-ready="true"][data-store-sidebar-collapsed="true"] [data-store-sidebar-link] {
          justify-content: center;
          gap: 0;
          padding-left: 0.75rem;
          padding-right: 0.75rem;
        }
        body[data-store-shell-ready="true"][data-store-sidebar-collapsed="true"] [data-store-sidebar-link] .material-symbols-outlined {
          font-size: 1.375rem;
          line-height: 1;
          /* flex-shrink: 0 已由上方全域規則覆蓋，此處無需重複設定 */
        }
        body[data-store-shell-ready="true"][data-store-sidebar-collapsed="true"] [data-store-sidebar-label] {
          opacity: 0;
          width: 0;
          overflow: hidden;
          pointer-events: none;
          /* 移除 line-height: 0：從 0 跳回正常值時會造成 layout 抖動 */
        }
        body[data-store-shell-ready="true"][data-store-sidebar-collapsed="true"] [data-store-sidebar-logout-label] {
          opacity: 0;
          width: 0;
          overflow: hidden;
          pointer-events: none;
        }
        body[data-store-shell-ready="true"][data-store-sidebar-collapsed="true"] [data-store-sidebar-logout-wrap] button {
          justify-content: center;
        }
        body[data-store-shell-ready="true"][data-store-sidebar-collapsed="true"] [data-store-page-main] {
          margin-left: 5.75rem;
        }

        /* 桌面隱藏行動專用元素 */
        body[data-store-shell-ready="true"] [data-store-sidebar-overlay],
        body[data-store-shell-ready="true"] [data-store-sidebar-close],
        body[data-store-shell-ready="true"] [data-store-sidebar-toggle-mobile] {
          display: none !important;
        }
      }

      /* ── 行動裝置 (<1024px) ──────────────────────────────────────────── */
      @media (max-width: 1023.98px) {
        body[data-store-shell-ready="true"] [data-store-header-start] {
          padding-left: 3.25rem;
        }
        body[data-store-shell-ready="true"] [data-store-page-main] {
          margin-left: 0 !important;
        }
        body[data-store-shell-ready="true"] [data-store-sidebar] {
          position: fixed !important;
          top: 0; left: 0; bottom: 0;
          z-index: 50;
          width: min(18rem, calc(100vw - 2.5rem)) !important;
          max-width: calc(100vw - 2.5rem);
          transform: translateX(-110%);
          box-shadow: 0 24px 48px rgba(15, 23, 42, 0.18);
          transition: transform 220ms ease !important;
        }
        body[data-store-shell-ready="true"][data-store-mobile-sidebar-open="true"] [data-store-sidebar] {
          transform: translateX(0);
        }

        /* overlay 遮罩 */
        body[data-store-shell-ready="true"] [data-store-sidebar-overlay] {
          position: fixed; inset: 0; z-index: 49;
          background: rgba(15, 23, 42, 0.45);
          opacity: 0; pointer-events: none;
          transition: opacity 220ms ease;
        }
        body[data-store-shell-ready="true"][data-store-mobile-sidebar-open="true"] [data-store-sidebar-overlay] {
          opacity: 1; pointer-events: auto;
        }

        /* 桌面專用 toggle 在行動隱藏 */
        body[data-store-shell-ready="true"] [data-store-sidebar-toggle-desktop] {
          display: none !important;
        }
      }
    `;
    document.head.appendChild(style);
  }

  // ─── 初始化響應式 Shell ───────────────────────────────────────────────────
  function initResponsiveShell() {
    if (document.body?.dataset.storeShellReady === 'true') return;

    const sidebar = document.querySelector('aside');
    const header  = document.querySelector('header');
    const mainEl  = document.querySelector('main');
    if (!sidebar || !header) return;

    injectStyles();

    // 先標記元素（讓 shell CSS 選擇器就位），再設 shell-ready
    // 兩步在同一 paint frame 完成，避免 boot CSS 移除與 shell CSS 套用之間的閃爍
    sidebar.setAttribute('data-store-sidebar', 'true');
    header.setAttribute('data-store-page-header', 'true');
    if (mainEl) mainEl.setAttribute('data-store-page-main', 'true');

    // body 狀態（設完後 boot CSS 與 shell CSS 同時切換，無縫）
    document.body.dataset.storeShellReady       = 'true';
    document.body.dataset.storeSidebarCollapsed  = localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === 'true' ? 'true' : 'false';
    document.body.dataset.storeMobileSidebarOpen = 'false';

    // Sidebar branding（logo 區塊）
    const branding = sidebar.querySelector(':scope > div');
    if (branding) {
      branding.setAttribute('data-store-sidebar-branding', 'true');
      branding.classList.add('relative');
      const logoWrap = branding.querySelector('a');
      if (logoWrap) logoWrap.setAttribute('data-store-sidebar-logo-wrap', 'true');

      // 行動端關閉按鈕
      if (!branding.querySelector('[data-store-sidebar-close]')) {
        const closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.setAttribute('data-store-sidebar-close', 'true');
        closeBtn.setAttribute('aria-label', '關閉側邊欄');
        closeBtn.className = 'absolute right-3 top-3 inline-flex h-10 w-10 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-500 shadow-sm hover:bg-slate-50 hover:text-slate-900 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300';
        closeBtn.innerHTML = '<span class="material-symbols-outlined text-[20px]">close</span>';
        branding.appendChild(closeBtn);
      }
    }

    // 標記 nav 連結（用於 collapsed 隱藏文字）
    sidebar.querySelectorAll('nav a').forEach(link => {
      link.setAttribute('data-store-sidebar-link', 'true');
      const spans = Array.from(link.children);
      const label = spans.find(el => !el.classList?.contains('material-symbols-outlined'));
      if (label) label.setAttribute('data-store-sidebar-label', 'true');
      const text = label?.textContent?.trim();
      if (text) link.setAttribute('title', text);
    });

    // overlay 遮罩
    let overlay = document.querySelector('[data-store-sidebar-overlay]');
    if (!overlay) {
      overlay = document.createElement('button');
      overlay.type = 'button';
      overlay.setAttribute('data-store-sidebar-overlay', 'true');
      overlay.setAttribute('aria-label', '關閉側邊欄');
      document.body.appendChild(overlay);
    }

    // Header toggle 按鈕
    const leftGroup = header.querySelector(':scope > div') || header.firstElementChild;
    if (leftGroup) {
      leftGroup.setAttribute('data-store-header-start', 'true');
    }

    if (leftGroup && !leftGroup.querySelector('[data-store-sidebar-controls]')) {
      const controls = document.createElement('div');
      controls.setAttribute('data-store-sidebar-controls', 'true');

      // 桌面收合按鈕
      const desktopBtn = document.createElement('button');
      desktopBtn.type = 'button';
      desktopBtn.setAttribute('data-store-sidebar-toggle', 'true');
      desktopBtn.setAttribute('data-store-sidebar-toggle-desktop', 'true');
      desktopBtn.setAttribute('aria-label', '切換側邊欄');
      desktopBtn.className = 'inline-flex h-10 w-10 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 hover:text-primary dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300';

      // 行動漢堡按鈕
      const mobileBtn = document.createElement('button');
      mobileBtn.type = 'button';
      mobileBtn.setAttribute('data-store-sidebar-toggle', 'true');
      mobileBtn.setAttribute('data-store-sidebar-toggle-mobile', 'true');
      mobileBtn.setAttribute('aria-label', '開啟側邊欄');
      mobileBtn.className = 'inline-flex h-10 w-10 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 hover:text-primary dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300';
      mobileBtn.innerHTML = '<span class="material-symbols-outlined text-[20px]">menu</span>';

      controls.appendChild(mobileBtn);
      controls.appendChild(desktopBtn);
      leftGroup.prepend(controls);
    }

    // ─── 狀態同步 ───────────────────────────────────────────────────────────
    const desktopBtn  = header.querySelector('[data-store-sidebar-toggle-desktop]');
    const mobileBtn   = header.querySelector('[data-store-sidebar-toggle-mobile]');
    const closeBtn    = sidebar.querySelector('[data-store-sidebar-close]');
    const desktopMQ   = window.matchMedia('(min-width: 1024px)');

    function syncState() {
      const collapsed  = document.body.dataset.storeSidebarCollapsed === 'true';
      const mobileOpen = document.body.dataset.storeMobileSidebarOpen === 'true';
      if (desktopBtn) {
        desktopBtn.setAttribute('aria-label', collapsed ? '展開側邊欄' : '收合側邊欄');
        desktopBtn.innerHTML = `<span class="material-symbols-outlined text-[20px]">${collapsed ? 'last_page' : 'first_page'}</span>`;
      }
      if (mobileBtn) mobileBtn.setAttribute('aria-expanded', mobileOpen ? 'true' : 'false');
    }

    function setCollapsed(next) {
      document.body.dataset.storeSidebarCollapsed = next ? 'true' : 'false';
      localStorage.setItem(SIDEBAR_COLLAPSED_KEY, next ? 'true' : 'false');
      syncState();
    }

    function setMobileOpen(next) {
      document.body.dataset.storeMobileSidebarOpen = next ? 'true' : 'false';
      syncState();
    }

    desktopBtn?.addEventListener('click', () => {
      if (!desktopMQ.matches) return;
      setCollapsed(document.body.dataset.storeSidebarCollapsed !== 'true');
    });
    mobileBtn?.addEventListener('click', () => {
      if (desktopMQ.matches) return;
      setMobileOpen(true);
    });
    closeBtn?.addEventListener('click', () => setMobileOpen(false));
    overlay.addEventListener('click', () => setMobileOpen(false));
    window.addEventListener('keydown', e => { if (e.key === 'Escape') setMobileOpen(false); });

    const onBreakpoint = e => {
      if (e.matches) setMobileOpen(false);
      syncState();
    };
    if (typeof desktopMQ.addEventListener === 'function') {
      desktopMQ.addEventListener('change', onBreakpoint);
    } else {
      desktopMQ.addListener(onBreakpoint);
    }

    syncState();
  }

  // ─── 登出按鈕 ─────────────────────────────────────────────────────────────
  function initLogoutButton() {
    const sidebar = document.querySelector('aside');
    if (!sidebar) return;

    let wrap = sidebar.querySelector('[data-store-sidebar-logout-wrap]');
    if (!wrap) {
      wrap = document.createElement('div');
      wrap.setAttribute('data-store-sidebar-logout-wrap', 'true');
      wrap.className = 'border-t border-primary/10 px-4 py-4 dark:border-slate-800';
      sidebar.appendChild(wrap);
    }

    let btn = wrap.querySelector('[data-store-sidebar-logout]');
    if (!btn) {
      btn = document.createElement('button');
      btn.type = 'button';
      btn.setAttribute('data-store-sidebar-logout', 'true');
      btn.className = 'flex w-full items-center justify-center gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 transition-colors hover:bg-primary/5 hover:text-primary dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200';
      btn.innerHTML = '<span class="material-symbols-outlined text-[20px]">logout</span><span data-store-sidebar-logout-label>登出</span>';
      wrap.appendChild(btn);
    }

    if (!btn.dataset.boundLogout) {
      btn.addEventListener('click', () => {
        clearAuth();
        window.location.href = getLoginUrl();
      });
      btn.dataset.boundLogout = 'true';
    }
  }

  // ─── 啟動 ─────────────────────────────────────────────────────────────────
  async function initStoreName() {
    const displayEl = document.getElementById('headerStoreNameDisplay');
    if (!displayEl) return;

    // 1. 嘗試從持久快取取得 (StoreAPI 可能尚未載入，需確保順序或檢查)
    if (window.StoreAPI) {
      const snapshot = window.StoreAPI.getProfileSnapshot();
      if (snapshot && snapshot.name) {
        displayEl.textContent = snapshot.name;
      }

      // 2. 背景更新
      try {
        const fresh = await window.StoreAPI.refreshProfile();
        if (fresh && fresh.name) {
          displayEl.textContent = fresh.name;
          window.StoreAPI.saveProfileSnapshot(fresh);
        }
      } catch (err) {
        console.warn('[Shell] 無法更新店名:', err);
      }
    }
  }

  function boot() {
    initResponsiveShell();
    initLogoutButton();
    initStoreName();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();