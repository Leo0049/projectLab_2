/**
 * home-order-modals.js — 首頁訂單詳情彈窗（使用 api-home.js 的即時資料）
 */
(() => {
  const detailRoot = document.querySelector('[data-order-detail-modal]');
  if (!detailRoot) return;

  const STATUS_LABEL = {
    'pending':           '待處理 (Pending)',
    'in-production':     '製作中 (Preparing)',
    'delivering':        '配送中 (Delivering)',
    'ready-for-pickup':  '待取餐 (Ready)',
    'completed':         '已完成 (Completed)',
    'canceled':          '已取消 (Canceled)',
  };

  const fmtItem = (i) => {
    const name  = i.productNameSnapshot || i.name || '飲品';
    const tps   = Array.isArray(i.toppings) ? i.toppings.map(t => `加${t}`).join(',') : '';
    const specs = [i.sizeSnapshot, i.sugarSnapshot, i.iceSnapshot, tps].filter(Boolean).join('|');
    const note  = i.note ? ` <span style="color:#2563eb">${i.note}</span>` : '';
    const user  = i.userName ? ` <span style="color:#2563eb">【${i.userName}】</span>` : '';
    return `【${i.qty||1}杯】${name}${specs ? ` (${specs})` : ''}${note}${user}`;
  };

  const openDetail = (orderId) => {
    // ★ 從 HomeOrders 拿即時 API 資料（不用 BTOrders localStorage）
    const getOrder = () => window.HomeOrders?.getOrderById(orderId);
    const order = getOrder();
    if (!order) { console.warn('[home-modal] 找不到訂單', orderId); return; }

    detailRoot.dataset.currentOrderId = orderId;
    detailRoot.querySelector('[data-order-detail-title]').textContent     = `訂單詳情 #${order.orderNo||order.id}`;
    detailRoot.querySelector('[data-order-detail-status]').textContent    = STATUS_LABEL[order.uiStatus] || order.uiStatus;
    detailRoot.querySelector('[data-order-detail-placed]').textContent    =
      new Date(order.createdAt||Date.now()).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'}) + ' 下單';
    detailRoot.querySelector('[data-order-detail-customer]').textContent  =
      `${order.initiator?.name||'匿名'} (${order.initiator?.phone||'—'})`;
    detailRoot.querySelector('[data-order-detail-note]').textContent      = order.note || '無備註';
    detailRoot.querySelector('[data-order-detail-address]').textContent   = order.address || '自取訂單';
    detailRoot.querySelector('[data-order-detail-channel]').textContent   = order.address ? '外送 Delivery' : '自取 Pickup';

    // 品項列表
    detailRoot.querySelector('[data-order-detail-items]').innerHTML = (order.items||[]).map(i => `
        <div style="padding:1rem;border-radius:.75rem;background:#f8fafc;display:flex;justify-content:space-between;align-items:flex-start;gap:1rem;margin-bottom:.5rem">
          <div style="font-weight:700;color:#1e293b;flex:1">${fmtItem(i)}</div>
          <div style="font-weight:900;color:#0f172a;flex-shrink:0">$${Number(i.price||i.unitPriceSnapshot||0)*(i.qty||1)}</div>
        </div>`).join('') || '<div style="padding:1rem;color:#94a3b8;text-align:center">無品項資料</div>';

    // 更新金額顯示
    const subtotal = order.subtotal || 0;
    const discount = order.totalDiscount || 0;
    const total = order.totalAmount || 0;

    detailRoot.querySelector('[data-order-detail-subtotal]').textContent = `$${Number(subtotal).toLocaleString()}`;
    detailRoot.querySelector('[data-order-detail-discount]').textContent = `-$${Number(discount).toLocaleString()}`;
    detailRoot.querySelector('[data-order-detail-total]').textContent = `$${Number(total).toLocaleString()}`;

    // 更新狀態按鈕
    const primaryBtn   = detailRoot.querySelector('[data-order-detail-primary]');
    const primaryLabel = detailRoot.querySelector('[data-order-detail-primary-label]');
    if (primaryBtn && primaryLabel) {
      const canAdvance = ['in-production','delivering','ready-for-pickup'].includes(order.uiStatus);
      primaryBtn.classList.toggle('hidden', !canAdvance);
      if (canAdvance) primaryLabel.textContent = '更新狀態 Update Status';
    }

    detailRoot.classList.remove('hidden');
  };

  // 關閉彈窗
  const closeDetail = () => detailRoot.classList.add('hidden');
  detailRoot.querySelector('[data-order-detail-backdrop]')?.addEventListener('click', closeDetail);
  detailRoot.querySelectorAll('[data-order-detail-close]').forEach(b => b.addEventListener('click', closeDetail));

  // 更新狀態按鈕
  detailRoot.querySelector('[data-order-detail-primary]')?.addEventListener('click', async () => {
    const orderId = detailRoot.dataset.currentOrderId;
    if (!orderId) return;
    const order = window.HomeOrders?.getOrderById(orderId);
    if (!order) return;

    try {
      if (order.uiStatus === 'in-production') await window.StoreAPI.completeProduction(orderId);
      else if (['delivering','ready-for-pickup'].includes(order.uiStatus)) await window.StoreAPI.finalizeOrder(orderId);
      window.StoreAPI.showToast('狀態已更新！', 'success');
      closeDetail();
    } catch (err) { window.StoreAPI.showToast(err.message, 'error'); }
  });

  // ★ 監聽 api-home.js 發出的 view 事件
  window.addEventListener('btpro:home-order-view', (e) => {
    const id = String(e?.detail?.id || e?.detail?.orderId || '').trim();
    if (id) openDetail(id);
  });
})();
