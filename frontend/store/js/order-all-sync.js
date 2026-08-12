/**
 * order-all-sync.js — 訂單管理頁面（最終穩定版）
 * - Section 骨架只建立一次，永不重建
 * - Tab 數量用 data-label 鎖定，不會雙括號
 * - 所有欄位用 inline style，保證對齊
 */
(() => {
  if (!document.querySelector('[data-order-tabs]')) return;

  /* ── 常數 ─────────────────────────────────────────── */
  const PENDING_MS = 10 * 60 * 1000;
  const PAGE_SIZE  = 30;
  const SECTIONS = [
    { key: 'pending',           label: '待處理' },
    { key: 'in-production',     label: '製作中' },
    { key: 'delivering',        label: '配送中' },
    { key: 'ready-for-pickup',  label: '待取餐' },
    { key: 'completed',         label: '已完成' },
    { key: 'canceled',          label: '已取消' },
  ];
  const STATUS_LABEL = {
    'pending':           '待處理 (Pending)',
    'in-production':     '製作中 (Preparing)',
    'delivering':        '配送中 (Delivering)',
    'ready-for-pickup':  '待取餐 (Ready)',
    'completed':         '已完成 (Completed)',
    'canceled':          '已取消 (Canceled)',
  };
  const STATUS_COLOR = {
    'pending':           'color:#ca8a04',
    'in-production':     'color:#2563eb',
    'delivering':        'color:#ec5b13',
    'ready-for-pickup':  'color:#ec5b13',
    'completed':         'color:#16a34a',
    'canceled':          'color:#94a3b8',
  };

  /* ── 狀態 ─────────────────────────────────────────── */
  let currentTab = 'all';
  let allOrders  = [];
  let byStatus   = {};
  let lastDataKey = '';

  const pageState = {};
  SECTIONS.forEach(({ key }) => { pageState[key] = 1; });

  /* ── 工具函式 ─────────────────────────────────────── */
  const buildDataKey = (orders) => orders.map(o => `${o.orderId||o.id}:${o.status||o.uiStatus}`).join('|');
  const fmtItem = (i) => {
    const name  = i.productNameSnapshot || '飲品';
    const tps   = Array.isArray(i.toppings) ? i.toppings.map(t => `加${t}`).join(',') : '';
    const size  = i.sizeSnapshot  || i.size  || '';
    const sugar = i.sugarSnapshot || i.sugar || '';
    const ice   = i.iceSnapshot   || i.ice   || '';
    const specs = [size, sugar, ice, tps].filter(Boolean).join('|');
    const specsPart = specs ? ` <span style="color:#64748b;font-weight:500">(${specs})</span>` : '';
    const note  = i.note ? ` <span style="color:#ec5b13;font-weight:800">${i.note}</span>` : '';
    const user  = i.userName ? ` <span style="color:#0f172a;font-weight:700">【${i.userName}】</span>` : '';
    return `<span style="font-weight:600;color:#64748b">【${i.qty||1}杯】${name}</span>${specsPart}${note}${user}`;
  };

  const fmtItems = (items) => {
    if (!items || !items.length) return '無品項';
    const total   = items.reduce((s, i) => s + (i.qty||1), 0);
    const shown   = items.slice(0, 3).map(fmtItem).join('<br>');
    const more    = items.length > 3 ? `<br><span style="color:#94a3b8;font-size:.75rem">…還有 ${items.length - 3} 項，請看訂單詳情</span>` : '';
    return `<span style="color:#64748b;font-weight:700">總${total}杯：</span><br>${shown}${more}`;
  };

  /* ── th / td 共用樣式（行內，保證對齊）─────────────── */
  const TH = 'padding:.625rem 1.25rem;font-size:.75rem;font-weight:700;color:#64748b;white-space:nowrap;background:#f8fafc';
  const TD = 'padding:.875rem 1.25rem;vertical-align:middle;border-bottom:1px solid #f1f5f9';

  // 欄寬集中管理：colgroup 用這組數字，null 表示內容欄自動填滿剩餘空間
  const COL_WIDTHS = ['150px','88px',null,'110px','140px','90px','170px','200px'];

  const COLS = [
    { th: `${TH}`,                    td: `${TD};white-space:nowrap;font-weight:700;color:#0f172a` }, // 單號
    { th: `${TH}`,                    td: `${TD};white-space:nowrap;line-height:1` },                 // 類型
    { th: `${TH}`,                    td: `${TD};font-size:.875rem;color:#334155` },                  // 內容
    { th: `${TH};text-align:center`,  td: `${TD};white-space:nowrap;font-weight:700;color:#334155;text-align:center` }, // 暱稱
    { th: `${TH}`,                    td: `${TD};white-space:nowrap` },                               // 手機
    { th: `${TH};text-align:center`,  td: `${TD};white-space:nowrap;font-weight:700;color:#0f172a;text-align:center` }, // 金額
    { th: `${TH};text-align:center`,  td: `${TD};text-align:center;white-space:nowrap` },             // 狀態
    { th: `${TH};text-align:center`,  td: `${TD};text-align:center;white-space:nowrap` },             // 操作
  ];
  const HEADS = ['單號','類型','內容','暱稱','手機','金額','狀態','操作'];

  /* ── 版面偵測 ────────────────────────────────────────── */
  const isMobile = () => window.innerWidth < 768;

  /* ── 渲染分頁控制列 ─────────────────────────────────── */
  const renderPagination = (key, totalItems) => {
    const pagEl = document.querySelector(`[data-pagination="${key}"]`);
    if (!pagEl) return;

    const totalPages = Math.max(1, Math.ceil(totalItems / PAGE_SIZE));
    const page = pageState[key] || 1;

    if (totalPages <= 1) {
      pagEl.style.display = 'none';
      pagEl.innerHTML = '';
      return;
    }
    pagEl.style.display = 'flex';

    // 最多顯示 5 個頁碼按鈕
    const maxBtns = 5;
    let start = Math.max(1, page - Math.floor(maxBtns / 2));
    let end   = Math.min(totalPages, start + maxBtns - 1);
    if (end - start + 1 < maxBtns) start = Math.max(1, end - maxBtns + 1);

    const BTN_BASE  = 'min-width:2rem;height:2rem;border-radius:.375rem;font-size:.8rem;font-weight:700;cursor:pointer;padding:0 .4rem;';
    const BTN_ACT   = `${BTN_BASE}background:#ec5b13;color:#fff;border:none`;
    const BTN_NORM  = `${BTN_BASE}background:#fff;color:#64748b;border:1px solid #e2e8f0`;
    const BTN_NAV   = `padding:.35rem .65rem;border-radius:.375rem;font-size:.8rem;font-weight:700;cursor:pointer;background:#fff;border:1px solid #e2e8f0;color:#64748b`;
    const BTN_DIS   = 'opacity:.35;cursor:default;pointer-events:none';

    let pageBtns = '';
    for (let i = start; i <= end; i++) {
      pageBtns += `<button data-page-action="goto" data-page-section="${key}" data-page-num="${i}" style="${i === page ? BTN_ACT : BTN_NORM}">${i}</button>`;
    }

    pagEl.innerHTML = `
      <button data-page-action="prev" data-page-section="${key}" style="${BTN_NAV}${page <= 1 ? ';' + BTN_DIS : ''}">上頁</button>
      ${pageBtns}
      <button data-page-action="next" data-page-section="${key}" style="${BTN_NAV}${page >= totalPages ? ';' + BTN_DIS : ''}">下頁</button>
      <span style="font-size:.78rem;color:#94a3b8;font-weight:500;margin-left:.25rem">共 ${totalPages} 頁</span>
      <div style="display:flex;align-items:center;gap:.25rem;margin-left:.5rem">
        <input type="number" min="1" max="${totalPages}" placeholder="頁碼"
          data-page-jump="${key}"
          style="width:3.5rem;height:2rem;border-radius:.375rem;border:1px solid #e2e8f0;padding:0 .4rem;font-size:.8rem;text-align:center;outline:none">
        <button data-page-action="jump" data-page-section="${key}"
          style="${BTN_NAV}">跳轉</button>
      </div>`;
  };

  /* ── 渲染行動版卡片 ──────────────────────────────────── */
  const STATUS_PILL_STYLE = {
    'pending':          'background:#fef9c3;color:#ca8a04',
    'in-production':    'background:#dbeafe;color:#2563eb',
    'delivering':       'background:#fff7ed;color:#ec5b13',
    'ready-for-pickup': 'background:#fff7ed;color:#ec5b13',
    'completed':        'background:#dcfce7;color:#16a34a',
    'canceled':         'background:#f1f5f9;color:#94a3b8',
  };

  // 行動版卡片用短標籤（不含英文括號，節省空間）
  const STATUS_LABEL_SHORT = {
    'pending':           '待處理',
    'in-production':     '製作中',
    'delivering':        '配送中',
    'ready-for-pickup':  '待取餐',
    'completed':         '已完成',
    'canceled':          '已取消',
  };

  const renderCard = (o) => {
    const isDelivery = o.channel === 'delivery';
    const badge = isDelivery
      ? `<span style="display:inline-flex;align-items:center;padding:.15rem .45rem;border-radius:.375rem;font-size:.68rem;font-weight:700;background:rgba(236,91,19,.1);color:#ec5b13;border:1px solid rgba(236,91,19,.2);white-space:nowrap;flex-shrink:0">外送</span>`
      : `<span style="display:inline-flex;align-items:center;padding:.15rem .45rem;border-radius:.375rem;font-size:.68rem;font-weight:700;background:#eff6ff;color:#1d4ed8;border:1px solid #bfdbfe;white-space:nowrap;flex-shrink:0">自取</span>`;

    const statusPill = `<span style="padding:.15rem .45rem;border-radius:9999px;font-size:.68rem;font-weight:700;${STATUS_PILL_STYLE[o.uiStatus]||''};white-space:nowrap;flex-shrink:0">${STATUS_LABEL_SHORT[o.uiStatus]||o.uiStatus}</span>`;

    const totalQty = (o.items||[]).reduce((s, i) => s + (i.qty||1), 0);
    const firstName = o.items?.[0]?.productNameSnapshot || '飲品';
    const moreCount = (o.items||[]).length > 1 ? ` 等${(o.items||[]).length}項` : '';
    const itemsSummary = `總${totalQty}杯｜${firstName}${moreCount}`;

    const viewBtn = `<button style="padding:.3rem .65rem;border-radius:.5rem;background:#fff;border:1px solid #e2e8f0;font-size:.72rem;font-weight:700;cursor:pointer;white-space:nowrap" data-order-action="view">詳情</button>`;
    let actionBtns = '';
    if (o.uiStatus === 'pending') {
      actionBtns = `
        <button style="padding:.3rem .65rem;border-radius:.5rem;background:#fef2f2;color:#dc2626;border:1px solid #fecaca;font-size:.72rem;font-weight:700;cursor:pointer;white-space:nowrap" data-order-action="reject">拒絕</button>
        <button style="padding:.3rem .65rem;border-radius:.5rem;background:#ec5b13;color:#fff;border:none;font-size:.72rem;font-weight:700;cursor:pointer;white-space:nowrap" data-order-action="accept">接單</button>`;
    } else if (!['completed','canceled'].includes(o.uiStatus)) {
      actionBtns = `<button style="padding:.3rem .65rem;border-radius:.5rem;background:#ec5b13;color:#fff;border:none;font-size:.72rem;font-weight:700;cursor:pointer;white-space:nowrap" data-order-action="advance">下一步</button>`;
    }

    const countdown = o.uiStatus === 'pending'
      ? `<span style="font-size:.7rem;font-weight:900;color:#ef4444" data-countdown-deadline="${o.deadlineTs}">--:--</span>`
      : '';

    return `<div data-order-id="${o.id}" style="background:#fff;border-radius:.75rem;border:1px solid #f1f5f9;padding:.75rem;margin-bottom:.5rem;box-shadow:0 1px 3px rgba(0,0,0,.06)">
      <div style="display:flex;align-items:center;gap:.4rem;margin-bottom:.4rem">
        <span style="flex:1;font-weight:700;color:#0f172a;font-size:.85rem;overflow:hidden;white-space:nowrap;text-overflow:ellipsis">#${o.orderNo||o.id}</span>
        ${badge}
        ${statusPill}
      </div>
      <div style="font-size:.78rem;color:#64748b;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;margin-bottom:.5rem">${itemsSummary}</div>
      <div style="display:flex;align-items:center;justify-content:space-between;gap:.4rem">
        <div style="display:flex;align-items:center;gap:.5rem">
          <span style="font-size:.9rem;font-weight:900;color:#ec5b13">${o.amountLabel}</span>
          ${countdown}
        </div>
        <div style="display:inline-flex;gap:.3rem;align-items:center;flex-shrink:0">${viewBtn}${actionBtns}</div>
      </div>
    </div>`;
  };

  /* ── 建立 Section 骨架（只執行一次）─────────────────── */
  const buildSections = () => {
    const container = document.getElementById('mainContentFade');
    if (!container) return;
    container.querySelectorAll('section[data-order-section]').forEach(s => s.remove());

    if (isMobile()) {
      SECTIONS.forEach(({ key, label }) => {
        container.insertAdjacentHTML('beforeend', `
          <section data-order-section="${key}" style="margin-bottom:1rem">
            <h3 style="font-size:.85rem;font-weight:700;color:#475569;padding:.4rem 0;margin-bottom:.4rem">
              ${label} <span style="color:#94a3b8;font-weight:400" data-section-count>(0)</span>
            </h3>
            <div data-order-card-container></div>
            <div data-pagination="${key}" style="display:none;padding:.5rem 0;align-items:center;gap:.3rem;flex-wrap:wrap;justify-content:center"></div>
          </section>`);
      });
    } else {
      const thsHtml = HEADS.map((h, i) => `<th style="${COLS[i].th}">${h}</th>`).join('');
      const colgroupHtml = COL_WIDTHS.map(w => w ? `<col style="width:${w}">` : `<col>`).join('');

      SECTIONS.forEach(({ key, label }) => {
        container.insertAdjacentHTML('beforeend', `
          <section data-order-section="${key}" style="margin-bottom:1.5rem">
            <h3 style="font-size:.9rem;font-weight:700;color:#475569;padding:.5rem 0;margin-bottom:.5rem">
              ${label} <span style="color:#94a3b8;font-weight:400" data-section-count>(0)</span>
            </h3>
            <div style="background:#fff;border-radius:.75rem;box-shadow:0 1px 3px rgba(0,0,0,.06);border:1px solid #f1f5f9;overflow:hidden">
              <div style="overflow-x:auto;-webkit-overflow-scrolling:touch;display:block;width:100%">
                <table style="width:100%;table-layout:fixed;border-collapse:collapse;min-width:1100px;border-spacing:0">
                  <colgroup>${colgroupHtml}</colgroup>
                  <thead><tr style="background:#f8fafc">${thsHtml}</tr></thead>
                  <tbody data-order-table-body style="vertical-align:middle">
                    <tr><td colspan="8" style="padding:.875rem 1.25rem;text-align:center;color:#94a3b8;vertical-align:middle;border-bottom:1px solid #f1f5f9">載入中...</td></tr>
                  </tbody>
                </table>
              </div>
              <div data-pagination="${key}" style="display:none;padding:.75rem 1.25rem;border-top:1px solid #f1f5f9;align-items:center;gap:.4rem;flex-wrap:wrap;justify-content:center"></div>
            </div>
          </section>`);
      });
    }
  };

  /* ── 渲染單行 ─────────────────────────────────────── */
  const renderRow = (o) => {
    const isDelivery = o.channel === 'delivery';
    const badge = isDelivery
        ? `<span style="display:inline-flex;align-items:center;padding:.2rem .6rem;border-radius:.375rem;font-size:.75rem;font-weight:700;background:rgba(236,91,19,.1);color:#ec5b13;border:1px solid rgba(236,91,19,.2);line-height:1.4;vertical-align:middle">外送</span>`
        : `<span style="display:inline-flex;align-items:center;padding:.2rem .6rem;border-radius:.375rem;font-size:.75rem;font-weight:700;background:#eff6ff;color:#1d4ed8;border:1px solid #bfdbfe;line-height:1.4;vertical-align:middle">自取</span>`;

    // 詳情按鈕：所有狀態都要有
    const viewBtn = `<button style="padding:.35rem .75rem;border-radius:.5rem;background:#fff;border:1px solid #e2e8f0;font-size:.75rem;font-weight:700;cursor:pointer" data-order-action="view">詳情</button>`;

    let actionBtns = '';
    if (o.uiStatus === 'pending') {
      actionBtns = `
        <button style="padding:.35rem .75rem;border-radius:.5rem;background:#fef2f2;color:#dc2626;border:1px solid #fecaca;font-size:.75rem;font-weight:700;cursor:pointer" data-order-action="reject">拒絕</button>
        <button style="padding:.35rem .75rem;border-radius:.5rem;background:#ec5b13;color:#fff;border:none;font-size:.75rem;font-weight:700;cursor:pointer" data-order-action="accept">接單</button>`;
    } else if (!['completed','canceled'].includes(o.uiStatus)) {
      actionBtns = `<button style="padding:.35rem .75rem;border-radius:.5rem;background:#ec5b13;color:#fff;border:none;font-size:.75rem;font-weight:700;cursor:pointer" data-order-action="advance">下一步</button>`;
    }

    const statusHtml = `<span style="font-size:.8rem;font-weight:700;${STATUS_COLOR[o.uiStatus]||''}">${STATUS_LABEL[o.uiStatus]||o.uiStatus}</span>`
        + (o.uiStatus === 'pending' ? `<br><span style="font-size:.75rem;font-weight:900;color:#ef4444" data-countdown-deadline="${o.deadlineTs}">--:--</span>` : '');

    const tds = [
      `#${o.orderNo||o.id}`,
      badge,
      o.formattedItems,
      o.customerName,
      o.customerPhone,
      o.amountLabel,
      statusHtml,
      `<div style="display:inline-flex;gap:.3rem;align-items:center">${viewBtn}${actionBtns}</div>`,
    ].map((v, i) => `<td style="${COLS[i].td}">${v}</td>`).join('');

    return `<tr data-order-id="${o.id}" style="transition:background .15s" onmouseover="this.style.background='#f8fafc'" onmouseout="this.style.background=''">${tds}</tr>`;
  };

  /* ── 鎖定 Tab 原始文字 ────────────────────────────── */
  const lockTabLabels = () => {
    document.querySelectorAll('[data-order-filter]').forEach(btn => {
      if (!btn.dataset.label) {
        // 移除括號部分，只保留文字
        btn.dataset.label = btn.textContent.replace(/\s*[\(（].*?[\)）]\s*$/, '').trim();
      }
    });
  };

  /* ── 更新 Tab 數量 ─────────────────────────────────── */
  const updateTabCounts = () => {
    document.querySelectorAll('[data-order-filter]').forEach(btn => {
      const key   = btn.dataset.orderFilter;
      const label = btn.dataset.label || key;
      const count = key === 'all' ? allOrders.length : (byStatus[key]?.length || 0);
      btn.textContent = label + ' (' + count + ')';
    });
  };

  /* ── 更新 Section（只改 tbody / card container）────────── */
  const updateSections = () => {
    const mobile = isMobile();
    SECTIONS.forEach(({ key }) => {
      const section = document.querySelector(`section[data-order-section="${key}"]`);
      if (!section) return;

      const countEl = section.querySelector('[data-section-count]');
      const list    = byStatus[key] || [];

      // 分頁切片
      const totalPages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
      if (!pageState[key] || pageState[key] > totalPages) pageState[key] = 1;
      const page      = pageState[key];
      const pageItems = list.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

      if (mobile) {
        const cardContainer = section.querySelector('[data-order-card-container]');
        if (cardContainer) cardContainer.innerHTML = list.length
            ? pageItems.map(renderCard).join('')
            : `<div style="padding:.75rem;text-align:center;color:#94a3b8;font-size:.82rem">— 無訂單 —</div>`;
      } else {
        const body = section.querySelector('[data-order-table-body]');
        if (body) body.innerHTML = list.length
            ? pageItems.map(renderRow).join('')
            : `<tr><td colspan="8" style="${TD};text-align:center;color:#94a3b8">— 無訂單 —</td></tr>`;
      }

      if (countEl) countEl.textContent = `(${list.length})`;

      // 分頁控制列
      renderPagination(key, list.length);

      // Tab 篩選：用 display，不用 class
      section.style.display = (currentTab === 'all' || key === currentTab) ? '' : 'none';
    });
  };

  /* ── 拉資料 ───────────────────────────────────────── */
  const fetchOrders = async () => {
    try {
      const raw = await window.StoreAPI.getOrders();
      if (!Array.isArray(raw)) return;

      const newDataKey = buildDataKey(raw);
      if (newDataKey === lastDataKey) return; // ★ 資料沒變，不重繪 DOM
      lastDataKey = newDataKey;

      allOrders = raw.map(o => {
        const isDelivery = !!o.address;
        const s = (o.status || '').toUpperCase();
        let uiStatus;
        if      (s === 'OPEN')      uiStatus = 'pending';
        else if (s === 'SUBMITTED') uiStatus = 'pending';
        else if (s === 'PREPARING') uiStatus = 'in-production';
        else if (s === 'READY')     uiStatus = isDelivery ? 'delivering' : 'ready-for-pickup';
        else if (s === 'COMPLETED') uiStatus = 'completed';
        else                        uiStatus = 'canceled';

        // 後端回傳 LocalDateTime 無時區，加 '+08:00' 表示台灣時間，讓瀏覽器正確換算 UTC epoch
        // DB 存台灣時間（Asia/Taipei），+08:00 取得正確 UTC epoch（用於 deadline 比較）
        // display 時 +8h 用 getUTC*() 格式化，與機器時區無關
        const rawCreated = o.createdAt ? String(o.createdAt).replace('Z','') + '+08:00' : null;
        const createdTs  = rawCreated ? new Date(rawCreated).getTime() : Date.now();
        const pad = n => String(n).padStart(2,'0');
        const dd = new Date(createdTs + 8 * 3600 * 1000);
        const placedLabel = `${dd.getUTCFullYear()}/${pad(dd.getUTCMonth()+1)}/${pad(dd.getUTCDate())} ${pad(dd.getUTCHours())}:${pad(dd.getUTCMinutes())}`;
        return {
          ...o,
          id: String(o.orderId || o.id),
          uiStatus,
          channel: isDelivery ? 'delivery' : 'pickup',
          formattedItems: fmtItems(o.items),
          customerName:  o.initiator?.name  || '匿名',
          customerPhone: o.initiator?.phone || '—',
          amountLabel:   `$${Number(o.totalAmount||0).toLocaleString()}`,
          deadlineTs:    createdTs + PENDING_MS,
          placedLabel,
        };
      });

      byStatus = { pending:[], 'in-production':[], delivering:[], 'ready-for-pickup':[], completed:[], canceled:[] };
      allOrders.forEach(o => { if (byStatus[o.uiStatus]) byStatus[o.uiStatus].push(o); });

      updateTabCounts();
      updateSections();

      const main = document.getElementById('mainContentFade');
      if (main) main.classList.remove('opacity-0');
    } catch (err) { console.error('[order-all-sync]', err); }
  };

  /* ── 倒數計時 & 逾時自動取消 ──────────────────────── */
  const autoExpiredIds = new Set(); // 已送出取消請求的訂單，避免重複打 API

  setInterval(async () => {
    document.querySelectorAll('[data-countdown-deadline]').forEach(async (el) => {
      const diff = parseInt(el.dataset.countdownDeadline) - Date.now();
      const row  = el.closest('[data-order-id]');
      const orderId = row?.dataset.orderId;

      if (diff <= 0) {
        el.textContent = '逾時';
        el.style.color = '#dc2626';

        // 隱藏接單/拒單按鈕
        if (row) {
          row.querySelectorAll('[data-order-action="accept"],[data-order-action="reject"]')
              .forEach(btn => { btn.disabled = true; btn.style.opacity = '0.3'; btn.style.pointerEvents = 'none'; });
        }

        // 自動取消（只送一次）
        if (orderId && !autoExpiredIds.has(orderId)) {
          autoExpiredIds.add(orderId);
          try {
            await window.StoreAPI.rejectOrder(orderId);
            await fetchOrders(); // 重新拉資料，讓訂單移到已取消
          } catch (err) {
            console.warn('[auto-expire] 取消失敗:', orderId, err.message);
          }
        }
      } else {
        const m = Math.floor(diff / 60000);
        const s = Math.floor((diff % 60000) / 1000);
        el.textContent = `${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
      }
    });
  }, 1000);

  /* ── 點擊事件 ─────────────────────────────────────── */
  document.addEventListener('click', async (e) => {
    // 分頁動作
    const pagBtn = e.target.closest('[data-page-action]');
    if (pagBtn) {
      const action = pagBtn.dataset.pageAction;
      const secKey = pagBtn.dataset.pageSection;
      if (!secKey) return;
      const list       = byStatus[secKey] || [];
      const totalPages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
      if (action === 'prev') {
        if (pageState[secKey] > 1) pageState[secKey]--;
      } else if (action === 'next') {
        if (pageState[secKey] < totalPages) pageState[secKey]++;
      } else if (action === 'goto') {
        const num = parseInt(pagBtn.dataset.pageNum, 10);
        if (num >= 1 && num <= totalPages) pageState[secKey] = num;
      } else if (action === 'jump') {
        const input = document.querySelector(`[data-page-jump="${secKey}"]`);
        const num   = input ? parseInt(input.value, 10) : NaN;
        if (!isNaN(num) && num >= 1 && num <= totalPages) {
          pageState[secKey] = num;
          if (input) input.value = '';
        }
      }
      updateSections();
      return;
    }

    const btn = e.target.closest('[data-order-action],[data-order-detail-close],[data-order-detail-backdrop]');
    if (!btn) return;

    if (btn.hasAttribute('data-order-detail-close') || btn.hasAttribute('data-order-detail-backdrop')) {
      document.querySelector('[data-order-detail-modal]')?.classList.add('hidden');
      return;
    }

    const action  = btn.dataset.orderAction;
    const orderId = btn.closest('[data-order-id]')?.dataset.orderId
        || document.querySelector('[data-order-detail-modal]')?.dataset.currentOrderId;
    if (!orderId) return;

    if (action === 'view') {
      const order = allOrders.find(o => o.id == orderId);
      if (!order) return;
      const root = document.querySelector('[data-order-detail-modal]');
      if (!root) return;
      root.dataset.currentOrderId = orderId;
      root.querySelector('[data-order-detail-title]').textContent     = `訂單詳情 #${order.orderNo||order.id}`;
      root.querySelector('[data-order-detail-status]').textContent    = STATUS_LABEL[order.uiStatus] || order.uiStatus;
      root.querySelector('[data-order-detail-placed]').textContent    = order.placedLabel || '';
      root.querySelector('[data-order-detail-customer]').textContent  = `${order.customerName} (${order.customerPhone})`;
      root.querySelector('[data-order-detail-note]').textContent      = order.note || '無備註';
      root.querySelector('[data-order-detail-address]').textContent   = order.address || '自取訂單';
      root.querySelector('[data-order-detail-channel]').textContent   = order.address ? '外送 Delivery' : '自取 Pickup';
      root.querySelector('[data-order-detail-items]').innerHTML = (order.items||[]).map(i =>
          `<div style="padding:1rem;border-radius:.75rem;background:#f8fafc;display:flex;justify-content:space-between;align-items:flex-start;gap:1rem;margin-bottom:.5rem">
          <div style="font-weight:700;color:#1e293b;flex:1">${fmtItem(i)}</div>
          <div style="font-weight:900;color:#0f172a;flex-shrink:0">$${Number(i.price||i.unitPriceSnapshot||0)*(i.qty||1)}</div>
        </div>`).join('');
      
      // 更新金額顯示
      const subtotal = order.subtotal || 0;
      const discount = order.totalDiscount || 0;
      const total = order.totalAmount || 0;

      root.querySelector('[data-order-detail-subtotal]').textContent = `$${Number(subtotal).toLocaleString()}`;
      root.querySelector('[data-order-detail-discount]').textContent = `-$${Number(discount).toLocaleString()}`;
      root.querySelector('[data-order-detail-total]').textContent = `$${Number(total).toLocaleString()}`;

      // 只有製作中/配送中/待取餐才顯示「更新狀態」按鈕
      const primaryBtn = root.querySelector('[data-order-detail-primary]');
      if (primaryBtn) {
        const canAdvance = ['in-production','delivering','ready-for-pickup'].includes(order.uiStatus);
        primaryBtn.classList.toggle('hidden', !canAdvance);
      }

      root.classList.remove('hidden');
      return;
    }

    try {
      if (action === 'accept') {
        await window.StoreAPI.acceptOrder(orderId);
        window.StoreAPI.showToast('已接單！', 'success');
      } else if (action === 'advance') {
        const order = allOrders.find(o => o.id == orderId);
        if (order?.uiStatus === 'in-production') await window.StoreAPI.completeProduction(orderId);
        else await window.StoreAPI.finalizeOrder(orderId);
        window.StoreAPI.showToast('狀態已更新！', 'success');
      } else if (action === 'reject') {
        const ok = await window.StoreAPI.showConfirm({ title: '確定要拒絕此訂單嗎？', body: '訂單將移至已取消，此操作無法復原。', confirmText: '確定拒單' });
        if (!ok) return;
        await window.StoreAPI.rejectOrder(orderId);
        window.StoreAPI.showToast('已拒單，訂單移至已取消', 'error');
      }
      document.querySelector('[data-order-detail-modal]')?.classList.add('hidden');
      lastDataKey = ''; // ★ 強制清空快照，確保 fetchOrders 必定重繪
      await fetchOrders();
    } catch (err) { window.StoreAPI.showToast(err.message, 'error'); }
  });

  /* ── Tab 切換 ─────────────────────────────────────── */
  document.querySelectorAll('[data-order-filter]').forEach(tab => {
    tab.addEventListener('click', () => {
      currentTab = tab.dataset.orderFilter;
      // 切換 Tab 時重置所有分頁回第 1 頁
      SECTIONS.forEach(({ key }) => { pageState[key] = 1; });
      document.querySelectorAll('[data-order-filter]').forEach(t => {
        const active = t === tab;
        t.className = active
            ? 'px-4 py-1.5 rounded-lg text-sm font-bold bg-primary/10 text-primary whitespace-nowrap'
            : 'px-4 py-1.5 rounded-lg text-sm font-medium text-slate-500 hover:bg-slate-50 whitespace-nowrap';
      });
      updateSections();
    });
  });

  /* ── 初始化 ─────────────────────────────────────── */
  buildSections();
  lockTabLabels();    // 先鎖定 label（此時 HTML 上顯示的就是原始文字）
  fetchOrders();      // 立刻拉資料，API 回來前 tab 保持原始文字
  setInterval(fetchOrders, 15000);

  // 視窗跨越 768px 時重建版面（e.g. 開發者工具切換裝置）
  let _lastMobile = isMobile();
  window.addEventListener('resize', () => {
    const nowMobile = isMobile();
    if (nowMobile !== _lastMobile) {
      _lastMobile = nowMobile;
      lastDataKey = '';
      buildSections();
      updateSections();
    }
  });
})();