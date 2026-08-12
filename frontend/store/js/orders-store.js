(() => {
  const STORAGE_KEY = 'btpro.orders.v1';

  function formatHHMM(ms) {
    const d = new Date(ms);
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    return `${hh}:${mm}`;
  }

  const DEFAULT_ORDERS = [
    {
      id: '042',
      status: 'pending',
      channel: 'delivery',
      waitLabel: '03:20',
      placedLabel: '14:25 下單',
      amountLabel: '$195',
      itemsLabel: '經典珍珠奶茶 (L) x2, 錫蘭紅茶 (M) x1',
      customerLabel: '暱稱：王小明 • 手機號碼：0912-345-678',
      addressLabel: '台北市信義區忠孝東路五段 10 號 12 樓',
      noteLabel: '請將餐點放在門口，並按門鈴提醒。',
    },
    {
      id: '041',
      status: 'pending',
      channel: 'pickup',
      waitLabel: '05:45',
      placedLabel: '14:18 下單',
      amountLabel: '$85',
      itemsLabel: '楊枝甘露 (L) x1',
      customerLabel: '暱稱：王小明 • 手機號碼：0912-345-678',
      noteLabel: '少冰、少糖。',
    },
    {
      id: '040',
      status: 'pending',
      channel: 'delivery',
      waitLabel: '08:12',
      placedLabel: '14:10 下單',
      amountLabel: '$210',
      itemsLabel: '茉莉綠茶 (L) x3, 四季春 (M) x2',
      customerLabel: '暱稱：王小明 • 手機號碼：0912-345-678',
      addressLabel: '台北市大安區復興南路一段 99 號 8 樓',
      noteLabel: '請先電話聯繫再送達。',
    },
    {
      id: '039',
      status: 'in-production',
      channel: 'pickup',
      waitLabel: '05:10',
      placedLabel: '預計 5 分鐘',
      amountLabel: '$95',
      itemsLabel: '黑糖珍珠鮮奶 (L) x1',
      customerLabel: '暱稱：王小明 • 手機號碼：0912-345-678',
      noteLabel: '去冰。',
    },
    {
      id: '038',
      status: 'in-production',
      channel: 'delivery',
      waitLabel: '07:25',
      placedLabel: '預計 10 分鐘',
      amountLabel: '$165',
      itemsLabel: '多多綠 (M) x2, 烏龍茶 (M) x1',
      customerLabel: '暱稱：王小明 • 手機號碼：0912-345-678',
      addressLabel: '新北市板橋區文化路二段 18 號 5 樓',
      noteLabel: '門口有管理員，請報到。',
    },
    {
      id: '037',
      status: 'delivering',
      channel: 'delivery',
      waitLabel: '02:30',
      placedLabel: '外送員前往中',
      amountLabel: '$95',
      itemsLabel: '經典珍珠奶茶 (L) x1',
      customerLabel: '暱稱：王小明 • 手機號碼：0912-345-678',
      addressLabel: '台北市中山區南京東路二段 1 號',
      noteLabel: '請勿敲門，放門口即可。',
    },
    {
      id: '036',
      status: 'ready-for-pickup',
      channel: 'pickup',
      waitLabel: '04:05',
      placedLabel: '等待取餐',
      amountLabel: '$120',
      itemsLabel: '茉莉綠茶 (L) x2',
      customerLabel: '暱稱：王小明 • 手機號碼：0912-345-678',
      noteLabel: '分開裝袋。',
    },
  ];

  function safeParse(json) {
    try {
      return JSON.parse(json);
    } catch {
      return null;
    }
  }

  function loadOrders() {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;

    const parsed = safeParse(raw);
    if (!parsed || typeof parsed !== 'object') return null;

    const orders = Array.isArray(parsed.orders) ? parsed.orders : null;
    return orders;
  }

  function saveOrders(orders, meta = {}) {
    const payload = {
      v: 1,
      updatedAt: Date.now(),
      orders,
      ...meta,
    };

    localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));

    window.dispatchEvent(
      new CustomEvent('btpro:orders-changed', {
        detail: { source: meta.source || 'local' },
      })
    );
  }

  function ensureSeeded() {
    const existing = loadOrders();
    if (Array.isArray(existing) && existing.length > 0) return;

    const now = Date.now();
    const seeded = DEFAULT_ORDERS.slice().map((order, index) => {
      const current = order && typeof order === 'object' ? order : {};
      const statusChangedAt = Number.isFinite(Number(current.statusChangedAt))
        ? Number(current.statusChangedAt)
        : now - (index + 1) * 60_000;

      const placedLabel = String(current.placedLabel || '').trim();
      const hasTimePlaced = /^\d{2}:\d{2}\s*下單$/.test(placedLabel);

      return {
        ...current,
        statusChangedAt,
        placedLabel: hasTimePlaced ? placedLabel : `${formatHHMM(statusChangedAt)} 下單`,
      };
    });

    saveOrders(seeded, { source: 'seed' });
  }

  function getOrders() {
    const orders = loadOrders();
    return Array.isArray(orders) ? orders.slice() : [];
  }

  function setOrders(orders) {
    const next = Array.isArray(orders) ? orders.slice() : [];
    saveOrders(next, { source: 'set' });
  }

  function updateOrderStatus(orderId, nextStatus) {
    const id = String(orderId || '').trim();
    if (!id) return;

    const orders = getOrders();
    const idx = orders.findIndex((o) => String(o?.id || '').trim() === id);
    if (idx < 0) return;

    const current = orders[idx] || {};

    const now = Date.now();
    const status = String(nextStatus || '').trim();
    orders[idx] = {
      ...current,
      status,
      statusChangedAt: now,
      placedLabel: `${formatHHMM(now)} 下單`,
    };
    saveOrders(orders, { source: 'update-status', updatedId: id });
  }

  function subscribe(handler) {
    const fn = typeof handler === 'function' ? handler : null;
    if (!fn) return () => {};

    const onChange = (event) => fn(event);
    window.addEventListener('btpro:orders-changed', onChange);
    return () => window.removeEventListener('btpro:orders-changed', onChange);
  }

  window.addEventListener('storage', (event) => {
    if (event.key !== STORAGE_KEY) return;
    window.dispatchEvent(new CustomEvent('btpro:orders-changed', { detail: { source: 'storage' } }));
  });

  window.BTOrders = {
    STORAGE_KEY,
    ensureSeeded,
    getOrders,
    setOrders,
    updateOrderStatus,
    subscribe,
  };
})();
