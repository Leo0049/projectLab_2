/**
 * 管理員後台：某品牌底下的分店（停用、恢復、分頁、登出）。
 *
 * 品牌 id 取自網址的 ?brandId=，只接受正整數；缺少或不合法時不呼叫 API，只提示回到「已核准品牌」。
 * 清單以 innerHTML 拼接，插入的每個值都先過 escapeHtml；動作以 data-* 與事件代理傳入。
 * 品牌停用時，分店即使恢復也無法使用，因此頁面會顯示停用提示。
 */
(() => {
  if (!AdminAPI.requireLogin()) return;

  const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[char]);

  const PAGE_SIZE = 20;
  const TONES = {
    success: ['bg-emerald-50', 'text-emerald-800'],
    warn: ['bg-amber-50', 'text-amber-800'],
    error: ['bg-red-50', 'text-red-700'],
  };
  const BADGES = {
    active: 'rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-semibold text-emerald-700 ring-1 ring-emerald-200',
    suspended: 'rounded-full bg-red-50 px-2 py-0.5 text-xs font-semibold text-red-700 ring-1 ring-red-200',
  };
  const ACTION_CLASSES = {
    suspend: 'inline-flex items-center gap-1 rounded-lg border border-red-300 bg-white px-3.5 py-2 text-sm font-semibold text-red-600 hover:bg-red-50 disabled:opacity-50',
    reinstate: 'inline-flex items-center gap-1 rounded-lg bg-primary px-3.5 py-2 text-sm font-semibold text-white shadow-sm hover:opacity-90 disabled:opacity-50',
  };
  const STATUS_LABELS = { active: '營業中', closed: '休息中' };

  const listEl = document.getElementById('store-list');
  const emptyEl = document.getElementById('store-empty');
  const statusEl = document.getElementById('store-status');
  const countEl = document.getElementById('store-count');
  const titleEl = document.getElementById('page-title');
  const bannerEl = document.getElementById('brand-banner');
  const pagerEl = document.getElementById('pager');
  const pageInfoEl = document.getElementById('page-info');
  const prevBtn = document.getElementById('prev-page');
  const nextBtn = document.getElementById('next-page');
  const refreshBtn = document.getElementById('refresh-btn');
  const logoutBtn = document.getElementById('logout-btn');

  /** 只接受正整數的品牌 id；不合法回 null（不呼叫 API） */
  function parseBrandId(raw) {
    if (!/^[1-9]\d*$/.test(raw || '')) return null;
    const id = Number(raw);
    return Number.isSafeInteger(id) ? id : null;
  }

  const brandId = parseBrandId(new URLSearchParams(window.location.search).get('brandId'));

  // seq／busy 與已核准品牌頁相同：慢的舊回應不蓋掉新結果；動作進行中不接受第二次操作
  const state = { page: 0, total: 0, totalPages: 0, seq: 0, busy: false };

  function setNotice(message, tone = 'success') {
    Object.values(TONES).flat().forEach((cls) => statusEl.classList.remove(cls));
    statusEl.classList.add(...TONES[tone]);
    statusEl.textContent = message;
    statusEl.classList.remove('hidden');
  }

  function clearNotice() {
    statusEl.textContent = '';
    statusEl.classList.add('hidden');
  }

  function formatTime(value) {
    return value ? String(value).replace('T', ' ').slice(0, 16) : '—';
  }

  /** 只查自己定義的對照表（hasOwnProperty），避免 "constructor" 之類的鍵查到原型上的東西 */
  function statusLabel(status) {
    return Object.prototype.hasOwnProperty.call(STATUS_LABELS, status)
      ? STATUS_LABELS[status]
      : String(status || '—');
  }

  function rowHtml(store) {
    const id = escapeHtml(store.storeId);
    const name = escapeHtml(store.storeName);
    const suspended = store.suspended === true;
    const action = suspended ? 'reinstate' : 'suspend';
    const label = suspended ? '恢復' : '停用';
    const badge = suspended
      ? `<span class="${BADGES.suspended}">已停用</span>`
      : `<span class="${BADGES.active}">正常</span>`;
    const since = suspended && store.suspendedAt
      ? `<p class="text-xs text-slate-400">停用時間：${escapeHtml(formatTime(store.suspendedAt))}</p>`
      : '';
    return `
      <li class="flex flex-col gap-3 rounded-xl bg-white p-4 shadow-sm ring-1 ring-slate-200 sm:flex-row sm:items-center sm:justify-between">
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-2">
            <p class="truncate font-semibold">${name}</p>
            ${badge}
          </div>
          <p class="text-sm text-slate-500">分店編號 ${id} · ${escapeHtml(statusLabel(store.status))}</p>
          ${since}
        </div>
        <div class="flex shrink-0 gap-2">
          <button type="button" data-action="${action}" data-store-id="${id}" data-store-name="${name}" class="${ACTION_CLASSES[action]}">${label}</button>
        </div>
      </li>`;
  }

  function setActionsDisabled(disabled) {
    listEl.querySelectorAll('button[data-action]').forEach((btn) => { btn.disabled = disabled; });
  }

  function setLoading(loading) {
    refreshBtn.disabled = loading;
    prevBtn.disabled = loading || state.page <= 0;
    nextBtn.disabled = loading || state.page + 1 >= state.totalPages;
    if (loading) countEl.textContent = '載入中…';
  }

  function renderPager() {
    const pages = state.totalPages;
    pagerEl.classList.toggle('hidden', pages <= 1);
    pageInfoEl.textContent = pages ? `第 ${state.page + 1} / ${pages} 頁` : '';
    prevBtn.disabled = state.page <= 0;
    nextBtn.disabled = state.page + 1 >= pages;
  }

  async function load(page = state.page) {
    const seq = ++state.seq;
    setLoading(true);
    try {
      const data = await AdminAPI.brandStores(brandId, page, PAGE_SIZE);
      if (seq !== state.seq) return;

      const stores = Array.isArray(data.stores) ? data.stores : [];
      // 處理掉原頁最後一筆後，該頁可能已經空了：退回最後一頁
      if (!stores.length && page > 0) {
        const last = Math.max(0, Number(data.totalPages) - 1);
        if (last !== page) return load(last);
      }

      state.page = Number(data.page) || 0;
      state.total = Number(data.total) || 0;
      state.totalPages = Number(data.totalPages) || 0;
      titleEl.textContent = data.brandName || `品牌 #${brandId}`;
      bannerEl.classList.toggle('hidden', data.brandSuspended !== true);
      countEl.textContent = `共 ${state.total} 間分店`;
      listEl.innerHTML = stores.map(rowHtml).join('');
      emptyEl.classList.toggle('hidden', stores.length > 0);
      renderPager();
    } catch (err) {
      if (seq !== state.seq || err.auth) return;
      countEl.textContent = '';
      listEl.innerHTML = '';
      emptyEl.classList.add('hidden');
      setNotice(`載入失敗：${err.message}`, 'error');
    } finally {
      if (seq === state.seq) setLoading(false);
    }
  }

  async function decide(storeId, name, action) {
    if (state.busy) return;
    state.busy = true;
    setActionsDisabled(true);
    clearNotice();
    try {
      if (action === 'suspend') {
        await AdminAPI.suspendStore(storeId);
        setNotice(`已停用分店「${name}」，既有登入與推播連線已失效。`, 'success');
      } else {
        await AdminAPI.reinstateStore(storeId);
        const brandStillSuspended = !bannerEl.classList.contains('hidden');
        if (brandStillSuspended) {
          setNotice(`已恢復分店「${name}」，但所屬品牌仍停用，分店暫時無法使用。`, 'warn');
        } else {
          setNotice(`已恢復分店「${name}」。分店需要重新登入。`, 'success');
        }
      }
      await load(state.page);
    } catch (err) {
      if (err.auth) return;
      if (err.status === 404 || err.status === 409) {
        // 另一位管理員已經處理過：清單是舊的，重新載入
        setNotice(`${err.message}，已重新載入清單。`, 'warn');
        await load(state.page);
      } else {
        setNotice(`操作失敗：${err.message}`, 'error');
      }
    } finally {
      state.busy = false;
      setActionsDisabled(false);
    }
  }

  listEl.addEventListener('click', (event) => {
    const button = event.target.closest('button[data-action]');
    if (!button || !listEl.contains(button)) return;

    const storeId = Number(button.dataset.storeId);
    if (!Number.isSafeInteger(storeId) || storeId <= 0) return;
    const name = button.dataset.storeName || '';

    if (button.dataset.action === 'suspend') {
      const message = `確定要停用分店「${name}」嗎？\n\n停用後該分店會立即無法使用，既有登入與推播連線都會失效，也不再出現在公開列表。`;
      if (window.confirm(message)) decide(storeId, name, 'suspend');
    } else if (button.dataset.action === 'reinstate') {
      decide(storeId, name, 'reinstate');
    }
  });

  prevBtn.addEventListener('click', () => load(state.page - 1));
  nextBtn.addEventListener('click', () => load(state.page + 1));
  refreshBtn.addEventListener('click', () => {
    clearNotice();
    load(state.page);
  });
  logoutBtn.addEventListener('click', () => AdminAPI.logout());

  if (brandId === null) {
    setNotice('缺少品牌編號，請從「已核准品牌」進入。', 'warn');
    countEl.textContent = '';
    refreshBtn.disabled = true;
  } else {
    load(0);
  }
})();
