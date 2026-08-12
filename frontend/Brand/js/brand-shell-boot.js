(function () {
  try {
    var isDesktop = window.matchMedia('(min-width: 1024px)').matches;
    var collapsed = window.localStorage.getItem('brandSidebarCollapsed') === 'true';
    var root = document.documentElement;

    root.dataset.brandShellBoot = 'true';
    root.dataset.brandShellBootViewport = isDesktop ? 'desktop' : 'mobile';
    root.dataset.brandShellBootCollapsed = collapsed ? 'true' : 'false';

    if (!document.getElementById('brand-shell-boot-style')) {
      var style = document.createElement('style');
      style.id = 'brand-shell-boot-style';
      style.textContent = `
        html[data-brand-shell-boot="true"] body header > div:first-child {
          position: relative;
          min-height: 2.5rem;
          padding-left: 3.5rem;
        }

        @media (max-width: 1023.98px) {
          html[data-brand-shell-boot="true"] body header > div:first-child {
            padding-left: 3.25rem;
          }
        }
      `;
      document.head.appendChild(style);
    }
  } catch (_) {
    // Ignore storage or media query failures and fall back to static layout.
  }
})();
