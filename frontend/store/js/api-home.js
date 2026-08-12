/**
 * api-home.js — 首頁待處理訂單預覽
 * 核心原則：資料沒變就不重繪 DOM，只讓計時器在原地更新
 */
(() => {
    const PENDING_TIMEOUT_MS = 10 * 60 * 1000;
    let allActiveOrders = [];
    let currentTab = 'pending';
    let lastRenderedKey = ''; // ★ 記錄上次渲染的快照，避免無謂重繪

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

    // ─── 拉訂單 ──────────────────────────────────────────────
    const loadOrders = async () => {
        try {
            const raw = await window.StoreAPI.getOrders();
            if (!Array.isArray(raw)) return;

            allActiveOrders = raw
                .filter(o => ['OPEN','SUBMITTED','READY','MAKING'].includes((o.status||'').toUpperCase()))
                .map(o => {
                    const isDelivery = !!o.address;
                    const s = (o.status||'').toUpperCase();
                    let uiStatus;
                    if      (s === 'OPEN')      uiStatus = 'pending';
                    else if (s === 'SUBMITTED') uiStatus = o.submittedAt ? 'in-production' : 'pending';
                    else if (s === 'MAKING')    uiStatus = 'in-production';
                    else                        uiStatus = isDelivery ? 'delivering' : 'ready-for-pickup';

                    // DB 存台灣時間（Asia/Taipei），加 '+08:00' 讓瀏覽器正確換算 UTC epoch
                    const rawCreatedStr = o.createdAt ? String(o.createdAt).replace('Z','') + '+08:00' : null;
                    const createdTs = rawCreatedStr ? new Date(rawCreatedStr).getTime() : Date.now();
                    return {
                        ...o,
                        id: String(o.orderId || o.id),
                        uiStatus,
                        channel: isDelivery ? 'delivery' : 'pickup',
                        amountLabel: `$${Number(o.totalAmount||0).toLocaleString()}`,
                        deadlineTs: createdTs + PENDING_TIMEOUT_MS,
                    };
                });

            updateTabCounts();
            renderListIfChanged(); // ★ 只在有變化時重繪
        } catch (err) { console.error('[Home] 載入訂單失敗', err); }
    };

    // ─── Tab 數量 ─────────────────────────────────────────────
    const updateTabCounts = () => {
        const map = { pending:'待接單', 'in-production':'製作中', ready:'待取餐/配送中' };
        Object.keys(map).forEach(key => {
            const btn = document.querySelector(`[data-order-tab="${key}"]`);
            if (!btn) return;
            const count = allActiveOrders.filter(o =>
                key === 'ready' ? ['delivering','ready-for-pickup'].includes(o.uiStatus) : o.uiStatus === key
            ).length;
            btn.textContent = `${map[key]} (${count})`;
        });
    };

    // ─── 產生目前清單的 key（用來比較是否需要重繪）────────────
    const buildKey = (list) =>
        list.map(o => `${o.id}:${o.uiStatus}`).join(',');

    // ─── 只在清單有實質變化時才重繪 ─────────────────────────
    const renderListIfChanged = () => {
        const filtered = getFiltered();
        const key = buildKey(filtered);
        if (key === lastRenderedKey) return; // 沒變，不動 DOM
        lastRenderedKey = key;
        renderList(filtered);
    };

    const getFiltered = () => allActiveOrders.filter(o => {
        if (currentTab === 'ready') return ['delivering','ready-for-pickup'].includes(o.uiStatus);
        return o.uiStatus === currentTab;
    });

    // ─── 實際重繪 DOM ─────────────────────────────────────────
    const renderList = (filtered) => {
        if (!filtered) filtered = getFiltered();
        const container = document.getElementById('activeOrdersList');
        if (!container) return;

        if (filtered.length === 0) {
            container.innerHTML = '<div class="px-6 py-10 text-center text-slate-400 font-bold text-sm">此狀態目前尚無訂單</div>';
            return;
        }
        container.innerHTML = filtered.map(renderCard).join('');
        // 重繪後立刻把倒數填入，避免出現 --:--
        syncCountdowns();
    };

    // ─── 渲染單張卡片（計時器用 data 屬性，不直接寫時間）────
    const renderCard = (o) => {
        const isPending  = o.uiStatus === 'pending';
        const isInProd   = o.uiStatus === 'in-production';
        const isReady    = ['delivering','ready-for-pickup'].includes(o.uiStatus);
        const isDelivery = o.channel === 'delivery';

        // DB 存台灣時間（Asia/Taipei），加 '+08:00' 讓瀏覽器正確換算 UTC epoch
        const rawCreated = o.createdAt ? String(o.createdAt).replace('Z','') + '+08:00' : null;
        const createdTs  = rawCreated ? new Date(rawCreated).getTime() : Date.now();
        const padH = n => String(n).padStart(2,'0');
        const dd = new Date(createdTs + 8 * 3600 * 1000);
        const timeStr = `${dd.getUTCFullYear()}/${padH(dd.getUTCMonth()+1)}/${padH(dd.getUTCDate())} ${padH(dd.getUTCHours())}:${padH(dd.getUTCMinutes())}`;
        const itemsSummary = fmtItems(o.items);

        // 行動版單行摘要
        const totalQty  = (o.items||[]).reduce((s, i) => s + (i.qty||1), 0);
        const firstName = o.items?.[0]?.productNameSnapshot || '飲品';
        const moreCount = (o.items||[]).length > 1 ? ` 等${(o.items||[]).length}項` : '';
        const mobileSummary = `總${totalQty}杯｜${firstName}${moreCount}`;

        let statusText = isDelivery ? '配送中' : '待取餐';
        if (isPending) statusText = '待接單';
        if (isInProd)  statusText = '製作中';

        const viewBtn = `<button class="px-2 md:px-3 py-1.5 rounded-lg bg-white border border-slate-200 text-[11px] md:text-xs font-bold whitespace-nowrap" data-action="view">詳情</button>`;

        let actionBtn = '';
        if (isPending) {
            actionBtn = `
                <button class="px-2 md:px-3 py-1.5 rounded-lg bg-red-50 text-red-600 border border-red-100 text-[11px] md:text-xs font-bold whitespace-nowrap" data-action="reject">拒絕</button>
                <button class="px-2 md:px-3 py-1.5 rounded-lg bg-primary text-white text-[11px] md:text-xs font-bold shadow-md whitespace-nowrap" data-action="accept">接單</button>`;
        } else if (isInProd) {
            actionBtn = `<button class="px-2 md:px-3 py-1.5 rounded-lg bg-primary text-white text-[11px] md:text-xs font-bold shadow-md whitespace-nowrap" data-action="advance">${isDelivery?'開始配送':'製作完成'}</button>`;
        } else if (isReady) {
            actionBtn = `<button class="px-2 md:px-3 py-1.5 rounded-lg bg-primary text-white text-[11px] md:text-xs font-bold shadow-md whitespace-nowrap" data-action="advance">${isDelivery?'確認送達':'完成取餐'}</button>`;
        }

        // ★ 計時器先用空字串，syncCountdowns() 會立刻填值
        const countdownHtml = isPending
            ? `<span class="text-[10px] md:text-sm font-black text-red-600 leading-none mt-0.5 md:mt-1" data-home-countdown="${o.deadlineTs}"></span>`
            : '';

        const badgeDelivery = `<span class="px-1.5 md:px-2 py-0.5 rounded text-[9px] md:text-[10px] font-bold ${isDelivery?'bg-orange-100 text-orange-600':'bg-blue-50 text-blue-600'} whitespace-nowrap">${isDelivery?'外送':'自取'}</span>`;
        const badgeStatus   = `<span class="text-[9px] md:text-[10px] font-bold text-slate-400 bg-slate-100 px-1.5 md:px-2 py-0.5 rounded whitespace-nowrap">${statusText}</span>`;

        return `
            <div class="p-3 md:p-5 hover:bg-slate-50/50 transition-colors border-b border-slate-50" data-order-id="${o.id}">
                <div class="flex items-center gap-2 md:gap-4">
                    <!-- 訂單號碼方塊 -->
                    <div class="w-10 h-10 md:w-16 md:h-16 rounded-xl bg-orange-50 flex flex-col items-center justify-center border border-orange-100 shrink-0">
                        <span class="text-[7px] md:text-[9px] font-black text-primary leading-tight text-center px-0.5 break-all">#${o.orderNo||o.id}</span>
                        ${countdownHtml}
                    </div>
                    <!-- 訂單資訊 -->
                    <div class="flex-1 min-w-0">
                        <!-- 行動版：單行摘要 + 小徽章 -->
                        <div class="md:hidden">
                            <div class="flex items-center gap-1 mb-0.5">
                                ${badgeDelivery}${badgeStatus}
                            </div>
                            <p class="font-black text-slate-800 text-xs truncate">${mobileSummary}</p>
                            <p class="text-[10px] text-slate-400 font-bold truncate mt-0.5">${o.amountLabel} • ${o.initiator?.name||'匿名'}</p>
                        </div>
                        <!-- 電腦版：完整多行（原本樣式不動）-->
                        <div class="hidden md:block">
                            <div class="flex items-center gap-2 flex-wrap">
                                <p class="font-black text-slate-800">${itemsSummary}</p>
                                ${badgeDelivery}${badgeStatus}
                            </div>
                            <p class="text-xs text-slate-400 mt-1 font-bold">${o.amountLabel} • ${o.initiator?.name||'匿名'} • ${timeStr} 下單</p>
                        </div>
                    </div>
                    <!-- 操作按鈕 -->
                    <div class="flex items-center gap-1 md:gap-2 shrink-0">
                        ${viewBtn}${actionBtn}
                    </div>
                </div>
            </div>`;
    };

    // ─── 計時器：只更新文字，不動其他 DOM ───────────────────
    const syncCountdowns = () => {
        const now = Date.now();
        document.querySelectorAll('[data-home-countdown]').forEach(el => {
            const deadline = parseInt(el.dataset.homeCountdown);
            const diff = deadline - now;
            if (diff <= 0) {
                el.textContent = '逾時';
                el.style.color = '#dc2626';
            } else {
                const m = Math.floor(diff / 60000);
                const s = Math.floor((diff % 60000) / 1000);
                el.textContent = `${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
                el.style.color = diff < 60000 ? '#dc2626' : '#ea580c'; // 最後1分鐘變紅
            }
        });
    };

    // ─── Tab 切換 ─────────────────────────────────────────────
    const setupTabs = () => {
        document.querySelectorAll('[data-order-tab]').forEach(tab => {
            tab.addEventListener('click', () => {
                currentTab = tab.getAttribute('data-order-tab');
                document.querySelectorAll('[data-order-tab]').forEach(t => {
                    const active = t.getAttribute('data-order-tab') === currentTab;
                    t.className = `flex-1 py-3 text-xs font-black transition-all border-b-4 ${active
                        ? 'border-primary bg-orange-50/50 text-primary'
                        : 'border-transparent text-slate-400 hover:bg-slate-50'}`;
                });
                lastRenderedKey = ''; // 強制重繪
                renderList();
            });
        });
    };

    // ─── 點擊操作 ─────────────────────────────────────────────
    document.addEventListener('click', async (e) => {
        const btn = e.target.closest('[data-action]');
        if (!btn || !btn.closest('#activeOrdersList')) return;

        const orderId = btn.closest('[data-order-id]')?.dataset.orderId;
        const action  = btn.dataset.action;
        const order   = allActiveOrders.find(o => o.id == orderId);

        if (action === 'view') {
            window.dispatchEvent(new CustomEvent('btpro:home-order-view', { detail: { id: orderId } }));
            return;
        }

        try {
            if (action === 'accept') {
                await window.StoreAPI.acceptOrder(orderId);
                window.StoreAPI.showToast('已接單！', 'success');
                
                // ★ 樂觀更新：立即在前端標記已接單
                if (order) { order.submittedAt = new Date().toISOString(); order.uiStatus = 'in-production'; }
                currentTab = 'in-production';
            } else if (action === 'reject') {
                const ok = await window.StoreAPI.showConfirm({ title: '確定要拒絕此訂單嗎？', body: '訂單將移至已取消，此操作無法復原。', confirmText: '確定拒單' });
                if (!ok) return;
                await window.StoreAPI.rejectOrder(orderId);
                window.StoreAPI.showToast('已拒單', 'error');
                
                // ★ 樂觀更新：立即從前端移除
                allActiveOrders = allActiveOrders.filter(o => o.id != orderId);
            } else if (action === 'advance') {
                if (order?.uiStatus === 'in-production') {
                    await window.StoreAPI.completeProduction(orderId);
                    window.StoreAPI.showToast('製作完成！', 'success');
                    // ★ 樂觀更新：移至 ready 分頁
                    if (order) { order.status = 'READY'; order.uiStatus = order.channel === 'delivery' ? 'delivering' : 'ready-for-pickup'; }
                    currentTab = 'ready';
                } else {
                    await window.StoreAPI.finalizeOrder(orderId);
                    window.StoreAPI.showToast('訂單已結案！', 'success');
                    // ★ 樂觀更新：從活動列表中移除
                    allActiveOrders = allActiveOrders.filter(o => o.id != orderId);
                }
            }

            // 統一更新 Tab UI 與 強制渲染
            document.querySelectorAll('[data-order-tab]').forEach(t => {
                const active = t.getAttribute('data-order-tab') === currentTab;
                t.className = `flex-1 py-3 text-xs font-black transition-all border-b-4 ${active
                    ? 'border-primary bg-orange-50/50 text-primary'
                    : 'border-transparent text-slate-400 hover:bg-slate-50'}`;
            });
            
            updateTabCounts();
            lastRenderedKey = ''; 
            renderList();
            
            // 最後再背景拉一次真實資料
            await loadOrders();
        } catch (err) { window.StoreAPI.showToast(err.message, 'error'); }
    });

    // ─── 逾時自動拒單 ─────────────────────────────────────────
    let isRejecting = false;
    const rejectedIds = new Set();

    const autoRejectExpired = async () => {
        if (isRejecting) return;
        const now = Date.now();
        const expired = allActiveOrders.filter(o =>
            o.uiStatus === 'pending' && now > o.deadlineTs && !rejectedIds.has(o.id)
        );
        if (expired.length === 0) return;

        isRejecting = true;
        expired.forEach(o => rejectedIds.add(o.id));
        allActiveOrders = allActiveOrders.filter(o => !rejectedIds.has(o.id));
        updateTabCounts();
        lastRenderedKey = '';
        renderList();

        try {
            let count = 0;
            for (const o of expired) {
                try { await window.StoreAPI.rejectOrder(o.id); count++; }
                catch (e) { console.warn('[home auto-reject]', o.id, e.message); }
            }
            if (count > 0) window.StoreAPI.showToast(`${count} 筆待處理訂單已逾時自動取消`, 'error');
            lastRenderedKey = '';
            await loadOrders();
        } finally { isRejecting = false; }
    };

    // ★ 每秒只更新計時器文字，不碰 DOM 結構
    setInterval(() => {
        syncCountdowns();
        autoRejectExpired();
    }, 1000);

    // ─── 暴露給 home-order-modals.js ──────────────────────────
    window.HomeOrders = {
        getOrders: () => allActiveOrders,
        getOrderById: (id) => allActiveOrders.find(o => String(o.id) === String(id)) || null,
    };

    // ─── 初始化 ───────────────────────────────────────────────
    setupTabs();
    loadOrders();
    setInterval(loadOrders, 15000);
})();
