(() => {
  const preview = document.querySelector('[data-home-order-preview]');
  if (!preview) return;

  const PENDING_COUNTDOWN_MS = 10 * 60 * 1000;

  const formatMMSS = (ms) => {
    const safe = Number.isFinite(Number(ms)) ? Number(ms) : 0;
    const seconds = Math.max(0, Math.floor((safe + 999) / 1000));
    const minutes = Math.floor(seconds / 60);
    const sec = seconds % 60;
    return `${String(minutes).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
  };

  const getPendingCountdownLabel = (order, now = Date.now()) => {
    const placedAt = Number(order?.statusChangedAt);
    if (!Number.isFinite(placedAt) || placedAt <= 0) {
      return String(order?.waitLabel || '—');
    }

    const deadline = placedAt + PENDING_COUNTDOWN_MS;
    return formatMMSS(deadline - now);
  };

  const tabsRoot = preview.querySelector('[data-home-order-tabs]');
  const buttons = tabsRoot
    ? Array.from(tabsRoot.querySelectorAll('button[data-home-order-tab]'))
    : [];

  const panels = Array.from(preview.querySelectorAll('[data-home-order-panel]'));
  if (panels.length === 0) return;

  const store = window.BTOrders;
  if (!store) return;

  store.ensureSeeded();

  const TAB_LABELS = {
    pending: '待接單',
    'in-production': '製作中',
    delivering: '待取餐/配送中',
  };

  const getPanelListRoot = (panelEl) => {
    if (!panelEl) return null;
    if (panelEl.classList.contains('divide-y')) return panelEl;

    const children = Array.from(panelEl.children);
    const divideRoot = children.find(
      (el) => el instanceof HTMLElement && el.classList.contains('divide-y')
    );
    return divideRoot || panelEl;
  };

  const channelBadge = (channel) => {
    if (channel === 'delivery') {
      return {
        text: '外送',
        cls: 'bg-orange-100 text-orange-600',
      };
    }

    return {
      text: '自取',
      cls: 'bg-blue-50 text-blue-600',
    };
  };

  const statusTile = (status) => {
    if (status === 'pending') {
      return {
        tileCls: 'bg-red-50 border-red-100',
        idCls: 'text-red-400',
        timeCls: 'text-red-600',
      };
    }

    if (status === 'in-production') {
      return {
        tileCls: 'bg-orange-50 border-orange-100',
        idCls: 'text-orange-400',
        timeCls: 'text-orange-600',
      };
    }

    return {
      tileCls: 'bg-blue-50 border-blue-100',
      idCls: 'text-blue-400',
      timeCls: 'text-blue-600',
    };
  };

  const escapeHtml = (value) => {
    return String(value || '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  };

  const renderOrderRow = (order) => {
    const channel = channelBadge(order.channel);
    const tile = statusTile(order.status);

    const isPending = order.status === 'pending';
    const tileIdSizeCls = isPending ? 'text-[10px] font-bold' : 'text-base font-black';
    const placedAt = Number(order?.statusChangedAt);
    const countdownDeadline = Number.isFinite(placedAt) && placedAt > 0 ? placedAt + PENDING_COUNTDOWN_MS : null;
    const tileTimeLabel = isPending ? getPendingCountdownLabel(order) : '';

    const actions = (() => {
      if (order.status === 'pending') {
        return `
          <button class="px-5 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 text-sm font-bold text-slate-600 dark:text-slate-400 hover:bg-slate-50" data-order-action="reject">拒絕</button>
          <button class="px-5 py-2.5 rounded-lg bg-primary text-white text-sm font-bold shadow-md shadow-primary/20 hover:bg-primary/90" data-order-action="accept">接單</button>
        `;
      }

      return `
        <button class="px-5 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 text-sm font-bold text-slate-600 dark:text-slate-400 hover:bg-slate-50" data-order-action="view">查看</button>
        <button class="px-5 py-2.5 rounded-lg bg-primary text-white text-sm font-bold shadow-md shadow-primary/20 hover:bg-primary/90" data-order-action="advance">更新狀態</button>
      `;
    })();

    return `
      <div class="p-6 flex items-center justify-between hover:bg-slate-50/50 transition-colors" data-order-item data-order-id="${escapeHtml(
        order.id
      )}">
        <div class="flex items-center gap-4">
          <div class="w-16 h-16 rounded-xl ${tile.tileCls} flex flex-col items-center justify-center border">
            <span class="${tileIdSizeCls} ${tile.idCls}">#${escapeHtml(order.id)}</span>
            ${
              isPending
                ? `<span class="text-lg font-black ${tile.timeCls} leading-none mt-1" ${
                    countdownDeadline
                      ? `data-order-countdown data-order-countdown-deadline="${escapeHtml(String(countdownDeadline))}"`
                      : ''
                  }>${escapeHtml(tileTimeLabel)}</span>`
                : ''
            }
          </div>
          <div>
            <p class="font-bold text-slate-800 dark:text-slate-200">${escapeHtml(order.itemsLabel)}</p>
            <div class="flex items-center gap-3 mt-1.5">
              <span class="px-2 py-0.5 rounded ${channel.cls} text-[10px] font-bold">${channel.text}</span>
              <span class="text-sm text-slate-400">金額: ${escapeHtml(
                order.amountLabel
              )} • ${escapeHtml(order.placedLabel)}</span>
            </div>
            <p class="text-xs text-slate-400 mt-1">${escapeHtml(order.customerLabel)}</p>
          </div>
        </div>
        <div class="flex gap-2">${actions}</div>
      </div>
    `;
  };

  let countdownIntervalId = null;
  const syncCountdownTiles = () => {
    const nodes = Array.from(preview.querySelectorAll('[data-order-countdown][data-order-countdown-deadline]'));
    if (nodes.length === 0) return;

    const now = Date.now();
    for (const el of nodes) {
      const deadline = Number(el.getAttribute('data-order-countdown-deadline'));
      if (!Number.isFinite(deadline)) continue;
      el.textContent = formatMMSS(deadline - now);
    }
  };

  const ensureCountdownTicker = () => {
    if (countdownIntervalId) return;
    countdownIntervalId = window.setInterval(syncCountdownTiles, 1000);
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) syncCountdownTiles();
    });
  };

  const splitOrdersForHome = (orders) => {
    const pending = [];
    const inProduction = [];
    const deliveringGroup = [];

    for (const order of orders) {
      if (!order || !order.status) continue;
      if (order.status === 'pending') pending.push(order);
      else if (order.status === 'in-production') inProduction.push(order);
      else if (order.status === 'delivering' || order.status === 'ready-for-pickup') deliveringGroup.push(order);
    }

    return { pending, inProduction, deliveringGroup };
  };

  const setTabCounts = (counts) => {
    for (const button of buttons) {
      const key = (button.getAttribute('data-home-order-tab') || '').trim();
      const label = TAB_LABELS[key] || (button.textContent || '').trim();

      let count = 0;
      if (key === 'pending') count = counts.pending;
      if (key === 'in-production') count = counts.inProduction;
      if (key === 'delivering') count = counts.deliveringGroup;

      button.textContent = `${label} (${count})`;
    }
  };

  const render = () => {
    const orders = store.getOrders();
    const groups = splitOrdersForHome(orders);

    const counts = {
      pending: groups.pending.length,
      inProduction: groups.inProduction.length,
      deliveringGroup: groups.deliveringGroup.length,
    };

    setTabCounts(counts);

    const byPanelKey = {
      pending: groups.pending,
      'in-production': groups.inProduction,
      delivering: groups.deliveringGroup,
    };

    for (const panelEl of panels) {
      const key = (panelEl.getAttribute('data-home-order-panel') || '').trim();
      const listRoot = getPanelListRoot(panelEl);
      if (!listRoot) continue;

      const list = byPanelKey[key] || [];
      listRoot.innerHTML = list.map(renderOrderRow).join('');
    }

    window.dispatchEvent(new CustomEvent('btpro:home-order-preview-updated'));

    ensureCountdownTicker();
    syncCountdownTiles();
  };

  const advanceStatus = (order) => {
    if (!order) return;

    if (order.status === 'pending') {
      store.updateOrderStatus(order.id, 'in-production');
      return;
    }

    if (order.status === 'in-production') {
      const next = order.channel === 'delivery' ? 'delivering' : 'ready-for-pickup';
      store.updateOrderStatus(order.id, next);
      return;
    }

    if (order.status === 'delivering' || order.status === 'ready-for-pickup') {
      store.updateOrderStatus(order.id, 'completed');
    }
  };

  preview.addEventListener('click', (event) => {
    const target = event.target instanceof Element ? event.target.closest('[data-order-action]') : null;
    if (!target) return;

    const action = (target.getAttribute('data-order-action') || '').trim();
    const item = target.closest('[data-order-item][data-order-id]');
    if (!item) return;

    const id = (item.getAttribute('data-order-id') || '').trim();
    if (!id) return;

    const order = store.getOrders().find((o) => String(o?.id || '') === id);
    if (!order) return;

    if (action === 'reject') {
      window.dispatchEvent(new CustomEvent('btpro:home-order-reject', { detail: { id } }));
      return;
    }
    if (action === 'accept') store.updateOrderStatus(id, 'in-production');
    if (action === 'view') {
      window.dispatchEvent(new CustomEvent('btpro:home-order-view', { detail: { id } }));
      return;
    }
    if (action === 'advance') advanceStatus(order);
  });

  window.addEventListener('btpro:orders-changed', render);

  render();
})();
