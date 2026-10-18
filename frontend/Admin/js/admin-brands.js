/**
 * 管理員後台：待審核品牌清單（核准、退件、分頁、登出）。
 *
 * 清單以 innerHTML 拼接，插入的每個值都先過 escapeHtml（屬性值也一樣）。
 * 動作與品牌 id 放在 data-* 上，由清單的事件代理讀取，不把資料拼進 onclick。
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
  const ACTION_CLASSES = {
    approve: 'inline-flex items-center gap-1 rounded-lg bg-primary px-3.5 py-2 text-sm font-semibold text-white shadow-sm hover:opacity-90 disabled:opacity-50',
    reject: 'inline-flex items-center gap-1 rounded-lg border border-red-300 bg-white px-3.5 py-2 text-sm font-semibold text-red-600 hover:bg-red-50 disabled:opacity-50',
  };

  const listEl = document.getElementById('brand-list');
  const emptyEl = document.getElementById('brand-empty');
  const statusEl = document.getElementById('brand-status');
  const countEl = document.getElementById('pending-count');
  const pagerEl = document.getElementById('pager');
  const pageInfoEl = document.getElementById('page-info');
  const prevBtn = document.getElementById('prev-page');
  const nextBtn = document.getElementById('next-page');
  const refreshBtn = document.getElementById('refresh-btn');
  const logoutBtn = document.getElementById('logout-btn');

  // seq：每次載入遞增，回應到達時序號不符就丟棄（避免慢的舊回應蓋掉新結果）
  // busy：核准或退件進行中，不接受第二次操作
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
    return `
      <li class="flex flex-col gap-3 rounded-xl bg-white p-4 shadow-sm ring-1 ring-slate-200 sm:flex-row sm:items-center sm:justify-between">
        <div class="min-w-0">
          <p class="truncate font-semibold">${name}</p>
          <p class="truncate text-sm text-slate-500">帳號：${escapeHtml(brand.account)}</p>
          <p class="text-xs text-slate-400">申請時間：${escapeHtml(formatTime(brand.createdAt))}</p>
        </div>
        <div class="flex shrink-0 gap-2">
          <button type="button" data-action="approve" data-brand-id="${id}" data-brand-name="${name}" class="${ACTION_CLASSES.approve}">核准</button>
          <button type="button" data-action="reject" data-brand-id="${id}" data-brand-name="${name}" class="${ACTION_CLASSES.reject}">退件</button>
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
      const data = await AdminAPI.pendingBrands(page, PAGE_SIZE);
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
      countEl.textContent = `共 ${state.total} 筆待審核`;
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
      if (action === 'approve') {
        await AdminAPI.approveBrand(brandId);
        setNotice(`已核准「${name}」，該品牌現在可以登入。`, 'success');
      } else {
        await AdminAPI.rejectBrand(brandId);
        setNotice(`已退件「${name}」，該品牌無法登入。`, 'success');
      }
      await load(state.page);
    } catch (err) {
      if (err.auth) return;
      if (err.status === 404 || err.status === 409) {
        // 別的管理員已經處理過，或品牌已不存在：清單是舊的，重新載入
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

    if (button.dataset.action === 'approve') {
      decide(brandId, name, 'approve');
    } else if (button.dataset.action === 'reject') {
      if (window.confirm(`確定要退件「${name}」嗎？退件後該品牌無法登入。`)) {
        decide(brandId, name, 'reject');
      }
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
