/**
 * store-shell-boot.js
 * 在 <head> 最早載入，避免 sidebar 閃爍。
 * 同時取代各頁面內嵌的 MutationObserver 預處理腳本。
 */
(function () {
  try {
    var root = document.documentElement;
    var isDesktop = window.matchMedia('(min-width: 1024px)').matches;
    var collapsed = localStorage.getItem('storeSidebarCollapsed') === 'true';

    root.dataset.storeShellBoot = 'true';
    root.dataset.storeShellBootViewport = isDesktop ? 'desktop' : 'mobile';
    root.dataset.storeShellBootCollapsed = collapsed ? 'true' : 'false';

    // ─── 最小化 CSS：防止 header 跳動 & mobile sidebar 閃爍 ────────────
    if (!document.getElementById('store-shell-boot-style')) {
      var s = document.createElement('style');
      s.id = 'store-shell-boot-style';
      s.textContent = [
        /* ── 全域 RWD 調整 ── */
        'header { padding-left: 1rem !important; padding-right: 1rem !important; height: 4rem !important; }',
        '@media (max-width: 400px) {',
        '  #storeStatusToggle { padding-left: 0.5rem !important; padding-right: 0.5rem !important; }',
        '  header h3 { font-size: 0.9rem !important; }',
        '}',
        /* ── 防止行動版換頁閃爍：boot CSS 預先把 ml-64 歸零，不等 shell-ready ── */
        '@media (max-width: 1023.98px) {',
        '  html[data-store-shell-boot="true"] body:not([data-store-shell-ready="true"]) main {',
        '    margin-left: 0 !important;',
        '  }',
        '}',
        'html[data-store-shell-boot="true"] body header > div:first-child {',
        '  position: relative;',
        '  min-height: 2.5rem;',
        '  padding-left: 3.5rem;',
        '}',
        '@media (max-width: 1023.98px) {',
        '  html[data-store-shell-boot="true"] body header > div:first-child {',
        '    padding-left: 3.25rem;',
        '  }',
        '}',
        /* ── 桌面 collapsed 預設：在 shell-ready 前就套用正確寬度，避免換頁時播放收合動畫 ── */
        '@media (min-width: 1024px) {',
        '  html[data-store-shell-boot="true"][data-store-shell-boot-collapsed="true"] body:not([data-store-shell-ready="true"]) aside {',
        '    width: 5.75rem !important;',
        '  }',
        '  html[data-store-shell-boot="true"][data-store-shell-boot-collapsed="true"] body:not([data-store-shell-ready="true"]) aside > div:first-child {',
        '    padding-left: 1rem;',
        '    padding-right: 1rem;',
        '  }',
        '  html[data-store-shell-boot="true"][data-store-shell-boot-collapsed="true"] body:not([data-store-shell-ready="true"]) aside > div:first-child a {',
        '    width: 100%;',
        '  }',
        '  html[data-store-shell-boot="true"][data-store-shell-boot-collapsed="true"] body:not([data-store-shell-ready="true"]) aside > div:first-child img {',
        '    height: 3.5rem;',
        '  }',
        '  html[data-store-shell-boot="true"][data-store-shell-boot-collapsed="true"] body:not([data-store-shell-ready="true"]) aside nav a {',
        '    justify-content: center;',
        '    gap: 0;',
        '    padding-left: 0.75rem;',
        '    padding-right: 0.75rem;',
        '    white-space: nowrap;',
        '  }',
        '  html[data-store-shell-boot="true"][data-store-shell-boot-collapsed="true"] body:not([data-store-shell-ready="true"]) aside nav a span:not(.material-symbols-outlined) {',
        '    opacity: 0;',
        '    width: 0;',
        '    overflow: hidden;',
        '    pointer-events: none;',
        '  }',
        '  html[data-store-shell-boot="true"][data-store-shell-boot-collapsed="true"] body:not([data-store-shell-ready="true"]) aside nav a .material-symbols-outlined {',
        '    font-size: 1.375rem;',
        '    line-height: 1;',
        '    flex-shrink: 0;',
        '  }',
        '  html[data-store-shell-boot="true"][data-store-shell-boot-collapsed="true"] body:not([data-store-shell-ready="true"]) main {',
        '    margin-left: 5.75rem !important;',
        '  }',
        '}'
      ].join('\n');
      document.head.appendChild(s);
    }

    // ─── Sidebar active 預載入（取代各頁內嵌 script）──────────────────
    var page = (window.location.pathname.split('/').pop() || 'home.html').toLowerCase();
    var ACTIVE   = 'flex items-center gap-3 px-3 py-2.5 rounded-lg bg-primary text-white text-base font-medium shadow-lg shadow-primary/20';
    var INACTIVE = 'flex items-center gap-3 px-3 py-2.5 rounded-lg text-base font-medium text-slate-600 dark:text-slate-400 hover:bg-primary/10 hover:text-primary transition-colors';

    function getTargetIcon(name) {
      if (name === 'home.html' || name === '' || name === '/') return 'dashboard';
      if (name.startsWith('store-'))   return 'store';
      if (name.startsWith('menu-'))    return 'menu_book';
      if (name.startsWith('order-'))   return 'assignment';
      if (name.startsWith('finance-')) return 'payments';
      if (name.startsWith('reports-')) return 'monitoring';
      return null;
    }

    var targetIcon = getTargetIcon(page);
    
    // ─── 狀態與頭像快取極速套用 (防止換頁閃爍) ──────────────────
    var LOGO_CACHE_KEY = 'cached_brand_logo';
    var STATUS_CACHE_KEY = 'cached_store_status';
    
    var observer = new MutationObserver(function () {
      // 1. Sidebar Active 處理
      if (targetIcon) {
        var nav = document.querySelector('aside nav');
        if (nav) {
          nav.querySelectorAll('a').forEach(function (link) {
            var icon = link.querySelector('span.material-symbols-outlined');
            if (icon && icon.textContent.trim() === targetIcon) link.className = ACTIVE;
            else if (icon) link.className = INACTIVE;
          });
        }
      }

      // 2. Header 頭像與營業狀態快取套用
      var avatarImg = document.querySelector('header img[alt="用戶頭像"]');
      var statusBtn = document.getElementById('storeStatusToggle');
      
      if (avatarImg) {
        var cachedLogo = localStorage.getItem(LOGO_CACHE_KEY);
        if (cachedLogo && !avatarImg.dataset.bootApplied) {
          avatarImg.src = cachedLogo;
          avatarImg.classList.add('object-contain', 'p-1', 'bg-white');
          avatarImg.dataset.bootApplied = 'true';
        }
      }

      if (statusBtn) {
        var cachedStatus = localStorage.getItem(STATUS_CACHE_KEY);
        if (cachedStatus && !statusBtn.dataset.bootApplied) {
          try {
            var data = JSON.parse(cachedStatus);
            var isOpen = data.status === 'active' || data.status === 'open';
            var dot = statusBtn.querySelector('#storeStatusDot');
            var label = statusBtn.querySelector('#storeStatusLabel');
            
            statusBtn.setAttribute('data-status', isOpen ? 'active' : 'closed');
            statusBtn.className = 'flex items-center px-4 py-2 rounded-lg border transition-colors ' + 
                                  (isOpen ? 'bg-green-50 border-green-200 hover:bg-green-100' : 'bg-red-50 border-red-200 hover:bg-red-100');
            
            if (dot) dot.className = 'w-2 h-2 rounded-full mr-2 ' + (isOpen ? 'bg-green-500' : 'bg-red-500');
            if (label) {
              label.textContent = isOpen ? '營業中' : '休息中';
              label.className = 'text-sm font-bold ' + (isOpen ? 'text-green-700' : 'text-red-700');
            }
            statusBtn.dataset.bootApplied = 'true';
          } catch(e) {}
        }
      }

      // 如果全部都處理完了，可以考慮停止（但因為單頁應用可能會動態切換，這裡建議持續監聽一段時間或在頁面 ready 後停止）
      if (document.readyState === 'complete' || (avatarImg && statusBtn && (!targetIcon || document.querySelector('aside nav')))) {
        // 頁面完全載入後停止監聽，交給 api-store-status.js 負責後續更新
        setTimeout(function() { observer.disconnect(); }, 1000);
      }
    });

    observer.observe(root, { childList: true, subtree: true });
  } catch (_) { /* ignore */ }
})();
