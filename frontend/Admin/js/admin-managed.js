/**
 * 管理員後台：已核准品牌清單（停用、恢復、查看分店、分頁、登出）。
 *
 * 清單以 innerHTML 拼接，插入的每個值都先過 escapeHtml（屬性值也一樣）。
 * 動作與品牌 id 放在 data-* 上，由清單的事件代理讀取，不把資料拼進 onclick。
 *
 * 停用會連帶停用底下所有分店，因此需要確認；恢復不需要確認，但提示要說明須重新登入。
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
    stores: 'inline-flex items-center gap-1 rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50',
  };

  const listEl = document.getElementById('brand-list');
  const emptyEl = document.getElementById('brand-empty');
  const statusEl = document.getElementById('brand-status');
  const countEl = document.getElementById('brand-count');
  const pagerEl = document.getElementById('pager');
  const pageInfoEl = document.getElementById('page-info');
  const prevBtn = document.getElementById('prev-page');
  const nextBtn = document.getElementById('next-page');
  const refreshBtn = document.getElementById('refresh-btn');
  const logoutBtn = document.getElementById('logout-btn');

  // seq：每次載入遞增，回應到達時序號不符就丟棄（避免慢的舊回應蓋掉新結果）
  // busy：停用或恢復進行中，不接受第二次操作
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

  function rowHtml(brand) {
    const id = escapeHtml(brand.brandId);
    const name = escapeHtml(brand.name);
    const suspended = brand.suspended === true;
    const action = suspended ? 'reinstate' : 'suspend';
    const label = suspended ? '恢復' : '停用';
    const badge = suspended
      ? `<span class="${BADGES.suspended}">已停用</span>`
      : `<span class="${BADGES.active}">營業中</span>`;
    const since = suspended && brand.suspendedAt
      ? `<p class="text-xs text-slate-400">停用時間：${escapeHtml(formatTime(brand.suspendedAt))}</p>`
      : '';
    return `
      <li class="flex flex-col gap-3 rounded-xl bg-white p-4 shadow-sm ring-1 ring-slate-200 sm:flex-row sm:items-center sm:justify-between">
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-2">
            <p class="truncate font-semibold">${name}</p>
            ${badge}
          </div>
          <p class="truncate text-sm text-slate-500">帳號：${escapeHtml(brand.account)}</p>
          ${since}
        </div>
        <div class="flex shrink-0 flex-wrap gap-2">
          <a href="stores.html?brandId=${id}" class="${ACTION_CLASSES.stores}">分店</a>
          <button type="button" data-action="${action}" data-brand-id="${id}" data-brand-name="${name}" class="${ACTION_CLASSES[action]}">${label}</button>
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
      const data = await AdminAPI.approvedBrands(page, PAGE_SIZE);
      if (seq !== state.seq) return;

      const brands = Array.isArray(data.brands) ? data.brands : [];
      // 處理掉原頁最後一筆後，該頁可能已經空了：退回最後一頁
      if (!brands.length && page > 0) {
        const last = Math.max(0, Number(data.totalPages) - 1);
        if (last !== page) return load(last);
      }

      state.page = Number(data.page) || 0;
      state.total = Number(data.total) || 0;
      state.totalPages = Number(data.totalPages) || 0;
      countEl.textContent = `共 ${state.total} 個已核准品牌`;
      listEl.innerHTML = brands.map(rowHtml).join('');
      emptyEl.classList.toggle('hidden', brands.length > 0);
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

  async function decide(brandId, name, action) {
    if (state.busy) return;
    state.busy = true;
    setActionsDisabled(true);
    clearNotice();
    try {
      if (action === 'suspend') {
        const result = await AdminAPI.suspendBrand(brandId);
        const affected = Number(result && result.affectedStores) || 0;
        setNotice(`已停用「${name}」，底下 ${affected} 間分店一併停用，既有登入與推播連線已失效。`, 'success');
      } else {
        await AdminAPI.reinstateBrand(brandId);
        setNotice(`已恢復「${name}」。品牌與分店需要重新登入，停用前的登入不會恢復。`, 'success');
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

    const brandId = Number(button.dataset.brandId);
    if (!Number.isSafeInteger(brandId) || brandId <= 0) return;
    const name = button.dataset.brandName || '';

    if (button.dataset.action === 'suspend') {
      const message = `確定要停用「${name}」嗎？\n\n停用後該品牌與底下所有分店會立即無法使用，既有登入與推播連線都會失效，分店也不再出現在公開列表。`;
      if (window.confirm(message)) decide(brandId, name, 'suspend');
    } else if (button.dataset.action === 'reinstate') {
      decide(brandId, name, 'reinstate');
    }
  });

  prevBtn.addEventListener('click', () => load(state.page - 1));
  nextBtn.addEventListener('click', () => load(state.page + 1));
  refreshBtn.addEventListener('click', () => {
    clearNotice();
    load(state.page);
  });
  logoutBtn.addEventListener('click', () => AdminAPI.logout());

  load(0);
})();
