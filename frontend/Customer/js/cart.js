/**
 * Legacy cart facade with DB sync support.
 * - Logged-in users: persisted in DB (/api/cart) + near-real-time polling sync.
 * - Guests: fallback to localStorage only.
 */
(function () {
    const STORAGE_KEY = 'join_multi_carts';
    const TOKEN_KEYS = ['userToken', 'JOIN_TOKEN'];
    const POLL_VISIBLE_MS = 3000;
    const POLL_HIDDEN_MS = 10000;

    let syncTimer = null;
    let syncInFlight = false;
    let syncBound = false;

    function clampQty(value) {
        return Math.max(1, Math.min(99, Number(value) || 1));
    }

    function toNumber(value, fallback = 0) {
        const n = Number(value);
        return Number.isFinite(n) ? n : fallback;
    }

    function getAuthToken() {
        for (const key of TOKEN_KEYS) {
            const token = String(localStorage.getItem(key) || '').trim();
            if (token) return token;
        }
        return '';
    }

    function isLoggedIn() {
        return Boolean(getAuthToken());
    }

    function emitCartUpdated(carts) {
        window.dispatchEvent(new CustomEvent('cartUpdated', { detail: carts }));
    }

    function safeParseObject(raw) {
        try {
            const parsed = raw ? JSON.parse(raw) : {};
            return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
        } catch (e) {
            console.error('Failed to parse cart data:', e);
            return {};
        }
    }

    function normalizeLegacyItem(item, fallbackStoreId, fallbackStoreName) {
        if (!item || typeof item !== 'object') return null;
        const storeId = toNumber(item.storeId, toNumber(fallbackStoreId, 0));
        if (!storeId) return null;

        const customization = Array.isArray(item.customization)
            ? item.customization.map((x) => String(x || '').trim()).filter(Boolean)
            : [];

        const toppingNames = Array.isArray(item.toppingNames)
            ? item.toppingNames.map((x) => String(x || '').trim()).filter(Boolean)
            : [];

        return {
            _cartItemId: item._cartItemId != null ? toNumber(item._cartItemId, null) : null,
            productId: toNumber(item.productId, 0),
            productName: String(item.productName || ''),
            price: Math.max(0, toNumber(item.price, 0)),
            quantity: clampQty(item.quantity),
            customization,
            imageUrl: String(item.imageUrl || ''),
            storeId,
            storeName: String(item.storeName || fallbackStoreName || ''),
            sizeSnapshot: String(item.sizeSnapshot || ''),
            sugarSnapshot: String(item.sugarSnapshot || ''),
            iceSnapshot: String(item.iceSnapshot || ''),
            toppingNames,
            toppingIds: Array.isArray(item.toppingIds) ? item.toppingIds.map((id) => toNumber(id, 0)).filter((id) => id > 0) : [],
            brandSpecId: toNumber(item.brandSpecId, null),
            categoryId: toNumber(item.categoryId, null),
            couponId: toNumber(item.couponId, null),
            discountAmount: toNumber(item.discountAmount, 0),
        };
    }

    function normalizeCart(cart, storeIdKey) {
        const storeId = toNumber(cart?.storeId, toNumber(storeIdKey, 0));
        if (!storeId) return null;
        const storeName = String(cart?.storeName || '');
        const storeLogoUrl = String(cart?.storeLogoUrl || '');
        const items = Array.isArray(cart?.items)
            ? cart.items.map((it) => normalizeLegacyItem(it, storeId, storeName)).filter(Boolean)
            : [];
        return { storeId, storeName, storeLogoUrl, items };
    }

    function normalizeAllCarts(rawObject) {
        const normalized = {};
        for (const key of Object.keys(rawObject || {})) {
            const cart = normalizeCart(rawObject[key], key);
            if (cart && cart.items.length > 0) {
                normalized[String(cart.storeId)] = cart;
            }
        }
        return normalized;
    }

    function getAllCarts() {
        return normalizeAllCarts(safeParseObject(localStorage.getItem(STORAGE_KEY)));
    }

    function saveAllCarts(carts, options = {}) {
        const { emit = true } = options;
        const normalized = normalizeAllCarts(carts);
        const nextRaw = JSON.stringify(normalized);
        const prevRaw = localStorage.getItem(STORAGE_KEY) || '{}';
        if (prevRaw === nextRaw) return normalized;

        localStorage.setItem(STORAGE_KEY, nextRaw);
        if (emit) emitCartUpdated(normalized);
        return normalized;
    }

    function getCart(storeId) {
        const all = getAllCarts();
        if (!storeId) {
            const keys = Object.keys(all);
            if (keys.length > 0) return all[keys[0]];
            return { items: [], storeId: null, storeName: null, storeLogoUrl: null };
        }
        const key = String(toNumber(storeId, 0));
        return all[key] || { items: [], storeId: toNumber(storeId, 0), storeName: null, storeLogoUrl: null };
    }

    async function requestApi(path, options = {}) {
        const token = getAuthToken();
        if (!token) throw new Error('請先登入');

        const url = (typeof NavAuth !== 'undefined' && typeof NavAuth.getApiUrl === 'function') 
            ? NavAuth.getApiUrl(path) 
            : path;

        const response = await fetch(url, {
            method: options.method || 'GET',
            headers: {
                'Content-Type': 'application/json',
                Authorization: `Bearer ${token}`,
            },
            body: options.body ? JSON.stringify(options.body) : undefined,
        });

        const raw = await response.text();
        let parsed = null;
        try {
            parsed = raw ? JSON.parse(raw) : null;
        } catch (e) {
            parsed = null;
        }

        if (!response.ok) {
            throw new Error(parsed?.msg || parsed?.message || raw || `HTTP ${response.status}`);
        }

        if (parsed && typeof parsed === 'object' && Object.prototype.hasOwnProperty.call(parsed, 'code')) {
            if (String(parsed.code) !== '200') {
                throw new Error(parsed.msg || 'Cart API error');
            }
            return parsed.data ?? null;
        }
        return parsed;
    }

    function detectFromCustomization(item) {
        const customization = Array.isArray(item?.customization) ? item.customization : [];
        const clean = customization.map((c) => String(c || '').trim()).filter(Boolean);

        let size = String(item?.sizeSnapshot || '').trim();
        let sugar = String(item?.sugarSnapshot || '').trim();
        let ice = String(item?.iceSnapshot || '').trim();
        let note = String(item?.note || '').trim();

        for (const rawToken of clean) {
            const token = rawToken.split('(')[0].trim();
            if (!size && (/杯/.test(token) || token === 'M' || token === 'L')) size = token;
            if (!sugar && /糖/.test(token)) sugar = token;
            if (!ice && (/冰/.test(token) || token === '熱' || token === '溫')) ice = token;
        }

        const toppingNames = Array.isArray(item?.toppingNames) && item.toppingNames.length > 0
            ? item.toppingNames.map((x) => String(x || '').trim()).filter(Boolean)
            : clean.filter((rawToken) => {
                const token = rawToken.split('(')[0].trim();
                if (!token) return false;
                if (token === size || token === sugar || token === ice) return false;
                if (token.startsWith('備註:')) return false;
                return true;
            });

        return {
            size: size || 'M',
            sugar,
            ice,
            toppingNames,
        };
    }

    function buildCustomizationList(size, sugar, ice, toppingNames) {
        const list = [];
        if (size) list.push(size);
        if (sugar) list.push(sugar);
        if (ice) list.push(ice);
        (Array.isArray(toppingNames) ? toppingNames : []).forEach((name) => {
            const text = String(name || '').trim();
            if (text) list.push(text);
        });
        return list;
    }

    function toLegacyItemFromApi(apiItem, storeMeta) {
        const qty = clampQty(apiItem?.qty ?? apiItem?.quantity);
        const unitPrice = Math.max(0, toNumber(apiItem?.finalPrice, toNumber(apiItem?.unitPrice, 0)));
        const toppingNames = Array.isArray(apiItem?.toppingNames)
            ? apiItem.toppingNames.map((x) => String(x || '').trim()).filter(Boolean)
            : [];
        const size = String(apiItem?.size || '').trim();
        const sugar = String(apiItem?.sugar || '').trim();
        const ice = String(apiItem?.ice || '').trim();
        const note = String(apiItem?.note || '').trim();

        return normalizeLegacyItem({
            _cartItemId: apiItem?.cartItemId,
            productId: apiItem?.productId,
            productName: apiItem?.productName,
            price: unitPrice,
            quantity: qty,
            customization: buildCustomizationList(size, sugar, ice, toppingNames),
            imageUrl: apiItem?.imageUrl,
            storeId: apiItem?.storeId || storeMeta?.storeId,
            storeName: apiItem?.storeName || storeMeta?.storeName,
            sizeSnapshot: size,
            sugarSnapshot: sugar,
            iceSnapshot: ice,
            toppingNames,
        }, storeMeta?.storeId, storeMeta?.storeName);
    }

    function buildCartsFromApiData(cartData) {
        const items = Array.isArray(cartData?.items) ? cartData.items : [];
        if (!items.length) return {};

        const result = {};
        for (const item of items) {
           const sid = toNumber(item?.storeId || cartData?.storeId, 0);
           if (!sid) continue;
           if (!result[sid]) {
               result[sid] = {
                   storeId: sid,
                   storeName: String(item?.storeName || cartData?.storeName || ''),
                   storeLogoUrl: String(item?.storeLogoUrl || cartData?.storeLogoUrl || ''),
                   items: []
               };
           }
           const mapped = toLegacyItemFromApi(item, { storeId: sid, storeName: result[sid].storeName, storeLogoUrl: result[sid].storeLogoUrl });
           if (mapped) result[sid].items.push(mapped);
        }
        return result;
    }

    function buildAddPayload(item, fallbackStoreId) {
        const storeId = toNumber(item?.storeId, toNumber(fallbackStoreId, 0));
        const productId = toNumber(item?.productId, 0);
        if (!storeId || !productId) return null;

        const detected = detectFromCustomization(item);
        return {
            storeId,
            productId,
            sugar: detected.sugar,
            ice: detected.ice,
            size: detected.size,
            toppingNames: detected.toppingNames,
            qty: clampQty(item?.quantity),
        };
    }

    async function syncFromServer(options = {}) {
        const { silent = true } = options;
        if (!isLoggedIn()) return getAllCarts();
        try {
            const cartData = await requestApi('/api/cart');
            const carts = buildCartsFromApiData(cartData);
            return saveAllCarts(carts, { emit: true });
        } catch (error) {
            // silent 是背景輪詢用的（每 3 秒一次）。使用者換頁時，正在飛的那個請求
            // 一定會被中斷成 "Failed to fetch"——那是正常現象，不該以 error 等級寫進 console，
            // 否則真正的錯誤會被這些雜訊淹掉。非 silent（使用者主動觸發）才視為錯誤。
            if (!silent) {
                console.error('syncFromServer failed:', error);
                throw error;
            }
            console.debug('syncFromServer: 背景同步未完成（多半是換頁中斷）', error);
            return getAllCarts();
        }
    }

    async function replaceServerCartFromSnapshot(cart) {
        if (!isLoggedIn()) return;
        const storeId = toNumber(cart?.storeId, 0);
        // 先清空該店家的雲端購物車
        if (storeId) {
            await requestApi(`/api/cart?storeId=${storeId}`, { method: 'DELETE' });
        } else {
            // 若無 storeId 則清空全部 (慎用)
            await requestApi('/api/cart', { method: 'DELETE' });
        }
        // 依序加入品項
        for (const item of (cart?.items || [])) {
            const payload = buildAddPayload(item, cart?.storeId);
            if (!payload) continue;
            await requestApi('/api/cart/items', { method: 'POST', body: payload });
        }
        // 同步完成後重新抓取
        await syncFromServer({ silent: true });
    }

    async function syncAllCartsToServer(allCarts) {
        if (!isLoggedIn()) return;
        // 1. 先清空伺服器所有個人購物車品項 (批次重置)
        await requestApi('/api/cart', { method: 'DELETE' });
        
        // 2. 依店家依序同步
        const cartsList = Object.values(allCarts || {});
        for (const cart of cartsList) {
            for (const item of (cart?.items || [])) {
                const payload = buildAddPayload(item, cart?.storeId);
                if (!payload) continue;
                await requestApi('/api/cart/items', { method: 'POST', body: payload });
            }
        }
        // 3. 最後執行一次拉取，更新本地 ID
        await syncFromServer({ silent: true });
    }

    async function addItemToServer(item) {
        const payload = buildAddPayload(item, item?.storeId);
        if (!payload) return;
        await requestApi('/api/cart/items', { method: 'POST', body: payload });
        await syncFromServer({ silent: true });
    }

    async function updateItemQtyToServer(cartItemId, qty) {
        if (!cartItemId) return;
        if (qty <= 0) {
            await requestApi(`/api/cart/items/${encodeURIComponent(cartItemId)}`, { method: 'DELETE' });
        } else {
            await requestApi(`/api/cart/items/${encodeURIComponent(cartItemId)}`, {
                method: 'PUT',
                body: { qty: clampQty(qty) },
            });
        }
        await syncFromServer({ silent: true });
    }

    function currentPollInterval() {
        return document.visibilityState === 'visible' ? POLL_VISIBLE_MS : POLL_HIDDEN_MS;
    }

    async function runRealtimeSyncOnce() {
        if (!isLoggedIn()) return;
        if (syncInFlight) return;
        syncInFlight = true;
        try {
            await syncFromServer({ silent: true });
        } finally {
            syncInFlight = false;
        }
    }

    function stopRealtimeTimer() {
        if (syncTimer) {
            clearTimeout(syncTimer);
            syncTimer = null;
        }
    }

    function scheduleRealtimeSync() {
        stopRealtimeTimer();
        if (!isLoggedIn()) return;
        syncTimer = setTimeout(async () => {
            await runRealtimeSyncOnce();
            scheduleRealtimeSync();
        }, currentPollInterval());
    }

    function internalSaveCart(storeId, cart, options = {}) {
        const { syncServer = true, emit = true } = options;
        if (!storeId) return;
        const key = String(toNumber(storeId, 0));
        if (!key || key === '0') return;

        const all = getAllCarts();
        const normalized = normalizeCart(cart, key);
        if (!normalized || normalized.items.length === 0) {
            delete all[key];
        } else {
            all[key] = normalized;
        }
        const saved = saveAllCarts(all, { emit });
        if (syncServer && isLoggedIn()) {
            const latestCart = normalized || { storeId: toNumber(storeId, 0), storeName: '', storeLogoUrl: '', items: [] };
            void replaceServerCartFromSnapshot(latestCart).catch((error) => {
                console.error('saveCart sync error:', error);
            });
        }
        return saved;
    }

    const Cart = {
        STORAGE_KEY,

        getAllCarts() {
            return getAllCarts();
        },

        saveAllCarts(carts) {
            const saved = saveAllCarts(carts, { emit: true });
            if (isLoggedIn()) {
                void syncAllCartsToServer(saved).catch((error) => {
                    console.error('saveAllCarts sync error:', error);
                });
            }
            return saved;
        },

        getCart(storeId) {
            return getCart(storeId);
        },

        saveCart(storeId, cart) {
            return internalSaveCart(storeId, cart, { syncServer: true, emit: true });
        },

        addItem(item) {
            const storeId = toNumber(item?.storeId, 0);
            if (!storeId) return false;

            const cart = getCart(storeId);
            if (!cart.storeName) {
                cart.storeName = item.storeName || cart.storeName || '';
            }
            if (item.storeLogoUrl) {
                cart.storeLogoUrl = item.storeLogoUrl;
            }

            const normalizedIncoming = normalizeLegacyItem(item, storeId, cart.storeName);
            if (!normalizedIncoming) return false;

            const specObj = {
                sz: normalizedIncoming.sizeSnapshot,
                su: normalizedIncoming.sugarSnapshot,
                ic: normalizedIncoming.iceSnapshot,
                tp: (normalizedIncoming.toppingNames || []).sort().join(','),
                cp: normalizedIncoming.couponId || 0
            };
            const spec = JSON.stringify(specObj);
            const existingIndex = cart.items.findIndex((it) => {
                const itSpecObj = {
                    sz: it.sizeSnapshot,
                    su: it.sugarSnapshot,
                    ic: it.iceSnapshot,
                    tp: (it.toppingNames || []).sort().join(','),
                    cp: it.couponId || 0
                };
                return Number(it.productId) === Number(normalizedIncoming.productId) &&
                       JSON.stringify(itSpecObj) === spec;
            });

            if (existingIndex >= 0) {
                cart.items[existingIndex].quantity = clampQty(
                    toNumber(cart.items[existingIndex].quantity, 1) + toNumber(normalizedIncoming.quantity, 1)
                );
            } else {
                cart.items.push(normalizedIncoming);
            }

            internalSaveCart(storeId, cart, { syncServer: false, emit: true });

            if (isLoggedIn()) {
                void addItemToServer(normalizedIncoming).catch((error) => {
                    console.error('addItem sync error:', error);
                });
            }
            return true;
        },

        updateQuantity(storeId, index, newQuantity) {
            const sid = toNumber(storeId, 0);
            if (!sid) return;
            const cart = getCart(sid);
            const idx = toNumber(index, -1);
            if (idx < 0 || idx >= cart.items.length) return;

            const item = cart.items[idx];
            const nextQty = toNumber(newQuantity, 0);
            if (nextQty <= 0) {
                cart.items.splice(idx, 1);
            } else {
                item.quantity = clampQty(nextQty);
            }

            internalSaveCart(sid, cart, { syncServer: false, emit: true });

            if (isLoggedIn()) {
                const cartItemId = toNumber(item?._cartItemId, 0);
                if (cartItemId > 0) {
                    void updateItemQtyToServer(cartItemId, nextQty).catch((error) => {
                        console.error('updateQuantity sync error:', error);
                    });
                } else {
                    // 如果是剛加入、尚未取得伺服器 ID 的本地品項，先不強制全量同步
                    // 避免本地更動後的狀態被尚未處理完畢的伺服器快取覆蓋
                    // 之後的輪詢 (Polling) 會自動帶回正確的 _cartItemId
                }
            }
        },

        removeItem(storeId, index) {
            this.updateQuantity(storeId, index, 0);
        },

        clearCart(storeId) {
            if (storeId) {
                const sid = toNumber(storeId, 0);
                if (!sid) return;
                const all = getAllCarts();
                delete all[String(sid)];
                saveAllCarts(all, { emit: true });
            } else {
                saveAllCarts({}, { emit: true });
            }

            if (isLoggedIn()) {
                const sid = toNumber(storeId, 0);
                const url = sid ? `/api/cart?storeId=${sid}` : '/api/cart';
                // 清空後不必再 syncFromServer：本地已經清乾淨，再拉一次只是多一趟請求，
                // 而且下單成功後畫面會立刻跳到訂單完成頁，這趟請求必定被中斷，
                // 於是每次下單都在 console 留下一則 "Failed to fetch" 的假錯誤。
                void requestApi(url, { method: 'DELETE' })
                    .catch((error) => console.debug('clearCart: 伺服器端清空未完成（多半是跳頁中斷）', error));
            }
        },

        getCartCount(storeId) {
            const cart = getCart(storeId);
            return cart.items.reduce((sum, item) => sum + clampQty(item.quantity), 0);
        },

        getCartTotal(storeId) {
            const cart = getCart(storeId);
            return cart.items.reduce((sum, item) => {
                const price = Math.max(0, toNumber(item.price, 0));
                return sum + (price * clampQty(item.quantity));
            }, 0);
        },

        getAllActiveCarts() {
            const all = getAllCarts();
            const active = {};
            for (const key of Object.keys(all)) {
                const cart = all[key];
                if (cart?.items?.length > 0) active[key] = cart;
            }
            return active;
        },

        getActiveCartsList() {
            return Object.values(this.getAllActiveCarts());
        },

        getTotalGlobalCount() {
            return this.getActiveCartsList().reduce((sum, cart) => {
                return sum + cart.items.reduce((itemSum, item) => itemSum + clampQty(item.quantity), 0);
            }, 0);
        },

        async forceSync() {
            return syncFromServer({ silent: false });
        },

        startRealtimeSync() {
            if (syncBound) {
                scheduleRealtimeSync();
                return;
            }
            syncBound = true;

            void runRealtimeSyncOnce();
            scheduleRealtimeSync();

            window.addEventListener('focus', () => {
                void runRealtimeSyncOnce();
                scheduleRealtimeSync();
            });

            document.addEventListener('visibilitychange', () => {
                scheduleRealtimeSync();
            });
        },

        stopRealtimeSync() {
            stopRealtimeTimer();
        },
    };

    window.Cart = Cart;

    window.addEventListener('storage', (event) => {
        if (!event) return;
        if (event.key === STORAGE_KEY) {
            const carts = getAllCarts();
            emitCartUpdated(carts);
            return;
        }
        if (event.key === 'userToken' || event.key === 'JOIN_TOKEN') {
            if (isLoggedIn()) {
                void runRealtimeSyncOnce();
                scheduleRealtimeSync();
            } else {
                stopRealtimeTimer();
            }
        }
    });

    // ⚠️ 不要改回在載入當下就直接呼叫 startRealtimeSync()。
    // requestApi() 需要 nav-auth.js 提供的 NavAuth.getApiUrl 才能組出正確的 API 位址；
    // store.html / checkout.html 是先載 cart.js 再載 nav-auth.js，立即同步會因為 NavAuth
    // 還不存在而退回相對路徑，變成打靜態伺服器（實測 404 http://127.0.0.1:5500/api/cart），
    // 第一次同步必定失敗、購物車要等 3 秒後的輪詢才會補正。
    // 延到 DOMContentLoaded，此時文件內的同步 script 都已執行完畢，與載入順序無關。
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => Cart.startRealtimeSync());
    } else {
        Cart.startRealtimeSync();
    }
})();
