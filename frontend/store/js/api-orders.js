/**
 * api-orders.js — 訂單管理 API 串接 (強化同步與 ID 修正版)
 */
(() => {
    const API_STATUS_MAP = {
        OPEN: 'pending',
        SUBMITTED: 'pending',
        PREPARING: 'in-production',
        READY: 'ready-for-pickup',
        COMPLETED: 'completed',
        CANCELLED: 'canceled',
        REJECTED: 'canceled',
    };

    const PENDING_COUNTDOWN_MS = 10 * 60 * 1000;

    const fmt = (n) => n == null ? '$0' : `$${Number(n).toLocaleString()}`;

    const groupItems = (items) => {
        const groups = {};
        items.forEach(i => {
            // 建立唯一鍵值：品名 + 規格 + 甜度 + 冰塊
            const key = `${i.productNameSnapshot}-${i.size}-${i.sugar}-${i.ice}`;
            if (!groups[key]) {
                groups[key] = { ...i, qty: i.qty || 1 };
            } else {
                groups[key].qty += (i.qty || 1);
            }
        });
        return Object.values(groups);
    };

    const orderToLocal = (o) => {
        const apiStatus = (o.status || '').toUpperCase();
        const uiStatus = API_STATUS_MAP[apiStatus];
        if (!uiStatus) return null;

        const rawItems = Array.isArray(o.items) ? o.items : [];
        const grouped = groupItems(rawItems);
        
        // 商家好讀格式：[數量] 品名 (規格/甜度/冰塊)
        const itemsLabel = grouped
            .map(i => {
                const sugarIce = [i.sugar, i.ice].filter(Boolean).join('/');
                const specs = [i.size, sugarIce].filter(Boolean).join(' | ');
                const cleanName = (i.productNameSnapshot || '飲品').replace(/\(L\)|\(M\)/g, '').trim();
                return `【${i.qty}杯】${cleanName} (${specs})`;
            })
            .join(' 、 ');

        const name = o.initiator?.name || '匿名顧客';
        const phone = o.initiator?.phone || '—';
        const createdAtTs = new Date(o.createdAt).getTime() || Date.now();
        const hhmm = (() => {
            const d = new Date(createdAtTs);
            return `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`;
        })();
        const nowTs = Date.now();
        const OFFSET_8H = 8 * 60 * 60 * 1000;
        let deadline = null;
        if (uiStatus === 'pending') {
            deadline = createdAtTs + PENDING_COUNTDOWN_MS;
            // 修正 UTC+8 時區偏差：若 deadline 剛好差 8 小時則補正
            if (deadline < nowTs && Math.abs((nowTs - createdAtTs) - OFFSET_8H) < 60 * 60 * 1000) {
                deadline += OFFSET_8H;
            }
        }

        const orderNo = o.orderNo || String(o.orderId || o.id || '');
        const isDelivery = !!(o.address || (orderNo && orderNo.startsWith('DL-')));

        return {
            id: String(o.orderId || o.id || ''),
            _apiId: o.orderId || o.id,
            orderNo: orderNo,
            status: uiStatus,
            channel: isDelivery ? 'delivery' : 'pickup', 
            amountLabel: fmt(o.totalAmount),
            itemsLabel: itemsLabel || '無飲品內容',
            items: grouped.map(i => ({
                ...i,
                name: (i.productNameSnapshot || '').replace(/\(L\)|\(M\)/g, '').trim()
            })),
            customerLabel: `暱稱：${name} • 手機號碼：${phone}`,
            customerName: name,
            customerPhone: phone,
            addressLabel: o.address || '',
            noteLabel: o.note || '',
            placedLabel: `${hhmm} 下單`,
            deadline: deadline, 
            statusChangedAt: createdAtTs,
        };
    };

    const loadOrders = async () => {
        try {
            const data = await window.StoreAPI.getOrders();
            const raw = Array.isArray(data) ? data : (data?.orders || data?.content || []);
            const localOrders = raw.map(orderToLocal).filter(Boolean);
            if (window.BTOrders?.setOrders) window.BTOrders.setOrders(localOrders);
        } catch (err) { console.warn('[api-orders] 載入失敗:', err.message); }
    };

    const overrideBTOrders = () => {
        if (!window.BTOrders) { setTimeout(overrideBTOrders, 100); return; }
        
        window.BTOrders.updateOrderStatus = async (orderId, nextUiStatus) => {
            const orders = window.BTOrders.getOrders();
            const order = orders.find(o => String(o.id) === String(orderId));
            const apiId = order?._apiId || orderId;

            try {
                if (nextUiStatus === 'in-production') await window.StoreAPI.acceptOrder(apiId);
                else if (nextUiStatus === 'ready-for-pickup') await window.StoreAPI.completeProduction(apiId);
                else if (nextUiStatus === 'completed') await window.StoreAPI.finalizeOrder(apiId);
                else if (nextUiStatus === 'canceled') await window.StoreAPI.rejectOrder(apiId);
                
                window.StoreAPI.showToast('訂單狀態已更新', 'success');
                await loadOrders(); // 這會觸發 BTOrders.setOrders 並發送 btpro:orders-changed
            } catch (err) {
                window.StoreAPI.showToast('操作失敗：' + err.message, 'error');
                await loadOrders();
            }
        };
    };

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => { loadOrders(); overrideBTOrders(); });
    else { loadOrders(); overrideBTOrders(); }
    
    setInterval(loadOrders, 20000);
})();
