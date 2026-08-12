/*
  UI: Transform <input type="time"> into a custom dropdown menu
  styled like the revenue range dropdown (rounded-xl, p-2 menu items).

  This is used for:
  - store-delivery-settings.html (外送時間設定)
  - store-business-hours.html (營業時間設定)
*/

(() => {
  const PROCESSED_ATTR = 'data-ui-time-dropdown-ready';

  let idCounter = 0;

  const ACTIVE_BUTTON_CLASSES = ['border-primary', 'ring-1', 'ring-primary'];
  const INACTIVE_BUTTON_CLASSES = ['border-slate-200', 'dark:border-slate-700'];

  const MENU_CLASSES =
    'hidden bg-white dark:bg-slate-800 ' +
    'border border-slate-200 dark:border-slate-700 rounded-xl shadow-sm p-2 z-50 ' +
    'max-h-64 overflow-y-auto';

  const OPTION_CLASSES =
    'w-full text-left px-3 py-2 rounded-lg text-sm font-bold ' +
    'text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700/50';

  const BUTTON_BASE_CLASSES =
    'w-full px-4 py-2 text-sm font-bold text-slate-700 dark:text-slate-200 border rounded-lg ' +
    'dark:bg-slate-900 transition-colors inline-flex items-center justify-between gap-2';

  const BUTTON_ENABLED_HOVER_CLASSES = ['hover:bg-slate-50', 'dark:hover:bg-slate-800'];

  const BUTTON_DISABLED_CLASSES = 'bg-slate-200 text-slate-500 cursor-not-allowed';

  function getEnabledBgClassesFromInput(inputEl) {
    // Prefer matching the original input background.
    if (inputEl.classList.contains('bg-white')) return ['bg-white'];
    if (inputEl.classList.contains('bg-slate-50')) return ['bg-slate-50'];
    return ['bg-slate-50'];
  }

  function formatHHMM(hour, minute) {
    const hh = String(hour).padStart(2, '0');
    const mm = String(minute).padStart(2, '0');
    return `${hh}:${mm}`;
  }

  function buildTimeOptions(stepMinutes) {
    const step = Number(stepMinutes) || 30;
    const options = [];
    for (let h = 0; h < 24; h += 1) {
      for (let m = 0; m < 60; m += step) {
        options.push(formatHHMM(h, m));
      }
    }
    return options;
  }

  function applyButtonActiveState(buttonEl, active) {
    if (!buttonEl) return;

    for (const cls of ACTIVE_BUTTON_CLASSES) {
      buttonEl.classList.toggle(cls, Boolean(active));
    }

    for (const cls of INACTIVE_BUTTON_CLASSES) {
      buttonEl.classList.toggle(cls, !active);
    }
  }

  function positionMenuForButton(menuEl, buttonEl) {
    const rect = buttonEl.getBoundingClientRect();
    menuEl.style.position = 'fixed';
    menuEl.style.left = `${Math.round(rect.left)}px`;
    menuEl.style.width = `${Math.round(rect.width)}px`;

    // Default: open below. If it would overflow the viewport, open above.
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

  function setMenuOpen(root, open) {
    const btn = root.querySelector('[data-ui-time-button]');
    if (!btn) return;

    const id = root.getAttribute('data-ui-time-id');
    if (!id) return;

    const selector = `[data-ui-time-menu][data-ui-time-id="${CSS.escape(id)}"]`;
    const menu = document.querySelector(selector);
    if (!menu) return;

    if (open) {
      // Portal to <body> to avoid clipping by overflow containers.
      if (menu.parentElement !== document.body) {
        menu.setAttribute('data-ui-time-portal', 'true');
        document.body.appendChild(menu);
      }

      // Measure and position without flashing.
      menu.classList.remove('hidden');
      const prevVisibility = menu.style.visibility;
      menu.style.visibility = 'hidden';
      positionMenuForButton(menu, btn);
      menu.style.visibility = prevVisibility;
    } else {
      if (menu.getAttribute('data-ui-time-portal') === 'true') {
        menu.removeAttribute('data-ui-time-portal');
        root.appendChild(menu);
      }
    }

    // Open branch already removed `hidden` above.
    if (!open) menu.classList.add('hidden');
    btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    applyButtonActiveState(btn, open);
    root.setAttribute('data-ui-time-open', open ? 'true' : 'false');
  }

  function isMenuOpen(root) {
    return root.getAttribute('data-ui-time-open') === 'true';
  }

  function syncDisabled(inputEl, buttonEl) {
    const disabled = Boolean(inputEl.disabled);
    buttonEl.disabled = disabled;

    const enabledBg = (buttonEl.getAttribute('data-ui-time-enabled-bg') || '')
      .split(' ')
      .map((s) => s.trim())
      .filter(Boolean);

    // Clear states
    buttonEl.classList.remove('bg-slate-100', 'text-slate-400', 'cursor-not-allowed');
    for (const cls of enabledBg) buttonEl.classList.remove(cls);

    if (disabled) {
      for (const cls of BUTTON_ENABLED_HOVER_CLASSES) buttonEl.classList.remove(cls);
      buttonEl.classList.add(...BUTTON_DISABLED_CLASSES.split(' '));
    } else {
      for (const cls of BUTTON_ENABLED_HOVER_CLASSES) buttonEl.classList.add(cls);
      for (const cls of enabledBg) buttonEl.classList.add(cls);
    }
  }

  function enhanceTimeInput(inputEl) {
    if (!inputEl || !(inputEl instanceof HTMLInputElement)) return;
    if (inputEl.getAttribute(PROCESSED_ATTR) === 'true') return;
    if ((inputEl.getAttribute('type') || '').toLowerCase() !== 'time') return;

    const parent = inputEl.parentElement;
    if (!parent) return;

    // Wrap the input to host the dropdown.
    const wrapper = document.createElement('div');
    const wrapperClasses = ['relative', 'w-full'];
    if (inputEl.classList.contains('flex-1')) wrapperClasses.push('flex-1');
    wrapper.className = wrapperClasses.join(' ');
    wrapper.setAttribute('data-ui-time-dropdown', '');

    parent.insertBefore(wrapper, inputEl);
    wrapper.appendChild(inputEl);

    // Keep the original input for form semantics and existing scripts.
    inputEl.classList.add('sr-only');

    const button = document.createElement('button');
    button.type = 'button';
    button.className = BUTTON_BASE_CLASSES;
    for (const cls of BUTTON_ENABLED_HOVER_CLASSES) button.classList.add(cls);
    button.setAttribute('data-ui-time-button', '');
    button.setAttribute('aria-haspopup', 'menu');
    button.setAttribute('aria-expanded', 'false');

    const label = document.createElement('span');
    label.setAttribute('data-ui-time-label', '');
    label.textContent = inputEl.value || '—';

    // If the original input had a left icon (commonly pl-11), keep spacing so text doesn't overlap.
    if (inputEl.classList.contains('pl-11')) {
      button.classList.remove('px-4');
      button.classList.add('pl-11', 'pr-4');
    }

    const chevron = document.createElement('span');
    chevron.className = 'material-symbols-outlined text-base text-slate-400';
    chevron.textContent = 'expand_more';

    button.appendChild(label);
    button.appendChild(chevron);

    const menu = document.createElement('div');
    menu.className = MENU_CLASSES;
    menu.setAttribute('data-ui-time-menu', '');
    menu.setAttribute('role', 'menu');

    const dropdownId = `ui-time-${(idCounter += 1)}`;
    wrapper.setAttribute('data-ui-time-id', dropdownId);
    menu.setAttribute('data-ui-time-id', dropdownId);

    const step = Number(inputEl.getAttribute('step')) || 1800; // seconds
    const stepMinutes = Math.max(5, Math.round(step / 60));
    const options = buildTimeOptions(stepMinutes);

    options.forEach((timeValue) => {
      const optBtn = document.createElement('button');
      optBtn.type = 'button';
      optBtn.className = OPTION_CLASSES;
      optBtn.textContent = timeValue;

      optBtn.addEventListener('click', () => {
        inputEl.value = timeValue;
        label.textContent = timeValue;
        inputEl.dispatchEvent(new Event('change', { bubbles: true }));
        setMenuOpen(wrapper, false);
      });

      menu.appendChild(optBtn);
    });

    wrapper.appendChild(button);
    wrapper.appendChild(menu);

    const enabledBg = getEnabledBgClassesFromInput(inputEl);
    button.setAttribute('data-ui-time-enabled-bg', enabledBg.join(' '));
    for (const cls of enabledBg) button.classList.add(cls);

    // Open/close behavior
    button.addEventListener('click', (e) => {
      e.preventDefault();
      if (button.disabled) return;
      setMenuOpen(wrapper, !isMenuOpen(wrapper));
    });

    const repositionIfOpen = () => {
      if (!isMenuOpen(wrapper)) return;
      positionMenuForButton(menu, button);
    };

    window.addEventListener('scroll', repositionIfOpen, true);
    window.addEventListener('resize', repositionIfOpen);

    document.addEventListener('click', (e) => {
      if (!isMenuOpen(wrapper)) return;
      const target = e.target;
      if (!(target instanceof Node)) return;
      if (wrapper.contains(target)) return;
      if (menu.contains(target)) return;
      setMenuOpen(wrapper, false);
    });

    document.addEventListener('keydown', (e) => {
      if (!isMenuOpen(wrapper)) return;
      if (e.key === 'Escape') setMenuOpen(wrapper, false);
    });

    // Keep button label in sync if someone changes input.value programmatically.
    inputEl.addEventListener('change', () => {
      label.textContent = inputEl.value || '—';
    });

    // Sync disabled state (store pages toggle disabled via JS)
    syncDisabled(inputEl, button);
    applyButtonActiveState(button, false);
    const observer = new MutationObserver(() => {
      syncDisabled(inputEl, button);
      if (inputEl.disabled) setMenuOpen(wrapper, false);
    });
    observer.observe(inputEl, { attributes: true, attributeFilter: ['disabled', 'class'] });

    inputEl.setAttribute(PROCESSED_ATTR, 'true');
  }

  function init() {
    const inputs = Array.from(document.querySelectorAll('input[type="time"]'));
    inputs.forEach(enhanceTimeInput);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
