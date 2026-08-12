(async () => {
  BrandAPI.requireAuth();
  BrandAPI.renderAdminHeader();

  let categories = [], products = [], brandSpecs = {}, brandToppings = [];
  let pendingDeleteId = null, pendingEditId = null, pendingEditCatId = null;
  let createLogoUrl = null, editLogoUrl = null;
  let createPendingUploadPromise = null, editPendingUploadPromise = null;
  let lastCreatedDrinkDraft = null;
  let hasPendingMenuRefresh = false;
  const MENU_SETUP_REQUIRED_MESSAGE = "目前尚未啟用任何規格。請先至「規格與配料管理」啟用或編輯預設規格，之後飲品的規格套用選項才會顯示你們已開啟的規格。";
  let pendingUploadPromise = null; // 追蹤進行中的圖片上傳
  const detailCache = new Map(); // productId → detail，避免重複 API 呼叫

  // ─── 價格格式化（依 specPrices 或 basePrice）─────────────
  const DEFAULT_MAX_TOPPINGS = 3;
  const CREATE_MAX_TOPPINGS_CACHE_KEY = 'hq.menu.createDrink.maxToppings';
  const modalInlineNoticeTimers = new WeakMap();
  const failedDrinkDraftQueue = [];
  let activeDrinkEditor = null;
  let isContinueCreatePromptOpen = false;
  let isCategoryTabReordering = false;
  let tempProductCounter = 0;
  const SAVE_STATE = {
    IDLE: 'idle',
    SAVING: 'saving',
    FAILED: 'failed',
  };

  function getModalUploadPromise(modal) {
    if (!modal) return null;
    if (modal.matches?.('[data-hq-drink-create-modal]')) return createPendingUploadPromise;
    if (modal.matches?.('[data-hq-drink-edit-modal]')) return editPendingUploadPromise;
    return null;
  }

  function setModalUploadPromise(modal, promise) {
    if (!modal) return;
    if (modal.matches?.('[data-hq-drink-create-modal]')) {
      createPendingUploadPromise = promise;
      return;
    }
    if (modal.matches?.('[data-hq-drink-edit-modal]')) {
      editPendingUploadPromise = promise;
    }
  }

  function getModalScrollContainer(modal) {
    return modal?.querySelector('.relative.h-full.w-full.overflow-y-auto') || null;
  }

  function syncModalScrollTopButton(modal) {
    if (!modal) return;

    const button = modal.querySelector('[data-hq-modal-scroll-top]');
    const scrollContainer = getModalScrollContainer(modal);
    if (!(button instanceof HTMLElement) || !(scrollContainer instanceof HTMLElement)) return;

    const isOpen = !modal.classList.contains('hidden') && modal.getAttribute('aria-hidden') !== 'true';
    const canScroll = scrollContainer.scrollHeight - scrollContainer.clientHeight > 120;
    const isVisible = isOpen && canScroll && scrollContainer.scrollTop > 180;

    button.classList.toggle('opacity-0', !isVisible);
    button.classList.toggle('pointer-events-none', !isVisible);
    button.classList.toggle('translate-y-3', !isVisible);
  }

  function ensureModalScrollTopButton(modal) {
    if (!modal || modal.dataset.scrollTopBound === 'true') return;

    const scrollContainer = getModalScrollContainer(modal);
    if (!(scrollContainer instanceof HTMLElement)) return;

    const button = document.createElement('button');
    button.type = 'button';
    button.setAttribute('aria-label', '回到表單頂部');
    button.setAttribute('data-hq-modal-scroll-top', 'true');
    button.className = 'absolute bottom-6 right-6 z-[55] inline-flex h-11 w-11 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-700 shadow-lg shadow-slate-900/10 transition-all duration-200 hover:-translate-y-0.5 hover:bg-slate-50 hover:text-primary focus:outline-none focus:ring-2 focus:ring-primary/40 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800 opacity-0 pointer-events-none translate-y-3';
    button.innerHTML = '<span class="material-symbols-outlined text-[22px]">keyboard_arrow_up</span>';
    button.addEventListener('click', () => {
      scrollContainer.scrollTo({ top: 0, behavior: 'smooth' });
    });

    scrollContainer.addEventListener('scroll', () => syncModalScrollTopButton(modal), { passive: true });
    modal.appendChild(button);
    modal.dataset.scrollTopBound = 'true';
    syncModalScrollTopButton(modal);
  }

  function formatPrice(p) {
    const fmt = v => { const n = Number(v); return Number.isFinite(n) ? (n % 1 === 0 ? n : n.toFixed(2)) : v; };
    const prices = p.specPrices;
    if (prices && prices.length) {
      return prices.map(sp => `${sp.name}: $${fmt(sp.price)}`).join(' / ');
    }
    return `$ ${fmt(p.basePrice || 0)}`;
  }

  function flushPendingMenuRefresh(force = false) {
    if (!force && !hasPendingMenuRefresh) return;
    renderMenu();
    hasPendingMenuRefresh = false;
  }

  function persistMenuSnapshots() {
    BrandAPI.saveCategoriesSnapshot(categories);
    BrandAPI.saveProductsSnapshot(
      products
        .filter(product => product?.productId != null)
        .map(product => {
          const plain = { ...product };
          delete plain.__tempId;
          delete plain.__saveState;
          delete plain.__saveError;
          delete plain.__queueKey;
          return plain;
        })
    );
    BrandAPI.saveSpecsSnapshot(brandSpecs);
    BrandAPI.saveToppingsSnapshot(brandToppings);
  }

  function hydrateMenuSnapshots() {
    const cachedCategories = BrandAPI.getCategoriesSnapshot();
    const cachedProducts = BrandAPI.getProductsSnapshot();
    const cachedSpecs = BrandAPI.getSpecsSnapshot();
    const cachedToppings = BrandAPI.getToppingsSnapshot();

    if (cachedCategories) categories = cachedCategories;
    if (cachedProducts) products = cachedProducts;
    if (cachedSpecs) brandSpecs = cachedSpecs;
    if (cachedToppings) brandToppings = cachedToppings;

    return Boolean(cachedCategories || cachedProducts || cachedSpecs || cachedToppings);
  }

  function getEnabledSizeSpecs() {
    return (brandSpecs.SIZE || []).filter(spec => spec.isEnabled);
  }

  function getProductKey(product) {
    if (!product) return '';
    if (product.productId != null) return `id:${product.productId}`;
    return product.__tempId ? `tmp:${product.__tempId}` : '';
  }

  function findProductIndexByKey(productKey) {
    return products.findIndex(product => getProductKey(product) === productKey);
  }

  function findProductByKey(productKey) {
    return products.find(product => getProductKey(product) === productKey) || null;
  }

  function replaceProductByKey(productKey, nextProduct) {
    const index = findProductIndexByKey(productKey);
    if (index < 0) return false;
    products[index] = nextProduct;
    return true;
  }

  function removeProductByKey(productKey) {
    const index = findProductIndexByKey(productKey);
    if (index < 0) return false;
    products.splice(index, 1);
    return true;
  }

  function setActiveDrinkEditor(editor) {
    activeDrinkEditor = editor;
    renderFailedDrinkQueuePrompt();
  }

  function clearActiveDrinkEditor() {
    activeDrinkEditor = null;
    renderFailedDrinkQueuePrompt();
  }

  function isDrinkEditorBusy() {
    return activeDrinkEditor !== null || isContinueCreatePromptOpen;
  }

  function getFailedQueueIndex(queueKey) {
    return failedDrinkDraftQueue.findIndex(item => item.queueKey === queueKey);
  }

  function getFailedQueueItem(queueKey) {
    return failedDrinkDraftQueue.find(item => item.queueKey === queueKey) || null;
  }

  function getFailedQueueItemByProductKey(productKey) {
    return failedDrinkDraftQueue.find(item => item.productKey === productKey) || null;
  }

  function enqueueFailedDrinkDraft(item) {
    if (!item?.queueKey) return;
    const normalizedItem = {
      ...item,
      queuedAt: item.queuedAt || Date.now(),
    };
    const index = getFailedQueueIndex(item.queueKey);
    if (index >= 0) {
      failedDrinkDraftQueue[index] = {
        ...failedDrinkDraftQueue[index],
        ...normalizedItem,
      };
    } else {
      failedDrinkDraftQueue.push(normalizedItem);
    }
    renderFailedDrinkQueuePrompt();
  }

  function removeFailedDrinkDraft(queueKey) {
    const index = getFailedQueueIndex(queueKey);
    if (index < 0) return;
    failedDrinkDraftQueue.splice(index, 1);
    renderFailedDrinkQueuePrompt();
  }

  function ensureFailedDrinkQueuePrompt() {
    let prompt = document.querySelector('[data-hq-failed-drink-prompt]');
    if (prompt) return prompt;

    prompt = document.createElement('button');
    prompt.type = 'button';
    prompt.setAttribute('data-hq-failed-drink-prompt', 'true');
    prompt.className = 'fixed bottom-6 right-6 z-[90] hidden max-w-sm items-start gap-3 rounded-2xl border border-rose-200 bg-white px-4 py-3 text-left shadow-xl shadow-slate-900/10 transition hover:-translate-y-0.5 hover:border-rose-300 hover:shadow-2xl dark:border-rose-900/60 dark:bg-slate-900';
    prompt.innerHTML = `
      <span class="material-symbols-outlined shrink-0 text-[22px] text-rose-500">error</span>
      <span class="min-w-0 flex-1">
        <span class="block text-sm font-bold text-slate-900 dark:text-slate-100" data-hq-failed-drink-count></span>
        <span class="mt-1 block text-xs leading-5 text-slate-500 dark:text-slate-400" data-hq-failed-drink-summary></span>
      </span>
    `;

    prompt.addEventListener('click', () => {
      const nextFailedItem = failedDrinkDraftQueue[0];
      if (!nextFailedItem) return;
      if (isDrinkEditorBusy()) {
        BrandAPI.toast('請先完成目前的新增或編輯，再處理失敗項目', 'error');
        return;
      }
      openFailedDrinkDraft(nextFailedItem.queueKey);
    });

    document.body.appendChild(prompt);
    return prompt;
  }

  function renderFailedDrinkQueuePrompt() {
    const prompt = ensureFailedDrinkQueuePrompt();
    const countEl = prompt.querySelector('[data-hq-failed-drink-count]');
    const summaryEl = prompt.querySelector('[data-hq-failed-drink-summary]');
    const count = failedDrinkDraftQueue.length;
    const nextItem = failedDrinkDraftQueue[0];

    if (!count) {
      prompt.classList.add('hidden');
      prompt.classList.remove('flex');
      return;
    }

    prompt.classList.remove('hidden');
    prompt.classList.add('flex');
    prompt.classList.toggle('opacity-80', isDrinkEditorBusy());
    countEl.textContent = `有 ${count} 筆飲品儲存失敗`;
    summaryEl.textContent = nextItem?.draft?.name
      ? `點此繼續處理：${nextItem.draft.name}`
      : '點此打開第一筆失敗項目並重新儲存';
  }

  function getCachedCreateMaxToppings() {
    try {
      const raw = window.localStorage?.getItem(CREATE_MAX_TOPPINGS_CACHE_KEY);
      const parsed = Number(raw);
      return Number.isFinite(parsed) && parsed > 0 ? Math.trunc(parsed) : DEFAULT_MAX_TOPPINGS;
    } catch (_) {
      return DEFAULT_MAX_TOPPINGS;
    }
  }

  function cacheCreateMaxToppings(value) {
    const parsed = Number(value);
    if (!Number.isFinite(parsed) || parsed < 1) return;

    try {
      window.localStorage?.setItem(CREATE_MAX_TOPPINGS_CACHE_KEY, String(Math.trunc(parsed)));
    } catch (_) {
      // Ignore storage failures and keep the in-form default.
    }
  }

  function ensureRequiredMarker(target) {
    if (!target || target.querySelector?.('[data-hq-required-marker]')) return;

    const marker = document.createElement('span');
    marker.className = 'ml-1 text-rose-500';
    marker.textContent = '*';
    marker.setAttribute('data-hq-required-marker', 'true');
    marker.setAttribute('aria-hidden', 'true');
    target.appendChild(marker);
  }

  function ensureDrinkRequiredMarkers(modal) {
    if (!modal) return;

    const nameLabel = modal.querySelector('[data-hq-drink-name]')?.closest('.space-y-2')?.querySelector('label');
    const categoryLabel = modal.querySelector('[data-hq-drink-category]')?.closest('.space-y-2')?.querySelector('label');
    const pricingTitle = modal.querySelector('[data-hq-size-pricing-rows]')?.parentElement?.querySelector('p');

    ensureRequiredMarker(nameLabel);
    ensureRequiredMarker(categoryLabel);
    ensureRequiredMarker(pricingTitle);
  }

  function ensureCategoryRequiredMarkers(modal, inputSelector) {
    if (!modal) return;

    const nameLabel = modal.querySelector(inputSelector)?.closest('.space-y-3')?.querySelector('label');
    ensureRequiredMarker(nameLabel);
  }

  function ensureModalInlineNotice(modal) {
    if (!modal) return null;

    let notice = modal.querySelector('[data-hq-modal-inline-notice]');
    if (notice) return notice;

    const saveButton = modal.querySelector(
      '[data-hq-drink-create-save],[data-hq-drink-edit-save],[data-hq-category-edit-submit]'
    );
    const footer = saveButton?.parentElement;
    if (footer) {
      footer.classList.remove('justify-between');
      footer.classList.add('justify-end', 'items-end');

      let actions = footer.querySelector('[data-hq-modal-footer-actions]');
      if (!actions) {
        actions = document.createElement('div');
        actions.className = 'ml-auto flex shrink-0 items-center justify-end gap-3';
        actions.setAttribute('data-hq-modal-footer-actions', 'true');
        Array.from(footer.children).forEach((child) => actions.appendChild(child));
        footer.appendChild(actions);
      }

      notice = document.createElement('p');
      notice.className = 'invisible min-w-0 flex-1 text-sm font-semibold leading-6 text-rose-500';
      notice.setAttribute('data-hq-modal-inline-notice', 'true');
      footer.prepend(notice);
      return notice;
    }

    const title = modal.querySelector('h3');
    if (!title) return null;

    let titleRow = modal.querySelector('[data-hq-modal-title-row]');
    if (!titleRow) {
      titleRow = document.createElement('div');
      titleRow.className = 'flex flex-wrap items-center gap-x-3 gap-y-1';
      titleRow.setAttribute('data-hq-modal-title-row', 'true');
      title.before(titleRow);
      titleRow.appendChild(title);
    }

    notice = document.createElement('span');
    notice.className = 'hidden text-sm font-semibold text-rose-500';
    notice.setAttribute('data-hq-modal-inline-notice', 'true');
    titleRow.appendChild(notice);
    return notice;
  }

  function clearModalInlineNotice(modal) {
    if (!modal) return;

    const timer = modalInlineNoticeTimers.get(modal);
    if (timer) {
      window.clearTimeout(timer);
      modalInlineNoticeTimers.delete(modal);
    }

    const notice = ensureModalInlineNotice(modal);
    if (!notice) return;
    notice.textContent = '';
    notice.classList.add('hidden');
    notice.classList.add('invisible');
  }

  function showModalInlineNotice(modal, message, duration = 3000) {
    const notice = ensureModalInlineNotice(modal);
    if (!notice) return;

    clearModalInlineNotice(modal);
    notice.textContent = message;
    notice.classList.remove('hidden');
    notice.classList.remove('invisible');

    const timer = window.setTimeout(() => {
      notice.textContent = '';
      notice.classList.add('invisible');
      modalInlineNoticeTimers.delete(modal);
    }, duration);

    modalInlineNoticeTimers.set(modal, timer);
  }

  function openManagedModal(modal) {
    if (!modal) return;
    if (modal.querySelector('[data-hq-drink-toppings-enabled]')) {
      syncToppingControls(modal);
      syncSectionActionLabels(modal);
    }
    modal.classList.remove('hidden');
    modal.setAttribute('aria-hidden', 'false');
    const scrollContainer = getModalScrollContainer(modal);
    if (scrollContainer instanceof HTMLElement) {
      scrollContainer.scrollTop = 0;
    }
    ensureModalScrollTopButton(modal);
    syncModalScrollTopButton(modal);
    const focusEl = modal.querySelector(
      'input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [tabindex]:not([tabindex="-1"])'
    );
    if (focusEl instanceof HTMLElement) {
      focusEl.focus();
    }
  }

  function showContinueCreateDrinkDialog() {
    document.querySelector('[data-hq-continue-create-drink-dialog]')?.remove();

    return new Promise((resolve) => {
      const root = document.createElement('div');
      root.className = 'fixed inset-0 z-[10010] flex items-center justify-center p-4';
      root.setAttribute('data-hq-continue-create-drink-dialog', 'true');
      root.innerHTML = `
        <div class="absolute inset-0 bg-slate-900/45 backdrop-blur-sm" data-dialog-backdrop></div>
        <div class="relative w-full max-w-md overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl">
          <div class="border-b border-slate-100 px-6 py-5">
            <h3 class="text-xl font-bold text-slate-900">飲品新增成功</h3>
          </div>
          <div class="space-y-3 px-6 py-6">
            <p class="text-base leading-8 text-slate-700">還要繼續輸入飲品嗎？</p>
            <p class="text-sm leading-7 text-slate-500">點擊畫面空白處即可返回菜單列表；若要繼續新增，請點擊下方「要」。</p>
          </div>
          <div class="flex items-center justify-end gap-3 border-t border-slate-100 px-6 py-5">
            <button
              type="button"
              data-dialog-confirm
              class="rounded-xl bg-primary px-6 py-2.5 text-base font-bold text-white transition-colors hover:bg-primary/90"
            >
              要
            </button>
          </div>
        </div>
      `;

      const close = (shouldContinue) => {
        root.remove();
        resolve(Boolean(shouldContinue));
      };

      root.querySelector('[data-dialog-confirm]')?.addEventListener('click', () => close(true), { once: true });
      root.querySelector('[data-dialog-backdrop]')?.addEventListener('click', () => close(false), { once: true });
      document.body.appendChild(root);
    });
  }

  function showContinueCreateDrinkPrompt() {
    document.querySelector('[data-hq-continue-create-drink-dialog]')?.remove();

    return new Promise((resolve) => {
      const root = document.createElement('div');
      root.className = 'fixed inset-0 z-[10010] flex items-center justify-center p-4';
      root.setAttribute('data-hq-continue-create-drink-dialog', 'true');
      root.innerHTML = `
        <div class="absolute inset-0 bg-slate-900/45 backdrop-blur-sm" data-dialog-backdrop></div>
        <div class="relative w-full max-w-md overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl">
          <div class="border-b border-slate-100 px-6 py-5">
            <h3 class="text-xl font-bold text-slate-900">要繼續新增飲品嗎？</h3>
          </div>
          <div class="px-6 py-6">
            <p class="text-center text-sm leading-7 text-slate-500">點擊視窗外即可返回菜單列表</p>
          </div>
          <div class="flex items-center justify-center border-t border-slate-100 px-6 py-5">
            <button
              type="button"
              data-dialog-confirm
              class="rounded-xl bg-primary px-8 py-2.5 text-base font-bold text-white transition-colors hover:bg-primary/90"
            >
              要！
            </button>
          </div>
        </div>
      `;

      const close = (shouldContinue) => {
        root.remove();
        resolve(Boolean(shouldContinue));
      };

      root.querySelector('[data-dialog-confirm]')?.addEventListener('click', () => close(true), { once: true });
      root.querySelector('[data-dialog-backdrop]')?.addEventListener('click', () => close(false), { once: true });
      document.body.appendChild(root);
    });
  }

  function ensureDrinkCustomizationSections(modal) {
    if (!modal || modal.querySelector('[data-hq-drink-topping-settings]')) return;
    const imageSection = modal.querySelector('[data-hq-drink-image-preview]')?.closest('.space-y-2');
    if (!imageSection) return;

    const fragment = document.createElement('div');
    fragment.innerHTML = `
      <div class="space-y-3" data-hq-drink-topping-settings>
        <div class="flex items-center justify-between gap-4">
          <div>
            <p class="text-sm font-semibold">配料設定</p>
            <p class="text-xs text-slate-500" data-hq-drink-topping-hint>開啟後可勾選此飲品可加的配料，並限制最多加料數。</p>
          </div>
          <label class="inline-flex items-center gap-3 cursor-pointer">
            <span class="text-sm font-medium text-slate-600">可加配料</span>
            <input type="checkbox" class="sr-only peer" data-hq-drink-toppings-enabled />
            <div class="relative h-6 w-11 rounded-full bg-slate-200 transition peer-checked:bg-primary peer-checked:after:translate-x-5 after:absolute after:left-[2px] after:top-[2px] after:h-5 after:w-5 after:rounded-full after:bg-white after:transition"></div>
          </label>
        </div>
        <div class="grid gap-3 sm:grid-cols-[160px]" data-hq-drink-topping-config>
          <label class="space-y-2">
            <span class="text-sm font-semibold">最多加料數</span>
            <input
              type="number"
              min="1"
              step="1"
              value="3"
              data-hq-drink-max-toppings
              class="w-full rounded-xl border border-slate-200 bg-white focus:ring-primary focus:border-primary"
            />
          </label>
        </div>
      </div>
      <div class="space-y-3" data-hq-drink-topping-section>
        <p class="text-sm font-semibold">配料</p>
        <div class="flex flex-wrap gap-3"></div>
      </div>
    `;

    Array.from(fragment.children).forEach(node => imageSection.before(node));
  }

  function syncToppingControls(modal) {
    if (!modal) return;
    const enabledToggle = modal.querySelector('[data-hq-drink-toppings-enabled]');
    const maxInput = modal.querySelector('[data-hq-drink-max-toppings]');
    const config = modal.querySelector('[data-hq-drink-topping-config]');
    const section = modal.querySelector('[data-hq-drink-topping-section]');
    const hint = modal.querySelector('[data-hq-drink-topping-hint]');
    const wrap = section?.querySelector('.flex.flex-wrap');
    const hasToppings = wrap?.querySelector('[data-topping-id]') != null;
    if (!enabledToggle || !maxInput || !config || !section) return;

    if (!hasToppings) {
      enabledToggle.checked = false;
      enabledToggle.disabled = true;
      if (hint) hint.textContent = '目前沒有任何已啟用配料，請先到「規格與配料管理」開啟配料。';
    } else {
      enabledToggle.disabled = false;
      if (hint) hint.textContent = enabledToggle.checked
        ? '請勾選此飲品可加的配料，並設定最多加料數。'
        : '開啟後即可勾選此飲品可加的配料。';
    }

    const enabled = hasToppings && enabledToggle.checked;
    maxInput.disabled = !enabled;
    config.classList.toggle('hidden', !enabled);
    section.classList.toggle('hidden', !enabled);
    wrap?.querySelectorAll('[data-topping-id]').forEach(input => {
      input.disabled = !enabled;
      if (!enabled) input.checked = false;
    });

    syncSectionActionLabels(modal);
  }

  function setToppingConfig(modal, toppingIds, maxToppings) {
    if (!modal) return;
    const enabledToggle = modal.querySelector('[data-hq-drink-toppings-enabled]');
    const maxInput = modal.querySelector('[data-hq-drink-max-toppings]');
    const hasSelectedToppings = Array.isArray(toppingIds) && toppingIds.length > 0;
    const parsedMax = Number(maxToppings);
    const selectedToppingIds = new Set((Array.isArray(toppingIds) ? toppingIds : []).map(Number));

    if (enabledToggle) {
      enabledToggle.checked = hasSelectedToppings || Number.isFinite(parsedMax) && parsedMax > 0;
    }
    if (maxInput) {
      maxInput.value = Number.isFinite(parsedMax) && parsedMax > 0 ? String(parsedMax) : '3';
    }

    modal.querySelectorAll('input[data-topping-id]').forEach(input => {
      input.checked = selectedToppingIds.has(Number(input.dataset.toppingId));
    });

    syncToppingControls(modal);
  }

  function resetCreateToppingConfig(modal) {
    if (!modal) return;
    const enabledToggle = modal.querySelector('[data-hq-drink-toppings-enabled]');
    const maxInput = modal.querySelector('[data-hq-drink-max-toppings]');
    if (enabledToggle) enabledToggle.checked = (brandToppings || []).some(t => t.isEnabled);
    if (maxInput) maxInput.value = String(getCachedCreateMaxToppings());
    syncToppingControls(modal);
  }

  function wireToppingControlEvents(modal) {
    if (!modal || modal.dataset.toppingControlBound === 'true') return;
    modal.dataset.toppingControlBound = 'true';
    modal.addEventListener('change', event => {
      if (event.target?.matches?.('[data-hq-drink-toppings-enabled]')) {
        syncToppingControls(modal);
        syncSectionActionLabels(modal);
      }

      if (event.target?.matches?.('input[data-spec-id], input[data-topping-id]')) {
        syncSectionActionLabels(modal);
      }
    });
    modal.addEventListener('click', event => {
      const trigger = event.target?.closest?.('[data-hq-select-all]');
      if (!trigger) return;
      selectAllSectionOptions(modal, trigger.getAttribute('data-hq-select-all'));
    });
  }

  function ensureSectionAction(section, sectionKey) {
    if (!section) return;
    const title = section.querySelector('p');
    if (!title) return;

    if (sectionKey === 'ICE') {
      title.textContent = '冰量';
    }

    if (sectionKey === 'ICE' || sectionKey === 'SWEETNESS') {
      ensureRequiredMarker(title);
    }

    let header = section.querySelector('[data-hq-section-header]');
    if (!header) {
      header = document.createElement('div');
      header.className = 'flex items-center justify-between gap-3';
      header.setAttribute('data-hq-section-header', sectionKey);
      title.before(header);
      header.appendChild(title);
    }

    let action = header.querySelector('[data-hq-select-all]');
    if (!action) {
      action = document.createElement('button');
      action.type = 'button';
      action.className = 'text-xs font-semibold text-primary hover:text-primary/80 transition-colors';
      action.setAttribute('data-hq-select-all', sectionKey);
      action.textContent = '一鍵全選';
      header.appendChild(action);
    }
  }

  function getSectionSelectableInputs(modal, sectionKey) {
    if (!modal) return [];

    if (sectionKey === 'TOPPING') {
      return Array.from(modal.querySelectorAll('input[data-topping-id]'));
    }

    const type = sectionKey === 'ICE' ? 'ICE' : 'SWEETNESS';
    const enabledIds = new Set(
      (brandSpecs[type] || [])
        .filter(spec => spec.isEnabled)
        .map(spec => String(spec.brandSpecId))
    );

    return Array.from(modal.querySelectorAll('input[data-spec-id]'))
      .filter(input => enabledIds.has(String(input.dataset.specId)));
  }

  function syncSectionActionLabel(modal, sectionKey) {
    if (!modal) return;

    const action = modal.querySelector(`[data-hq-select-all="${sectionKey}"]`);
    if (!action) return;

    const inputs = getSectionSelectableInputs(modal, sectionKey);
    const enabledInputs = inputs.filter(input => !input.disabled);
    const allChecked = enabledInputs.length > 0 && enabledInputs.every(input => input.checked);

    action.textContent = allChecked ? '一鍵關閉' : '一鍵全選';
    action.disabled = enabledInputs.length === 0;
    action.classList.toggle('opacity-40', enabledInputs.length === 0);
    action.classList.toggle('pointer-events-none', enabledInputs.length === 0);
  }

  function syncSectionActionLabels(modal) {
    ['ICE', 'SWEETNESS', 'TOPPING'].forEach(sectionKey => {
      syncSectionActionLabel(modal, sectionKey);
    });
  }

  function selectAllSectionOptions(modal, sectionKey) {
    if (!modal) return;

    const inputs = getSectionSelectableInputs(modal, sectionKey).filter(input => !input.disabled);
    const shouldUncheck = inputs.length > 0 && inputs.every(input => input.checked);

    inputs.forEach(input => {
      input.checked = !shouldUncheck;
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });

    if (sectionKey === 'TOPPING') {
      syncToppingControls(modal);
    }
    syncSectionActionLabels(modal);
  }

  // ─── 動態渲染容量規格定價列（規格售價 section）────────────
  function renderSizePricingRows(modal, selectedSpecPrices, basePrice) {
    const container = modal.querySelector('[data-hq-size-pricing-rows]');
    if (!container) return;
    const sizeSpecs = getEnabledSizeSpecs();
    const priceMap = {};
    (selectedSpecPrices || []).forEach(sp => { priceMap[sp.brandSpecId] = Number(sp.price); });

    if (!sizeSpecs.length) {
      container.innerHTML = '<span class="text-xs text-slate-400">尚未設定容量規格，請至「規格與配料管理」新增</span>';
      return;
    }

    container.innerHTML = sizeSpecs.map(s => {
      const checked = priceMap[s.brandSpecId] != null;
      const price = checked ? priceMap[s.brandSpecId] : '';
      return `
        <div class="flex items-center gap-2" data-hq-size-row>
          <input type="checkbox" ${checked ? 'checked' : ''} class="size-4 rounded accent-primary"
            data-hq-drink-size-check="${s.brandSpecId}" />
          <div class="w-24 shrink-0 py-2 text-sm font-medium">${s.name}</div>
          <div class="w-40 relative">
            <span class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">$</span>
            <input type="number" value="${price}"
              class="w-full pl-7 rounded-xl border border-slate-200 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-primary focus:border-primary"
              data-hq-drink-size-price="${s.brandSpecId}" />
          </div>
        </div>`;
    }).join('');

    // 觸發 change 讓 gray overlay 同步
    container.querySelectorAll('[data-hq-drink-size-check]').forEach(c => {
      c.dispatchEvent(new Event('change', { bubbles: true }));
    });
  }

  // ─── 取得 modal 中已勾選的容量規格定價 ─────────────────────
  function getSpecPrices(modal) {
    const result = [];
    modal.querySelectorAll('[data-hq-size-row]').forEach(row => {
      const c = row.querySelector('[data-hq-drink-size-check]');
      const i = row.querySelector('[data-hq-drink-size-price]');
      if (!c?.checked) return;
      const brandSpecId = Number(c.getAttribute('data-hq-drink-size-check'));
      if (!brandSpecId) return;
      const v = String(i?.value ?? '').trim();
      const price = Number(v);
      if (!Number.isFinite(price)) return;
      result.push({ brandSpecId, price });
    });
    return result;
  }

  // ─── 填充規格/配料 checkboxes（ICE + SWEETNESS + 配料）──────
  function populateSpecs(modal, selectedSpecIds, selectedToppingIds) {
    ensureDrinkCustomizationSections(modal);
    const chipClass = 'inline-flex items-center gap-2 px-3 py-2 rounded-xl border border-slate-200 dark:border-zinc-700 bg-white dark:bg-zinc-800 text-sm font-medium cursor-pointer';
    const emptyHint = '<span class="text-xs text-slate-400">尚未設定，請至「規格與配料管理」新增</span>';

    // 冰量（ICE）
    const iceSection = findSectionByTitle(modal, '冰量') || findSectionByTitle(modal, '溫度');
    if (iceSection) {
      ensureSectionAction(iceSection, 'ICE');
      const wrap = iceSection.querySelector('.flex.flex-wrap');
      if (wrap) {
        const list = (brandSpecs.ICE || []).filter(s => s.isEnabled);
        wrap.innerHTML = list.length ? list.map(s => `
          <label class="${chipClass}">
            <input type="checkbox" class="size-4 rounded accent-primary" data-spec-id="${s.brandSpecId}" ${(selectedSpecIds||[]).includes(s.brandSpecId)?'checked':''} />
            ${s.name}
          </label>`).join('') : emptyHint;
      }
    }

    // 甜度（SWEETNESS）
    const sweetSection = findSectionByTitle(modal, '甜度');
    if (sweetSection) {
      ensureSectionAction(sweetSection, 'SWEETNESS');
      const wrap = sweetSection.querySelector('.flex.flex-wrap');
      if (wrap) {
        const list = (brandSpecs.SWEETNESS || []).filter(s => s.isEnabled);
        wrap.innerHTML = list.length ? list.map(s => `
          <label class="${chipClass}">
            <input type="checkbox" class="size-4 rounded accent-primary" data-spec-id="${s.brandSpecId}" ${(selectedSpecIds||[]).includes(s.brandSpecId)?'checked':''} />
            ${s.name}
          </label>`).join('') : emptyHint;
      }
    }

    // 配料（Toppings）
    const topSection = findSectionByTitle(modal, '配料');
    if (topSection) {
      ensureSectionAction(topSection, 'TOPPING');
      const wrap = topSection.querySelector('.flex.flex-wrap');
      if (wrap) {
        const list = (brandToppings || []).filter(t => t.isEnabled);
        wrap.innerHTML = list.length ? list.map(t => `
          <label class="${chipClass}">
            <input type="checkbox" class="size-4 rounded accent-primary" data-topping-id="${t.brandToppingId}" ${(selectedToppingIds||[]).includes(t.brandToppingId)?'checked':''} />
            ${t.name} (+$${t.price})
          </label>`).join('') : emptyHint;
      }
    }
    syncToppingControls(modal);
    syncSectionActionLabels(modal);
  }

  function findSectionByTitle(modal, title) {
    return Array.from(modal.querySelectorAll('.space-y-3'))
      .find((section) => {
        const heading = section.querySelector('p');
        if (!heading) return false;
        const raw = heading.childNodes[0]?.textContent ?? heading.textContent ?? '';
        return raw.trim() === title;
      });
  }

  function getCheckedSpecIds(modal) {
    return Array.from(modal.querySelectorAll('input[data-spec-id]:checked')).map(el => Number(el.dataset.specId));
  }

  function getCheckedSpecIdsByType(modal, type) {
    const enabledSpecs = (brandSpecs[type] || []).filter(spec => spec.isEnabled);
    const selectedIds = new Set(getCheckedSpecIds(modal));
    return enabledSpecs
      .filter(spec => selectedIds.has(spec.brandSpecId))
      .map(spec => spec.brandSpecId);
  }

  function validateRequiredSpecSelections(modal) {
    const requirements = [
      { type: 'ICE', label: '冰塊' },
      { type: 'SWEETNESS', label: '甜度' },
    ];

    requirements.forEach(({ type, label }) => {
      const enabledSpecs = (brandSpecs[type] || []).filter(spec => spec.isEnabled);
      if (!enabledSpecs.length) {
        throw new Error(`請先在「規格與配料管理」啟用至少一個${label}規格`);
      }

      if (!getCheckedSpecIdsByType(modal, type).length) {
        throw new Error(`請至少勾選一個此飲品可用的${label}規格`);
      }
    });
  }
  function getCheckedToppingIds(modal) {
    return Array.from(modal.querySelectorAll('input[data-topping-id]:checked')).map(el => Number(el.dataset.toppingId));
  }

  function renderSizePricingRows(modal, selectedSpecPrices, basePrice) {
    const container = modal.querySelector('[data-hq-size-pricing-rows]');
    if (!container) return;

    ensureDrinkRequiredMarkers(modal);

    const sizeSpecs = getEnabledSizeSpecs();
    const priceMap = {};
    (selectedSpecPrices || []).forEach(sp => { priceMap[sp.brandSpecId] = Number(sp.price); });

    if (!sizeSpecs.length) {
      const normalizedBasePrice = Number(basePrice);
      container.innerHTML = `
        <label class="block space-y-2">
          <span class="text-xs font-medium text-slate-500">目前未啟用容量規格，請直接設定單一售價。</span>
          <div class="relative max-w-[220px]">
            <span class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">$</span>
            <input
              type="number"
              value="${Number.isFinite(normalizedBasePrice) ? normalizedBasePrice : ''}"
              data-hq-drink-base-price
              class="w-full pl-7 rounded-xl border border-slate-200 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-primary focus:border-primary"
            />
          </div>
        </label>`;
      return;
    }

    const singleSize = sizeSpecs.length === 1;
    container.innerHTML = sizeSpecs.map(s => {
      const hasSavedPrice = priceMap[s.brandSpecId] != null;
      const checked = singleSize || hasSavedPrice;
      const fallbackPrice = singleSize && Number.isFinite(Number(basePrice)) ? Number(basePrice) : '';
      const price = hasSavedPrice ? priceMap[s.brandSpecId] : fallbackPrice;
      return `
        <div class="flex items-center gap-2" data-hq-size-row>
          <input type="checkbox" ${checked ? 'checked' : ''} class="${singleSize ? 'sr-only' : 'size-4 rounded accent-primary'}"
            data-hq-drink-size-check="${s.brandSpecId}" />
          <div class="w-24 shrink-0 py-2 text-sm font-medium" data-hq-size-label>${s.name}</div>
          <div class="w-40 relative">
            <span class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">$</span>
            <input type="number" value="${price}"
              class="w-full pl-7 rounded-xl border border-slate-200 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-primary focus:border-primary"
              data-hq-drink-size-price="${s.brandSpecId}" />
          </div>
        </div>`;
    }).join('') + (singleSize
      ? '<p class="text-xs text-slate-500">目前只啟用一個容量，直接設定這個容量的售價即可。</p>'
      : '');

    container.querySelectorAll('[data-hq-drink-size-check]').forEach(c => {
      c.dispatchEvent(new Event('change', { bubbles: true }));
    });
  }

  function getProductPricingPayload(modal) {
    const sizeSpecs = getEnabledSizeSpecs();
    if (!sizeSpecs.length) {
      const value = Number(String(modal.querySelector('[data-hq-drink-base-price]')?.value ?? '').trim());
      if (!Number.isFinite(value) || value <= 0) {
        throw new Error('飲品售價必須大於 0');
      }
      return { basePrice: value, specPrices: [] };
    }

    const result = [];
    modal.querySelectorAll('[data-hq-size-row]').forEach(row => {
      const c = row.querySelector('[data-hq-drink-size-check]');
      const i = row.querySelector('[data-hq-drink-size-price]');
      if (!c?.checked) return;
      const brandSpecId = Number(c.getAttribute('data-hq-drink-size-check'));
      if (!brandSpecId) return;
      const label = row.querySelector('[data-hq-size-label]')?.textContent?.trim() || '容量';
      const price = Number(String(i?.value ?? '').trim());
      if (!Number.isFinite(price) || price <= 0) {
        throw new Error(`「${label}」的價格必須大於 0`);
      }
      result.push({ brandSpecId, price });
    });

    if (!result.length) {
      throw new Error('請至少設定一個容量價格');
    }

    return {
      basePrice: Math.min(...result.map(item => item.price)),
      specPrices: result,
    };
  }

  function getProductToppingPayload(modal) {
    const enabled = modal.querySelector('[data-hq-drink-toppings-enabled]')?.checked === true;
    if (!enabled) {
      return { maxToppings: 0, brandToppingIds: [] };
    }

    const brandToppingIds = getCheckedToppingIds(modal);
    if (!brandToppingIds.length) {
      throw new Error('請至少勾選一個可加配料');
    }

    const maxToppings = Number(String(modal.querySelector('[data-hq-drink-max-toppings]')?.value ?? '').trim());
    if (!Number.isFinite(maxToppings) || maxToppings < 1) {
      throw new Error('請輸入有效的最多加料數');
    }

    return { maxToppings, brandToppingIds };
  }

  function setDrinkImagePreview(prevEl, url) {
    if (!prevEl) return;
    if (url) {
      prevEl.innerHTML = `<img src="${url}" class="w-full h-full object-cover" />`;
      prevEl.className = 'w-full h-full';
    } else {
      prevEl.innerHTML = '';
      prevEl.className = 'w-full h-full bg-gradient-to-br from-slate-800 to-slate-950';
    }
  }

  function closeManagedDrinkModal(modal) {
    if (!modal) return;
    clearModalInlineNotice(modal);
    modal.classList.add('hidden');
    modal.setAttribute('aria-hidden', 'true');
    delete modal.dataset.failedQueueKey;
  }

  function buildDrinkDraft(modal, pricingPayload, toppingPayload, specIds, logoUrl) {
    if (!modal) return null;

    return {
      name: modal.querySelector('[data-hq-drink-name]')?.value?.trim() || '',
      categoryId: Number(modal.querySelector('[data-hq-drink-category]')?.value || 0),
      description: modal.querySelector('textarea')?.value?.trim() || null,
      basePrice: pricingPayload?.basePrice ?? null,
      specPrices: Array.isArray(pricingPayload?.specPrices)
        ? pricingPayload.specPrices.map(item => ({ ...item }))
        : [],
      brandSpecIds: Array.isArray(specIds) ? [...specIds] : [],
      brandToppingIds: Array.isArray(toppingPayload?.brandToppingIds)
        ? [...toppingPayload.brandToppingIds]
        : [],
      maxToppings: Number(toppingPayload?.maxToppings) || 0,
      logoUrl: logoUrl || null,
      isEnabled: true,
    };
  }

  function getCategoryNameById(categoryId) {
    const normalizedId = Number(categoryId);
    if (!normalizedId) return null;
    return categories.find(category => Number(category.id) === normalizedId)?.name || null;
  }

  function normalizeSortOrder(value, fallback = Number.MAX_SAFE_INTEGER) {
    const normalized = Number(value);
    return Number.isFinite(normalized) ? normalized : fallback;
  }

  function compareCategoriesBySortOrder(left, right) {
    return normalizeSortOrder(left?.sortOrder, 0) - normalizeSortOrder(right?.sortOrder, 0)
      || Number(left?.id || 0) - Number(right?.id || 0);
  }

  function compareProductsBySortOrder(left, right) {
    return normalizeSortOrder(left?.sortOrder, 0) - normalizeSortOrder(right?.sortOrder, 0)
      || Number(left?.productId || 0) - Number(right?.productId || 0);
  }

  function getCurrentCategoryOrderIds() {
    return [...categories]
      .sort(compareCategoriesBySortOrder)
      .map(category => Number(category?.id))
      .filter(categoryId => Number.isFinite(categoryId) && categoryId > 0);
  }

  function getCurrentCategoryOrderIds() {
    return [...categories]
      .sort(compareCategoriesBySortOrder)
      .map(category => Number(category?.id))
      .filter(categoryId => Number.isFinite(categoryId) && categoryId > 0);
  }

  function getNextLocalProductSortOrder(categoryId) {
    const normalizedCategoryId = Number(categoryId) || null;
    const currentMax = products
      .filter(product => (Number(product?.categoryId) || null) === normalizedCategoryId)
      .reduce((maxValue, product) => Math.max(maxValue, normalizeSortOrder(product?.sortOrder, -1)), -1);
    return currentMax + 1;
  }

  function buildListProductShape(source = {}) {
    return {
      productId: source.productId ?? null,
      sortOrder: normalizeSortOrder(source.sortOrder, getNextLocalProductSortOrder(source.categoryId)),
      name: source.name || '未命名飲品',
      categoryId: Number(source.categoryId) || null,
      categoryName: source.categoryName ?? getCategoryNameById(source.categoryId),
      basePrice: source.basePrice ?? null,
      maxToppings: Number(source.maxToppings) || 0,
      logoUrl: source.logoUrl || null,
      couponImageUrl: source.couponImageUrl || null,
      isEnabled: source.isEnabled !== false,
      specPrices: Array.isArray(source.specPrices) ? source.specPrices.map(item => ({ ...item })) : [],
    };
  }

  function applyDraftToCreateDrinkModal(modal, draft) {
    if (!modal || !draft) return;

    clearModalInlineNotice(modal);
    lastCreatedDrinkDraft = null;

    const nameInput = modal.querySelector('[data-hq-drink-name]');
    const categorySelect = modal.querySelector('[data-hq-drink-category]');
    const descriptionInput = modal.querySelector('textarea');
    const imageInput = modal.querySelector('[data-hq-drink-image-input]');

    if (nameInput) nameInput.value = draft.name || '';
    if (descriptionInput) descriptionInput.value = draft.description || '';
    if (imageInput) imageInput.value = '';

    if (categorySelect && categorySelect.options.length) {
      const categoryValue = String(draft.categoryId || '');
      const hasCategory = Array.from(categorySelect.options).some(option => option.value === categoryValue);
      if (hasCategory) {
        categorySelect.value = categoryValue;
      }
    }

    createLogoUrl = draft.logoUrl || null;
    setDrinkImagePreview(modal.querySelector('[data-hq-drink-image-preview]'), createLogoUrl);
    syncCreateImageCarryHint(modal, Boolean(createLogoUrl));
    renderSizePricingRows(modal, draft.specPrices || [], draft.basePrice ?? null);
    populateSpecs(modal, draft.brandSpecIds || [], draft.brandToppingIds || []);
    setToppingConfig(modal, draft.brandToppingIds || [], draft.maxToppings || 0);
  }

  function createOptimisticProduct(draft) {
    const tempId = `draft-${Date.now()}-${++tempProductCounter}`;
    return {
      ...buildListProductShape(draft),
      productId: null,
      __tempId: tempId,
      __saveState: SAVE_STATE.SAVING,
      __saveError: '',
      __queueKey: `create:tmp:${tempId}`,
    };
  }

  function upsertProductSaveState(productKey, nextState, errorMessage = '') {
    const product = findProductByKey(productKey);
    if (!product) return null;
    product.__saveState = nextState;
    product.__saveError = errorMessage || '';
    return product;
  }

  function openFailedDrinkDraft(queueKey) {
    const queueItem = getFailedQueueItem(queueKey);
    if (!queueItem) return;

    if (queueItem.mode === 'create') {
      const modal = document.querySelector('[data-hq-drink-create-modal]');
      if (!modal) return;
      applyDraftToCreateDrinkModal(modal, queueItem.draft);
      modal.dataset.failedQueueKey = queueItem.queueKey;
      setActiveDrinkEditor({ mode: 'create', queueKey: queueItem.queueKey, productKey: queueItem.productKey });
      openManagedModal(modal);
      return;
    }

    if (queueItem.mode === 'edit') {
      const modal = document.querySelector('[data-hq-drink-edit-modal]');
      if (!modal) return;
      pendingEditId = queueItem.productId;
      applyDetailToEditDrinkModal(modal, queueItem.draft);
      modal.dataset.failedQueueKey = queueItem.queueKey;
      setActiveDrinkEditor({ mode: 'edit', queueKey: queueItem.queueKey, productKey: queueItem.productKey });
      openManagedModal(modal);
    }
  }

  async function showContinueCreateDrinkPromptManaged() {
    isContinueCreatePromptOpen = true;
    renderFailedDrinkQueuePrompt();
    try {
      return await showContinueCreateDrinkPrompt();
    } finally {
      isContinueCreatePromptOpen = false;
      renderFailedDrinkQueuePrompt();
    }
  }

  function applyDetailToEditDrinkModal(modal, detail) {
    if (!modal) return;

    const nameEl = modal.querySelector('[data-hq-drink-name]');
    const catEl  = modal.querySelector('[data-hq-drink-category]');
    const descEl = modal.querySelector('textarea');
    if (nameEl) nameEl.value = detail?.name || '';
    if (catEl)  catEl.value  = String(detail?.categoryId || '');
    if (descEl) descEl.value = detail?.description || '';
    renderSizePricingRows(modal, detail?.specPrices || [], detail?.basePrice);
    populateSpecs(modal, detail?.brandSpecIds || [], detail?.brandToppingIds || []);
    setToppingConfig(modal, detail?.brandToppingIds || [], detail?.maxToppings);
    editLogoUrl = detail?.logoUrl || null;
    setDrinkImagePreview(modal.querySelector('[data-hq-drink-image-preview]'), editLogoUrl);
  }

  function resetCreateDrinkModal(modal) {
    if (!modal) return;
    clearModalInlineNotice(modal);

    const nameInput = modal.querySelector('[data-hq-drink-name]');
    const categorySelect = modal.querySelector('[data-hq-drink-category]');
    const descriptionInput = modal.querySelector('textarea');
    const imageInput = modal.querySelector('[data-hq-drink-image-input]');
    const draft = lastCreatedDrinkDraft;

    if (nameInput) nameInput.value = '';
    if (descriptionInput) descriptionInput.value = '';
    if (imageInput) imageInput.value = '';

    if (categorySelect && categorySelect.options.length) {
      const hasDraftCategory = draft?.categoryId
        && Array.from(categorySelect.options).some((option) => option.value === String(draft.categoryId));
      if (hasDraftCategory) {
        categorySelect.value = String(draft.categoryId);
      } else {
        categorySelect.selectedIndex = 0;
      }
    }

    createLogoUrl = draft?.logoUrl || null;
    setDrinkImagePreview(modal.querySelector('[data-hq-drink-image-preview]'), createLogoUrl);
    syncCreateImageCarryHint(modal, Boolean(createLogoUrl));
    renderSizePricingRows(modal, draft?.specPrices || [], draft?.basePrice ?? null);
    populateSpecs(modal, draft?.brandSpecIds || [], draft?.brandToppingIds || []);

    if (draft) {
      setToppingConfig(modal, draft.brandToppingIds || [], draft.maxToppings || 0);
    } else {
      resetCreateToppingConfig(modal);
    }
  }

  function cacheLastCreatedDrinkDraft(modal, pricingPayload, toppingPayload, specIds) {
    if (!modal) return;

    lastCreatedDrinkDraft = {
      categoryId: modal.querySelector('[data-hq-drink-category]')?.value || '',
      basePrice: pricingPayload?.basePrice ?? null,
      specPrices: Array.isArray(pricingPayload?.specPrices)
        ? pricingPayload.specPrices.map((item) => ({ ...item }))
        : [],
      brandSpecIds: Array.isArray(specIds) ? [...specIds] : [],
      brandToppingIds: Array.isArray(toppingPayload?.brandToppingIds)
        ? [...toppingPayload.brandToppingIds]
        : [],
      maxToppings: Number(toppingPayload?.maxToppings) || 0,
      logoUrl: createLogoUrl || null,
    };
  }

  function ensureCreateImageCarryHint(modal) {
    if (!modal) return null;

    let hint = modal.querySelector('[data-hq-create-image-carry-hint]');
    if (hint) return hint;

    const helperText = modal.querySelector('[data-hq-drink-image-pick]')?.closest('.flex-1')?.querySelector('p');
    if (!helperText) return null;

    hint = document.createElement('p');
    hint.className = 'mt-2 hidden text-xs font-semibold text-primary';
    hint.setAttribute('data-hq-create-image-carry-hint', 'true');
    hint.textContent = '沿用上一筆圖片，可重新上傳覆蓋';
    helperText.insertAdjacentElement('afterend', hint);
    return hint;
  }

  function syncCreateImageCarryHint(modal, isVisible) {
    const hint = ensureCreateImageCarryHint(modal);
    if (!hint) return;

    hint.classList.toggle('hidden', !isVisible);
  }

  function bindDrinkImageUpload(modal, onUploaded) {
    const inp  = modal.querySelector('[data-hq-drink-image-input]');
    const prev = modal.querySelector('[data-hq-drink-image-preview]');
    if (!inp) return;
    inp.addEventListener('change', () => {
      const file = inp.files?.[0]; if (!file) return;
      if (modal?.matches?.('[data-hq-drink-create-modal]')) {
        syncCreateImageCarryHint(modal, false);
      }
      setDrinkImagePreview(prev, URL.createObjectURL(file));
      const uploadTask = (async () => {
        try {
          const cloudUrl = await BrandAPI.uploadImage(file, 'products');
          setDrinkImagePreview(prev, cloudUrl);
          onUploaded(cloudUrl);
        } catch (err) {
          BrandAPI.toast('圖片上傳失敗：' + err.message, 'error');
          setDrinkImagePreview(prev, null);
          onUploaded(null);
        } finally {
          inp.value = '';
          setModalUploadPromise(modal, null);
        }
      })();
      setModalUploadPromise(modal, uploadTask);
    });
  }

  function buildCard(p) {
    const en = p.isEnabled !== false;
    const validLogo = p.logoUrl && /^https?:\/\//.test(p.logoUrl);
    const saveState = p.__saveState || SAVE_STATE.IDLE;
    const isSaving = saveState === SAVE_STATE.SAVING;
    const isFailed = saveState === SAVE_STATE.FAILED;
    const productKey = getProductKey(p);
    const moveDisabled = isSaving || isFailed || p.productId == null ? 'disabled' : '';
    const statusBadge = isSaving
      ? '<span class="inline-flex items-center rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold text-amber-700">儲存中</span>'
      : isFailed
        ? '<span class="inline-flex items-center rounded-full bg-rose-100 px-2 py-0.5 text-[10px] font-bold text-rose-700">儲存失敗</span>'
        : '';
    const statusText = isSaving
      ? '<p class="mt-1 text-[11px] font-medium text-amber-600">背景儲存中，你可以先處理其他飲品。</p>'
      : isFailed
        ? `<p class="mt-1 text-[11px] font-medium text-rose-600">${p.__saveError || '點擊卡片可重新打開並再次儲存。'}</p>`
        : '';
    const actionDisabled = isSaving ? 'disabled' : '';
    const actionClass = isSaving ? 'opacity-40 pointer-events-none' : '';
    return `
      <div class="flex items-center justify-between p-4 bg-background-light dark:bg-zinc-800 rounded-lg group ${en ? '' : 'opacity-60'} ${isFailed ? 'ring-1 ring-rose-200 dark:ring-rose-900/70' : ''}" data-product-key="${productKey}" data-save-state="${saveState}" ${p.productId != null ? `data-product-id="${p.productId}"` : ''}>
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 bg-slate-200 rounded-lg overflow-hidden ${en ? '' : 'grayscale'}">
            ${validLogo ? `<img src="${p.logoUrl}" class="w-full h-full object-cover"/>` : `<div class="w-full h-full bg-gradient-to-br from-amber-200 to-orange-400"></div>`}
          </div>
          <div>
            <div class="flex items-center gap-2">
              <p class="font-bold">${p.name}</p>
              ${statusBadge}
              <label class="flex items-center cursor-pointer">
                <input class="sr-only peer" type="checkbox" ${en ? 'checked' : ''} ${actionDisabled}/>
                <div class="relative w-9 h-5 bg-slate-200 peer-focus:outline-none rounded-full peer dark:bg-zinc-700 peer-checked:after:translate-x-full rtl:peer-checked:after:-translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:start-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all dark:border-gray-600 peer-checked:bg-primary"></div>
              </label>
            </div>
            <p class="text-xs text-slate-500">${p.description || ''}</p>
            ${statusText}
          </div>
        </div>
        <div class="flex items-center gap-6">
          <div class="text-right">
            <p class="text-sm font-bold ${en ? 'text-primary' : 'text-slate-400'}">${formatPrice(p)}</p>
            <p class="text-[10px] text-slate-400">標準售價</p>
          </div>
          <div class="flex items-center gap-2 ${actionClass}">
            <button type="button" data-hq-drink-move-up ${moveDisabled} class="w-8 h-8 flex items-center justify-center rounded-full bg-white dark:bg-zinc-700 text-slate-600 hover:text-primary hover:shadow-md transition-all"><span class="material-symbols-outlined text-lg">arrow_upward</span></button>
            <button type="button" data-hq-drink-move-down ${moveDisabled} class="w-8 h-8 flex items-center justify-center rounded-full bg-white dark:bg-zinc-700 text-slate-600 hover:text-primary hover:shadow-md transition-all"><span class="material-symbols-outlined text-lg">arrow_downward</span></button>
            <button type="button" data-hq-drink-edit-open ${actionDisabled} class="w-8 h-8 flex items-center justify-center rounded-full bg-white dark:bg-zinc-700 text-slate-600 hover:text-primary hover:shadow-md transition-all"><span class="material-symbols-outlined text-lg">edit</span></button>
            <button type="button" data-hq-drink-delete-open ${actionDisabled} class="w-8 h-8 flex items-center justify-center rounded-full bg-white dark:bg-zinc-700 text-slate-600 hover:text-red-500 hover:shadow-md transition-all"><span class="material-symbols-outlined text-lg">delete</span></button>
          </div>
        </div>
      </div>`;
  }

  function buildPanel(cat) {
    const categoryProducts = products
      .filter(p => p.categoryId === cat.id)
      .sort(compareProductsBySortOrder);
    const sec = document.createElement('section');
    sec.setAttribute('data-hq-menu-category-panel', cat.name);
    sec.setAttribute('data-category-id', cat.id);
    sec.setAttribute('data-hq-category-region-north',   String(cat.northOffset   ?? 0));
    sec.setAttribute('data-hq-category-region-central', String(cat.centralOffset ?? 0));
    sec.setAttribute('data-hq-category-region-south',   String(cat.southOffset   ?? 0));
    sec.className = 'bg-white dark:bg-zinc-900 rounded-xl p-6 shadow-sm border border-slate-100 dark:border-zinc-800';
    sec.innerHTML = `
      <div class="flex items-center justify-between mb-6">
        <h3 class="text-lg font-bold flex items-center gap-2">
          <span class="w-1.5 h-6 bg-primary rounded-full"></span>
          <span data-hq-category-title>${cat.name}</span>
        </h3>
        <div class="flex items-center gap-2">
          <button type="button" data-hq-category-edit-open data-hq-category-key="${cat.name}" data-category-id="${cat.id}" class="text-primary text-sm font-bold flex items-center hover:underline">編輯分類</button>
        </div>
      </div>
      <div class="space-y-4" data-category-products>
        ${categoryProducts.map(buildCard).join('')
          || '<p class="text-sm text-slate-400 text-center py-4">此分類尚無飲品</p>'}
      </div>`;
    return sec;
  }

  function renderMenu() {
    const tabsRoot = document.querySelector('[data-hq-menu-category-tabs]');
    const gridRoot = document.querySelector('[data-hq-menu-category-grid]');
    if (!tabsRoot || !gridRoot) return;
    const activeTabKey = Array.from(tabsRoot.querySelectorAll('button[data-hq-menu-category-tab]'))
      .find(button => button.getAttribute('aria-selected') === 'true')
      ?.getAttribute('data-hq-menu-category-tab') || '全部';
    const categorySelectStates = Array.from(document.querySelectorAll('select[data-hq-drink-category]'))
      .map(select => ({
        select,
        value: select.value,
      }));
    const sortedCategories = [...categories].sort(compareCategoriesBySortOrder);
    // 只移除分類按鈕，保留「全部」避免 layout jump
    Array.from(tabsRoot.querySelectorAll('button[data-hq-menu-category-tab]'))
      .filter(b => b.getAttribute('data-hq-menu-category-tab') !== '全部')
      .forEach(b => b.remove());
    gridRoot.innerHTML = '';
    sortedCategories.forEach(cat => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.setAttribute('data-hq-menu-category-tab', cat.name);
      btn.setAttribute('data-category-id', String(cat.id));
      btn.setAttribute('draggable', 'true');
      btn.setAttribute('role', 'tab');
      btn.setAttribute('aria-selected', 'false');
      btn.className = 'shrink-0 rounded-t-xl border-b-2 border-transparent bg-transparent px-6 py-3 text-sm font-medium text-slate-500 transition-all duration-150 hover:bg-slate-100/80 hover:text-slate-800 cursor-grab';
      btn.textContent = cat.name;
      tabsRoot.appendChild(btn);
      gridRoot.appendChild(buildPanel(cat));
    });
    categorySelectStates.forEach(({ select, value }) => {
      select.innerHTML = sortedCategories.map(c => `<option value="${c.id}">${c.name}</option>`).join('');
      const hasValue = value && Array.from(select.options).some(option => option.value === String(value));
      if (hasValue) {
        select.value = String(value);
      } else if (select.options.length) {
        select.selectedIndex = 0;
      }
    });
    const restoreTab = Array.from(tabsRoot.querySelectorAll('button[data-hq-menu-category-tab]'))
      .find(button => button.getAttribute('data-hq-menu-category-tab') === activeTabKey)
      || tabsRoot.querySelector('[data-hq-menu-category-tab="全部"]');
    restoreTab?.click();
    renderFailedDrinkQueuePrompt();
  }

  async function refreshMenuCollections() {
    [categories, products] = await Promise.all([
      BrandAPI.refreshCategories(),
      BrandAPI.refreshProducts(),
    ]);
    persistMenuSnapshots();
    renderMenu();
  }

  // ─── Initial load ─────────────────────────────────────────
  try {
    if (hydrateMenuSnapshots()) {
      renderMenu();
    }

    [categories, products, brandSpecs, brandToppings] = await Promise.all([
      BrandAPI.refreshCategories(), BrandAPI.refreshProducts(),
      BrandAPI.refreshSpecs(), BrandAPI.refreshToppings()
    ]);
    persistMenuSnapshots();
    if (!BrandAPI.hasAnyEnabledSpecs(brandSpecs)) {
      await BrandAPI.showInfoDialog(MENU_SETUP_REQUIRED_MESSAGE, {
        title: "請先完成規格設定",
        confirmText: "我知道了",
      });
      window.location.replace(BrandAPI.BRAND_MENU_SETUP_PAGE);
      return;
    }
    renderMenu();
    const createM = document.querySelector('[data-hq-drink-create-modal]');
    const editM   = document.querySelector('[data-hq-drink-edit-modal]');
    if (createM) {
      ensureDrinkCustomizationSections(createM);
      wireToppingControlEvents(createM);
      ensureModalScrollTopButton(createM);
      bindDrinkImageUpload(createM, url => { createLogoUrl = url; });
      populateSpecs(createM, [], []);
      renderSizePricingRows(createM, [], null);
      resetCreateToppingConfig(createM);
    }
    if (editM) {
      ensureDrinkCustomizationSections(editM);
      wireToppingControlEvents(editM);
      ensureModalScrollTopButton(editM);
      bindDrinkImageUpload(editM, url => { editLogoUrl = url; });
      // 移除圖片按鈕
      editM.querySelector('[data-hq-drink-image-remove]')?.addEventListener('click', () => {
        editLogoUrl = '';  // 空字串 = 明確清除；後端 isEmpty() → setLogoUrl(null)
        setDrinkImagePreview(editM.querySelector('[data-hq-drink-image-preview]'), null);
      });
      populateSpecs(editM, [], []);
      renderSizePricingRows(editM, [], null);
      setToppingConfig(editM, [], 0);
    }
  } catch (e) { BrandAPI.toast('載入菜單失敗：' + e.message, 'error'); console.error(e); }

  // ─── 新增飲品 ──────────────────────────────────────────────
  const createModal = document.querySelector('[data-hq-drink-create-modal]');
  if (createModal) {
    const saveBtnEl = createModal.querySelector('[data-hq-drink-create-save]');
    if (saveBtnEl) {
      saveBtnEl.addEventListener('click', async () => {
        const name  = createModal.querySelector('[data-hq-drink-name]')?.value?.trim();
        const catId = createModal.querySelector('[data-hq-drink-category]')?.value;
        const desc  = createModal.querySelector('textarea')?.value?.trim() || null;
        clearModalInlineNotice(createModal);
        if (!name)  { showModalInlineNotice(createModal, '請輸入飲品名稱'); return; }
        if (!catId) { showModalInlineNotice(createModal, '請選擇分類'); return; }
        let pricingPayload;
        let toppingPayload;
        try {
          pricingPayload = getProductPricingPayload(createModal);
          validateRequiredSpecSelections(createModal);
          toppingPayload = getProductToppingPayload(createModal);
        } catch (validationError) {
          showModalInlineNotice(createModal, validationError.message);
          return;
        }
        const specIds = getCheckedSpecIds(createModal);
        saveBtnEl.disabled = true; saveBtnEl.textContent = '儲存中...';
        if (pendingUploadPromise) await pendingUploadPromise;
        try {
          const newP = await BrandAPI.createProduct({
            categoryId: Number(catId), name, description: desc,
            basePrice: pricingPayload.basePrice,
            specPrices: pricingPayload.specPrices,
            maxToppings: toppingPayload.maxToppings,
            brandSpecIds: specIds,
            brandToppingIds: toppingPayload.brandToppingIds,
            logoUrl: createLogoUrl || null
          });
          if (toppingPayload.maxToppings > 0) {
            cacheCreateMaxToppings(toppingPayload.maxToppings);
          }
          cacheLastCreatedDrinkDraft(createModal, pricingPayload, toppingPayload, specIds);
          products.push(newP);
          persistMenuSnapshots();
          hasPendingMenuRefresh = true;
          clearModalInlineNotice(createModal);
          createModal.classList.add('hidden');
          createModal.setAttribute('aria-hidden', 'true');
          const shouldContinue = await showContinueCreateDrinkPrompt();
          if (shouldContinue) {
            resetCreateDrinkModal(createModal);
            createModal.classList.remove('hidden');
            createModal.setAttribute('aria-hidden', 'false');
            createModal.querySelector('[data-hq-drink-name]')?.focus();
          } else {
            flushPendingMenuRefresh();
          }
          BrandAPI.toast('飲品新增成功！');
        } catch (e) { showModalInlineNotice(createModal, `新增失敗：${e.message}`); }
        finally { saveBtnEl.disabled = false; saveBtnEl.textContent = '儲存變更'; }
      });
    }
  }

  // ─── 編輯飲品 ──────────────────────────────────────────────
  const editModal = document.querySelector('[data-hq-drink-edit-modal]');
  const editSaveBtnRef = editModal?.querySelector('[data-hq-drink-edit-save]');
  [createModal, editModal].forEach(modal => {
    if (!modal) return;
    ensureModalInlineNotice(modal);
    modal.querySelectorAll('[data-hq-modal-close],[data-hq-modal-backdrop]').forEach(el => {
      el.addEventListener('click', () => {
        clearModalInlineNotice(modal);
        if (modal === createModal) {
          flushPendingMenuRefresh();
        }
      });
    });
  });

  window.addEventListener('hq:category-tab-reorder-request', async event => {
    const orderedCategoryIds = event?.detail?.orderedCategoryIds;
    if (!Array.isArray(orderedCategoryIds) || !orderedCategoryIds.length) {
      renderMenu();
      return;
    }
    const normalizedOrderedIds = orderedCategoryIds
      .map(categoryId => Number(categoryId))
      .filter(categoryId => Number.isFinite(categoryId) && categoryId > 0);
    if (!normalizedOrderedIds.length) {
      renderMenu();
      return;
    }
    if (JSON.stringify(normalizedOrderedIds) === JSON.stringify(getCurrentCategoryOrderIds())) {
      renderMenu();
      return;
    }
    if (isDrinkEditorBusy()) {
      renderMenu();
      BrandAPI.toast('請先完成目前的新增或編輯，再調整分類順序', 'error');
      return;
    }
    if (isCategoryTabReordering) {
      renderMenu();
      return;
    }

    isCategoryTabReordering = true;
    try {
      await BrandAPI.reorderCategories(normalizedOrderedIds);
      await refreshMenuCollections();
    } catch (err) {
      try {
        await refreshMenuCollections();
      } catch (_) {
        renderMenu();
      }
      if (JSON.stringify(normalizedOrderedIds) === JSON.stringify(getCurrentCategoryOrderIds())) {
        return;
      }
      renderMenu();
      BrandAPI.toast('分類順序操作太頻繁，請放慢速度', 'error');
    } finally {
      isCategoryTabReordering = false;
    }
  });

  document.addEventListener('click', async e => {
    const btn = e.target.closest('[data-hq-drink-edit-open]');
    if (!btn || !editModal) return;
    const card = btn.closest('[data-product-id]');
    if (!card) return;
    pendingEditId = Number(card.getAttribute('data-product-id'));
    clearModalInlineNotice(editModal);

    if (editSaveBtnRef) { editSaveBtnRef.disabled = true; editSaveBtnRef.textContent = '載入中...'; }
    try {
      const cachedDetail = detailCache.get(pendingEditId);
      if (cachedDetail) {
        applyDetailToEditDrinkModal(editModal, cachedDetail);
        openManagedModal(editModal);
      }
      const freshDetail = await BrandAPI.refreshProductDetail(pendingEditId);
      detailCache.set(pendingEditId, freshDetail);
      applyDetailToEditDrinkModal(editModal, freshDetail);
      if (editModal.classList.contains('hidden')) {
        openManagedModal(editModal);
      }
    } catch (err) { showModalInlineNotice(editModal, `載入失敗：${err.message}`); console.error(err); }
    finally {
      if (editSaveBtnRef) { editSaveBtnRef.disabled = false; editSaveBtnRef.textContent = '儲存變更'; }
    }
  });

  if (editSaveBtnRef) {
    editSaveBtnRef.addEventListener('click', async () => {
      if (!pendingEditId) return;
      const name  = editModal.querySelector('[data-hq-drink-name]')?.value?.trim();
      const catId = editModal.querySelector('[data-hq-drink-category]')?.value;
      const desc  = editModal.querySelector('textarea')?.value?.trim() || null;
      clearModalInlineNotice(editModal);
      if (!name)  { showModalInlineNotice(editModal, '請輸入飲品名稱'); return; }
      if (!catId) { showModalInlineNotice(editModal, '請選擇分類'); return; }
      let pricingPayload;
      let toppingPayload;
      try {
        pricingPayload = getProductPricingPayload(editModal);
        validateRequiredSpecSelections(editModal);
        toppingPayload = getProductToppingPayload(editModal);
      } catch (validationError) {
        showModalInlineNotice(editModal, validationError.message);
        return;
      }
      editSaveBtnRef.disabled = true; editSaveBtnRef.textContent = '儲存中...';
      if (pendingUploadPromise) await pendingUploadPromise;
      try {
        const upd = await BrandAPI.updateProduct(pendingEditId, {
          name,
          description: desc,
          categoryId: Number(catId),
          basePrice: pricingPayload.basePrice,
          specPrices: pricingPayload.specPrices,
          maxToppings: toppingPayload.maxToppings,
          brandSpecIds: getCheckedSpecIds(editModal),
          brandToppingIds: toppingPayload.brandToppingIds,
          logoUrl: editLogoUrl ?? null
        });
        const idx = products.findIndex(p => p.productId === pendingEditId);
        if (idx >= 0) {
          products[idx] = { ...products[idx], ...upd, productId: pendingEditId };
          detailCache.delete(pendingEditId);
          persistMenuSnapshots();
          const existingCard = document.querySelector(`[data-product-id="${pendingEditId}"]`);
          if (existingCard) {
            const tmp = document.createElement('div');
            tmp.innerHTML = buildCard(products[idx]);
            existingCard.replaceWith(tmp.firstElementChild);
          } else { renderMenu(); }
        } else { renderMenu(); }
        clearModalInlineNotice(editModal);
        editModal.querySelector('[data-hq-modal-close]')?.click();
        BrandAPI.toast('飲品更新成功！');
      } catch (e) { showModalInlineNotice(editModal, `更新失敗：${e.message}`); console.error(e); }
      finally { editSaveBtnRef.disabled = false; editSaveBtnRef.textContent = '儲存變更'; pendingEditId = null; }
    });
  }

  // ─── 刪除飲品 ──────────────────────────────────────────────
  const delModal = document.querySelector('[data-hq-drink-delete-modal]');
  document.addEventListener('click', e => {
    const btn = e.target.closest('[data-hq-drink-delete-open]');
    if (!btn) return;
    const card = btn.closest('[data-product-id]');
    if (card) pendingDeleteId = Number(card.getAttribute('data-product-id'));
  });
  const delConfirm = delModal?.querySelector('[data-hq-drink-delete-confirm]');
  if (delConfirm) {
    const f = delConfirm.cloneNode(true);
    delConfirm.replaceWith(f);
    f.addEventListener('click', async () => {
      if (!pendingDeleteId) return;
      f.disabled = true; f.textContent = '刪除中...';
      try {
        await BrandAPI.deleteProduct(pendingDeleteId);
        products = products.filter(p => p.productId !== pendingDeleteId);
        persistMenuSnapshots();
        renderMenu();
        delModal.querySelector('[data-hq-modal-close]')?.click();
        BrandAPI.toast('飲品已刪除');
      } catch (e) { BrandAPI.toast('刪除失敗：' + e.message, 'error'); }
      finally { f.disabled = false; f.textContent = '確定刪除'; pendingDeleteId = null; }
    });
  }

  // ─── 開啟新增飲品 Modal ────────────────────────────────────
  const drinkCreateOpenBtn = document.querySelector('[data-hq-drink-create-open]');
  drinkCreateOpenBtn?.addEventListener('click', () => {
    const m = document.querySelector('[data-hq-drink-create-modal]');
    if (m) {
      resetCreateDrinkModal(m);
      openManagedModal(m);
    }
  });

  const createSaveBtnOverride = createModal?.querySelector('[data-hq-drink-create-save]');
  if (createModal && createSaveBtnOverride) {
    const nextCreateSaveBtn = createSaveBtnOverride.cloneNode(true);
    createSaveBtnOverride.replaceWith(nextCreateSaveBtn);

    nextCreateSaveBtn.addEventListener('click', async () => {
      const name = createModal.querySelector('[data-hq-drink-name]')?.value?.trim();
      const catId = createModal.querySelector('[data-hq-drink-category]')?.value;
      const desc = createModal.querySelector('textarea')?.value?.trim() || null;
      clearModalInlineNotice(createModal);
      if (!name) { showModalInlineNotice(createModal, '請輸入飲品名稱'); return; }
      if (!catId) { showModalInlineNotice(createModal, '請選擇飲品分類'); return; }

      let pricingPayload;
      let toppingPayload;
      try {
        pricingPayload = getProductPricingPayload(createModal);
        validateRequiredSpecSelections(createModal);
        toppingPayload = getProductToppingPayload(createModal);
      } catch (validationError) {
        showModalInlineNotice(createModal, validationError.message);
        return;
      }

      const specIds = getCheckedSpecIds(createModal);
      nextCreateSaveBtn.disabled = true;
      nextCreateSaveBtn.textContent = '儲存中...';

      if (getModalUploadPromise(createModal)) {
        showModalInlineNotice(createModal, '圖片仍在上傳，請等待完成後再儲存');
        nextCreateSaveBtn.disabled = false;
        nextCreateSaveBtn.textContent = '儲存';
        return;
      }

      const draft = buildDrinkDraft(createModal, pricingPayload, toppingPayload, specIds, createLogoUrl || null);
      const retryQueueKey = createModal.dataset.failedQueueKey || '';
      const retryQueueItem = retryQueueKey ? getFailedQueueItem(retryQueueKey) : null;
      const existingRetryProduct = retryQueueItem ? findProductByKey(retryQueueItem.productKey) : null;
      const optimisticProduct = existingRetryProduct
        ? {
            ...existingRetryProduct,
            ...buildListProductShape({
              ...existingRetryProduct,
              ...draft,
              categoryId: Number(draft.categoryId) || null,
            }),
            __saveState: SAVE_STATE.SAVING,
            __saveError: '',
            __queueKey: retryQueueKey,
          }
        : createOptimisticProduct(draft);
      const productKey = getProductKey(optimisticProduct);

      try {
        if (toppingPayload.maxToppings > 0) {
          cacheCreateMaxToppings(toppingPayload.maxToppings);
        }
        cacheLastCreatedDrinkDraft(createModal, pricingPayload, toppingPayload, specIds);

        if (retryQueueKey) {
          removeFailedDrinkDraft(retryQueueKey);
        }

        if (existingRetryProduct) {
          replaceProductByKey(productKey, optimisticProduct);
        } else {
          products.push(optimisticProduct);
        }

        persistMenuSnapshots();
        renderMenu();
        clearModalInlineNotice(createModal);
        closeManagedDrinkModal(createModal);
        clearActiveDrinkEditor();

        void (async () => {
          try {
            const newP = await BrandAPI.createProduct({
              categoryId: Number(catId),
              name,
              description: desc,
              basePrice: pricingPayload.basePrice,
              specPrices: pricingPayload.specPrices,
              maxToppings: toppingPayload.maxToppings,
              brandSpecIds: specIds,
              brandToppingIds: toppingPayload.brandToppingIds,
              logoUrl: draft.logoUrl || null
            });

            const persistedProduct = {
              ...buildListProductShape({
                ...optimisticProduct,
                ...newP,
                productId: newP.productId,
              }),
            };
            delete persistedProduct.__tempId;
            delete persistedProduct.__saveState;
            delete persistedProduct.__saveError;
            delete persistedProduct.__queueKey;

            replaceProductByKey(productKey, persistedProduct);
            removeFailedDrinkDraft(optimisticProduct.__queueKey);
            persistMenuSnapshots();
            renderMenu();
            BrandAPI.toast('飲品已同步建立完成');
          } catch (e) {
            upsertProductSaveState(productKey, SAVE_STATE.FAILED, e.message);
            enqueueFailedDrinkDraft({
              queueKey: optimisticProduct.__queueKey,
              productKey,
              mode: 'create',
              draft,
              errorMessage: e.message,
            });
            persistMenuSnapshots();
            renderMenu();
            BrandAPI.toast(`建立飲品失敗：${e.message}`, 'error');
          }
        })();

        if (!retryQueueKey) {
          const shouldContinue = await showContinueCreateDrinkPromptManaged();
          if (shouldContinue) {
            resetCreateDrinkModal(createModal);
            delete createModal.dataset.failedQueueKey;
            setActiveDrinkEditor({ mode: 'create' });
            openManagedModal(createModal);
          }
        }
      } catch (e) {
        showModalInlineNotice(createModal, `建立飲品失敗：${e.message}`);
      } finally {
        nextCreateSaveBtn.disabled = false;
        nextCreateSaveBtn.textContent = '儲存';
      }
    });
  }

  const editSaveBtnOverride = editModal?.querySelector('[data-hq-drink-edit-save]');
  if (editModal && editSaveBtnOverride) {
    const nextEditSaveBtn = editSaveBtnOverride.cloneNode(true);
    editSaveBtnOverride.replaceWith(nextEditSaveBtn);

    nextEditSaveBtn.addEventListener('click', async () => {
      if (!pendingEditId) return;

      const name = editModal.querySelector('[data-hq-drink-name]')?.value?.trim();
      const catId = editModal.querySelector('[data-hq-drink-category]')?.value;
      const desc = editModal.querySelector('textarea')?.value?.trim() || null;
      clearModalInlineNotice(editModal);
      if (!name) { showModalInlineNotice(editModal, '請輸入飲品名稱'); return; }
      if (!catId) { showModalInlineNotice(editModal, '請選擇飲品分類'); return; }

      let pricingPayload;
      let toppingPayload;
      try {
        pricingPayload = getProductPricingPayload(editModal);
        validateRequiredSpecSelections(editModal);
        toppingPayload = getProductToppingPayload(editModal);
      } catch (validationError) {
        showModalInlineNotice(editModal, validationError.message);
        return;
      }

      const specIds = getCheckedSpecIds(editModal);
      nextEditSaveBtn.disabled = true;
      nextEditSaveBtn.textContent = '儲存中...';

      if (getModalUploadPromise(editModal)) {
        showModalInlineNotice(editModal, '圖片仍在上傳，請等待完成後再儲存');
        nextEditSaveBtn.disabled = false;
        nextEditSaveBtn.textContent = '儲存';
        return;
      }

      try {
        const currentProduct = products.find(product => product.productId === pendingEditId);
        if (!currentProduct) {
          throw new Error('找不到要更新的飲品');
        }

        const productId = pendingEditId;
        const productKey = getProductKey(currentProduct);
        const queueKey = editModal.dataset.failedQueueKey || `edit:${productId}`;
        const draft = buildDrinkDraft(editModal, pricingPayload, toppingPayload, specIds, editLogoUrl ?? null);
        const optimisticProduct = {
          ...buildListProductShape({
            ...currentProduct,
            ...draft,
            productId,
            categoryId: Number(draft.categoryId) || null,
          }),
          __saveState: SAVE_STATE.SAVING,
          __saveError: '',
          __queueKey: queueKey,
        };

        removeFailedDrinkDraft(queueKey);
        replaceProductByKey(productKey, optimisticProduct);
        persistMenuSnapshots();
        renderMenu();
        clearModalInlineNotice(editModal);
        closeManagedDrinkModal(editModal);
        clearActiveDrinkEditor();
        pendingEditId = null;

        void (async () => {
          try {
            const upd = await BrandAPI.updateProduct(productId, {
              name,
              description: desc,
              categoryId: Number(catId),
              basePrice: pricingPayload.basePrice,
              specPrices: pricingPayload.specPrices,
              maxToppings: toppingPayload.maxToppings,
              brandSpecIds: specIds,
              brandToppingIds: toppingPayload.brandToppingIds,
              logoUrl: draft.logoUrl ?? null
            });

            const persistedProduct = {
              ...buildListProductShape({
                ...optimisticProduct,
                ...upd,
                productId,
              }),
            };
            delete persistedProduct.__saveState;
            delete persistedProduct.__saveError;
            delete persistedProduct.__queueKey;

            replaceProductByKey(productKey, persistedProduct);
            detailCache.delete(productId);
            removeFailedDrinkDraft(queueKey);
            persistMenuSnapshots();
            renderMenu();
            BrandAPI.toast('飲品更新完成');
          } catch (e) {
            upsertProductSaveState(productKey, SAVE_STATE.FAILED, e.message);
            enqueueFailedDrinkDraft({
              queueKey,
              productKey,
              mode: 'edit',
              productId,
              draft,
              errorMessage: e.message,
            });
            persistMenuSnapshots();
            renderMenu();
            BrandAPI.toast(`更新飲品失敗：${e.message}`, 'error');
            console.error(e);
          }
        })();
      } catch (e) {
        showModalInlineNotice(editModal, `更新飲品失敗：${e.message}`);
        console.error(e);
      } finally {
        nextEditSaveBtn.disabled = false;
        nextEditSaveBtn.textContent = '儲存';
      }
    });
  }

  [createModal, editModal].forEach(modal => {
    if (!modal) return;
    modal.querySelectorAll('[data-hq-modal-close],[data-hq-modal-backdrop]').forEach(el => {
      el.addEventListener('click', () => {
        if (modal === editModal) {
          pendingEditId = null;
        }
        clearActiveDrinkEditor();
        delete modal.dataset.failedQueueKey;
      });
    });
  });

  document.addEventListener('click', e => {
    const card = e.target.closest('[data-product-key][data-save-state="failed"]');
    if (!card) return;
    if (e.target.closest('button, input, label, textarea, select, a, [role="button"]')) return;
    if (isDrinkEditorBusy()) {
      BrandAPI.toast('請先完成目前的新增或編輯，再處理失敗項目', 'error');
      return;
    }
    const queueItem = getFailedQueueItemByProductKey(card.getAttribute('data-product-key'));
    if (queueItem) {
      openFailedDrinkDraft(queueItem.queueKey);
    }
  });

  document.addEventListener('click', async e => {
    const btn = e.target.closest('[data-hq-drink-edit-open]');
    if (!btn || !editModal) return;

    e.preventDefault();
    e.stopImmediatePropagation();

    if (isDrinkEditorBusy()) {
      BrandAPI.toast('請先完成目前的新增或編輯，再處理其他飲品', 'error');
      return;
    }

    const card = btn.closest('[data-product-key]');
    if (!card) return;

    const productKey = card.getAttribute('data-product-key');
    const failedQueueItem = getFailedQueueItemByProductKey(productKey);
    if (failedQueueItem) {
      openFailedDrinkDraft(failedQueueItem.queueKey);
      return;
    }

    pendingEditId = Number(card.getAttribute('data-product-id'));
    if (!pendingEditId) return;
    clearModalInlineNotice(editModal);

    const liveEditSaveBtn = editModal.querySelector('[data-hq-drink-edit-save]');
    if (liveEditSaveBtn) {
      liveEditSaveBtn.disabled = true;
      liveEditSaveBtn.textContent = '載入中...';
    }

    try {
      const cachedDetail = detailCache.get(pendingEditId);
      if (cachedDetail) {
        applyDetailToEditDrinkModal(editModal, cachedDetail);
        setActiveDrinkEditor({ mode: 'edit', productKey });
        openManagedModal(editModal);
      }

      const freshDetail = await BrandAPI.refreshProductDetail(pendingEditId);
      detailCache.set(pendingEditId, freshDetail);
      applyDetailToEditDrinkModal(editModal, freshDetail);
      if (editModal.classList.contains('hidden')) {
        setActiveDrinkEditor({ mode: 'edit', productKey });
        openManagedModal(editModal);
      }
    } catch (err) {
      showModalInlineNotice(editModal, `載入飲品失敗：${err.message}`);
      if (editModal.classList.contains('hidden')) {
        clearActiveDrinkEditor();
      }
      BrandAPI.toast(`載入飲品失敗：${err.message}`, 'error');
      console.error(err);
    } finally {
      if (liveEditSaveBtn) {
        liveEditSaveBtn.disabled = false;
        liveEditSaveBtn.textContent = '儲存';
      }
    }
  }, true);

  document.addEventListener('click', e => {
    const btn = e.target.closest('[data-hq-drink-delete-open]');
    if (!btn) return;

    const card = btn.closest('[data-product-key]');
    if (!card) return;

    const productId = Number(card.getAttribute('data-product-id'));
    if (productId) return;

    e.preventDefault();
    e.stopImmediatePropagation();

    const productKey = card.getAttribute('data-product-key');
    const failedQueueItem = getFailedQueueItemByProductKey(productKey);
    removeFailedDrinkDraft(failedQueueItem?.queueKey || findProductByKey(productKey)?.__queueKey);
    removeProductByKey(productKey);
    persistMenuSnapshots();
    renderMenu();
    BrandAPI.toast('已移除未成功儲存的飲品草稿');
  }, true);

  document.addEventListener('click', e => {
    const btn = e.target.closest('[data-hq-drink-create-open]');
    if (!btn || !createModal) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    resetCreateDrinkModal(createModal);
    delete createModal.dataset.failedQueueKey;
    setActiveDrinkEditor({ mode: 'create' });
    openManagedModal(createModal);
  }, true);

  document.addEventListener('click', async e => {
    const btn = e.target.closest('[data-hq-drink-move-up],[data-hq-drink-move-down]');
    if (!btn) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (isDrinkEditorBusy()) {
      BrandAPI.toast('請先完成目前的新增或編輯，再調整飲品順序', 'error');
      return;
    }
    const card = btn.closest('[data-product-id]');
    const productId = Number(card?.getAttribute('data-product-id'));
    if (!productId) return;
    btn.disabled = true;
    try {
      if (btn.matches('[data-hq-drink-move-up]')) {
        await BrandAPI.moveProductUp(productId);
      } else {
        await BrandAPI.moveProductDown(productId);
      }
      await refreshMenuCollections();
    } catch (err) {
      BrandAPI.toast(`調整飲品順序操作太頻繁，請放慢速度`, 'error');
    } finally {
      btn.disabled = false;
    }
  }, true);

  // ─── 開啟新增分類 Modal ────────────────────────────────────
  const catCreateOpenBtn = document.querySelector('[data-hq-category-create-open]');
  catCreateOpenBtn?.addEventListener('click', () => {
    const m = document.querySelector('[data-hq-category-create-modal]');
    if (m) {
      clearModalInlineNotice(m);
      m.classList.remove('hidden');
      m.querySelector('[data-hq-category-create-input]')?.focus();
    }
  });
  document.querySelector('[data-hq-category-create-modal]')
    ?.querySelectorAll('[data-hq-modal-close],[data-hq-modal-backdrop]')
    .forEach(el => el.addEventListener('click', () => {
      const modal = document.querySelector('[data-hq-category-create-modal]');
      clearModalInlineNotice(modal);
      modal?.classList.add('hidden');
    }));

  // ─── 新增分類 ──────────────────────────────────────────────
  const createCatModal = document.querySelector('[data-hq-category-create-modal]');
  ensureCategoryRequiredMarkers(createCatModal, '[data-hq-category-create-input]');
  const catSubmit = createCatModal?.querySelector('[data-hq-category-create-submit]');
  if (catSubmit) {
    ensureModalInlineNotice(createCatModal);
    const f = catSubmit.cloneNode(true); catSubmit.replaceWith(f);
    f.addEventListener('click', async () => {
      clearModalInlineNotice(createCatModal);
      const inp  = createCatModal.querySelector('[data-hq-category-create-input]');
      const name = inp?.value?.trim();
      if (!name) { showModalInlineNotice(createCatModal, '請輸入分類名稱'); return; }
      const region = {
        north:   createCatModal.querySelector('[data-hq-category-create-region-north]')?.value   ?? 0,
        central: createCatModal.querySelector('[data-hq-category-create-region-central]')?.value ?? 0,
        south:   createCatModal.querySelector('[data-hq-category-create-region-south]')?.value   ?? 0,
      };
      f.disabled = true; f.textContent = '新增中...';
      try {
        const res = await BrandAPI.createCategory(name, region);
        const nc = Array.isArray(res) ? res[0] : res;
        if (nc) categories.push(nc);
        persistMenuSnapshots();
        renderMenu();
        if (inp) inp.value = '';
        clearModalInlineNotice(createCatModal);
        createCatModal.querySelector('[data-hq-modal-close]')?.click();
        BrandAPI.toast('分類新增成功！');
      } catch (e) { showModalInlineNotice(createCatModal, '新增失敗：' + e.message); }
      finally { f.disabled = false; f.textContent = '新增'; }
    });
  }

  // ─── 編輯分類 ──────────────────────────────────────────────
  const editCatModal = document.querySelector('[data-hq-category-edit-modal]');
  ensureCategoryRequiredMarkers(editCatModal, '[data-hq-category-edit-input]');
  document.addEventListener('click', e => {
    const btn = e.target.closest('[data-hq-category-edit-open]');
    if (!btn || !editCatModal) return;
    pendingEditCatId = Number(btn.getAttribute('data-category-id')) || null;
    // 填入目前分類名稱並開啟 modal
    const cat = categories.find(c => c.id === pendingEditCatId);
    const inp = editCatModal.querySelector('[data-hq-category-edit-input]');
    if (inp) inp.value = cat?.name || btn.getAttribute('data-hq-category-key') || '';
    clearModalInlineNotice(editCatModal);
    editCatModal.classList.remove('hidden');
    inp?.focus(); inp?.select();
  });
  editCatModal?.querySelectorAll('[data-hq-modal-close],[data-hq-modal-backdrop]').forEach(el =>
    el.addEventListener('click', () => {
      clearModalInlineNotice(editCatModal);
      editCatModal.classList.add('hidden');
      pendingEditCatId = null;
    }));

  // 刪除此分類按鈕 → 開啟刪除確認 modal
  const editCatDeleteBtn = editCatModal?.querySelector('[data-hq-category-edit-delete]');
  editCatDeleteBtn?.addEventListener('click', () => {
    const cat = categories.find(c => c.id === pendingEditCatId);
    if (!cat) return;
    const delCatModalRef = document.querySelector('[data-hq-category-delete-modal]');
    if (!delCatModalRef) return;
    const nameEl = delCatModalRef.querySelector('[data-hq-category-delete-name]');
    if (nameEl) nameEl.textContent = cat.name;
    delCatModalRef.classList.remove('hidden');
  });

  const editCatSave = editCatModal?.querySelector('[data-hq-category-edit-save]');
  if (editCatSave) {
    ensureModalInlineNotice(editCatModal);
    const f = editCatSave.cloneNode(true); editCatSave.replaceWith(f);
    f.addEventListener('click', async () => {
      clearModalInlineNotice(editCatModal);
      if (!pendingEditCatId) return;
      const inp  = editCatModal.querySelector('[data-hq-category-edit-input]');
      const name = inp?.value?.trim();
      if (!name) { showModalInlineNotice(editCatModal, '請輸入分類名稱'); return; }
      const region = {
        north:   editCatModal.querySelector('[data-hq-category-edit-region-north]')?.value   ?? 0,
        central: editCatModal.querySelector('[data-hq-category-edit-region-central]')?.value ?? 0,
        south:   editCatModal.querySelector('[data-hq-category-edit-region-south]')?.value   ?? 0,
      };
      f.disabled = true; f.textContent = '儲存中...';
      try {
        const updated = await BrandAPI.renameCategory(pendingEditCatId, name, region);
        const cat = categories.find(c => c.id === pendingEditCatId);
        if (cat) {
          cat.name = updated.name ?? name;
          cat.northOffset   = updated.northOffset   ?? Number(region.north);
          cat.centralOffset = updated.centralOffset ?? Number(region.central);
          cat.southOffset   = updated.southOffset   ?? Number(region.south);
        }
        persistMenuSnapshots();
        renderMenu();
        clearModalInlineNotice(editCatModal);
        editCatModal.querySelector('[data-hq-modal-close]')?.click();
        BrandAPI.toast('分類已更新');
      } catch (e) { showModalInlineNotice(editCatModal, '更新失敗：' + e.message); }
      finally { f.disabled = false; f.textContent = '儲存'; pendingEditCatId = null; }
    });
  }

  // ─── 刪除分類 ──────────────────────────────────────────────
  const delCatModal = document.querySelector('[data-hq-category-delete-modal]');
  const delCatConfirm = delCatModal?.querySelector('[data-hq-category-delete-confirm]');
  if (delCatConfirm) {
    const f = delCatConfirm.cloneNode(true); delCatConfirm.replaceWith(f);
    f.addEventListener('click', async () => {
      const nameEl  = delCatModal.querySelector('[data-hq-category-delete-name]');
      const catName = nameEl?.textContent?.trim();
      const cat = categories.find(c => c.name === catName) || categories.find(c => c.id === pendingEditCatId);
      if (!cat) { BrandAPI.toast('找不到分類', 'error'); return; }
      f.disabled = true; f.textContent = '刪除中...';
      try {
        await BrandAPI.deleteCategory(cat.id);
        categories = categories.filter(c => c.id !== cat.id);
        products   = products.filter(p => p.categoryId !== cat.id);
        persistMenuSnapshots();
        renderMenu();
        delCatModal.querySelector('[data-hq-modal-close]')?.click();
        editCatModal?.querySelector('[data-hq-modal-close]')?.click();
        BrandAPI.toast('分類已刪除');
      } catch (e) { BrandAPI.toast('刪除失敗：' + e.message, 'error'); }
      finally { f.disabled = false; f.textContent = '確定刪除'; pendingEditCatId = null; }
    });
  }
})();
