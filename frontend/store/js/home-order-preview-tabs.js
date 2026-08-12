(() => {
  const preview = document.querySelector('[data-home-order-preview]');
  if (!preview) return;

  const tabsRoot = preview.querySelector('[data-home-order-tabs]');
  if (!tabsRoot) return;

  const buttons = Array.from(tabsRoot.querySelectorAll('button[data-home-order-tab]'));
  if (buttons.length === 0) return;

  const panels = Array.from(preview.querySelectorAll('[data-home-order-panel]'));
  if (panels.length === 0) return;

  const MAX_PREVIEW_ORDERS = 3;

  const getPanelListRoot = (panelEl) => {
    if (!panelEl) return null;
    if (panelEl.classList.contains('divide-y')) return panelEl;

    const children = Array.from(panelEl.children);
    const divideRoot = children.find(
      (el) => el instanceof HTMLElement && el.classList.contains('divide-y')
    );
    return divideRoot || panelEl;
  };

  const limitPanelToMaxOrders = (panelEl) => {
    const listRoot = getPanelListRoot(panelEl);
    if (!listRoot) return;

    const items = Array.from(listRoot.children).filter(
      (el) => el instanceof HTMLElement && el.classList.contains('p-6')
    );

    items.forEach((itemEl, idx) => {
      itemEl.classList.toggle('hidden', idx >= MAX_PREVIEW_ORDERS);
    });
  };

  // Sliding background indicator (local to Home order preview tabs)
  const ensureIndicator = () => {
    const existing = tabsRoot.querySelector('[data-home-order-tabs-indicator]');
    if (existing) return existing;

    const indicator = document.createElement('span');
    indicator.setAttribute('data-home-order-tabs-indicator', '');
    indicator.className = 'absolute inset-y-0 left-0 bg-red-50 dark:bg-red-900/10 pointer-events-none';
    indicator.style.width = '0px';
    indicator.style.transform = 'translate3d(0px, 0px, 0px)';
    indicator.style.willChange = 'transform, width';
    indicator.style.transitionProperty = 'transform, width';
    indicator.style.transitionDuration = '240ms';
    indicator.style.transitionTimingFunction = 'cubic-bezier(0.22, 1, 0.36, 1)';
    indicator.style.zIndex = '0';

    tabsRoot.insertBefore(indicator, tabsRoot.firstChild);
    return indicator;
  };

  const indicator = ensureIndicator();

  let currentTabKey = null;

  // Ensure the indicator can be positioned and clipped.
  if (getComputedStyle(tabsRoot).position === 'static') {
    tabsRoot.style.position = 'relative';
  }
  tabsRoot.classList.add('overflow-hidden');

  for (const btn of buttons) {
    btn.classList.add('relative');
    btn.style.zIndex = '1';
  }

  const ACTIVE_CLASSES = ['border-primary', 'text-red-600'];
  const INACTIVE_CLASSES = [
    'border-transparent',
    'text-slate-500',
    'dark:text-slate-400',
    'hover:bg-slate-50',
    'dark:hover:bg-slate-700/50',
  ];

  const LEGACY_ACTIVE_BG_CLASSES = ['bg-red-50', 'dark:bg-red-900/10'];

  const setButtonState = (button, isActive) => {
    // Ensure legacy markup active background is never stuck on inactive tabs.
    for (const cls of LEGACY_ACTIVE_BG_CLASSES) button.classList.remove(cls);

    for (const cls of ACTIVE_CLASSES) button.classList.toggle(cls, isActive);
    for (const cls of INACTIVE_CLASSES) button.classList.toggle(cls, !isActive);
    button.setAttribute('aria-selected', String(isActive));
  };

  const moveIndicatorToButton = (button) => {
    if (!indicator || !button) return;
    indicator.style.width = `${button.offsetWidth}px`;
    indicator.style.transform = `translate3d(${button.offsetLeft}px, 0px, 0px)`;
  };

  const applyTab = (tabKey) => {
    currentTabKey = tabKey;
    let activeButton = null;
    for (const button of buttons) {
      const isActive = (button.getAttribute('data-home-order-tab') || '') === tabKey;
      setButtonState(button, isActive);
      if (isActive) activeButton = button;
    }

    for (const panel of panels) {
      const panelKey = panel.getAttribute('data-home-order-panel') || '';
      panel.classList.toggle('hidden', panelKey !== tabKey);
    }

    // Ensure the preview never shows more than 3 orders per panel.
    for (const panel of panels) {
      limitPanelToMaxOrders(panel);
    }

    if (activeButton) {
      requestAnimationFrame(() => moveIndicatorToButton(activeButton));
    }
  };

  const getInitialTab = () => {
    const selected = buttons.find((b) => b.getAttribute('aria-selected') === 'true');
    return (selected?.getAttribute('data-home-order-tab') || buttons[0].getAttribute('data-home-order-tab') || 'pending');
  };

  tabsRoot.addEventListener('click', (event) => {
    const target = event.target instanceof Element ? event.target.closest('button[data-home-order-tab]') : null;
    if (!target) return;
    const tabKey = target.getAttribute('data-home-order-tab') || '';
    if (!tabKey) return;
    applyTab(tabKey);
  });

  window.addEventListener('resize', () => {
    const selected = buttons.find((b) => b.getAttribute('aria-selected') === 'true');
    if (!selected) return;
    moveIndicatorToButton(selected);
  });

  window.addEventListener('btpro:home-order-preview-updated', () => {
    if (!currentTabKey) return;
    applyTab(currentTabKey);
  });

  applyTab(getInitialTab());
})();
