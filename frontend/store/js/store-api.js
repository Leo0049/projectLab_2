/**
 * store-api.js — 分店後台 API 工具
 * 所有頁面共用，需在 auth-guard.js 之前載入
 */
(() => {
    const BASE = 'http://localhost:8082';
    const TOKEN_KEY = 'store_token';

    const getToken = () => localStorage.getItem(TOKEN_KEY);
    const clearToken = () => localStorage.removeItem(TOKEN_KEY);

    const getLoginUrl = () => {
        try {
            const parts = window.location.pathname.split('/');
            const idx = parts.indexOf('frontend');
            if (idx >= 0) return window.location.origin + parts.slice(0, idx + 1).join('/') + '/Customer/auth/login.html';
        } catch (_) {}
        return '../Customer/auth/login.html';
    };

    const authHeaders = () => ({
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${getToken()}`
    });

    const handleResponse = async (res) => {
        if (res.status === 401 || res.status === 403) {
            clearToken();
            window.location.href = getLoginUrl();
            throw new Error('Unauthorized');
        }
        const json = await res.json();
        if (String(json.code) !== '200') throw new Error(json.msg || 'API 錯誤');
        return json.data;
    };

    // ── 快取層 ──────────────────────────────────────────────────────────────
    const CACHE_TTL   = 5 * 60 * 1000;           // sessionStorage 5 分鐘
    const PERSIST_TTL = 24 * 60 * 60 * 1000;     // localStorage 24 小時
    const C_PFX = 'storeCache:v1:';
    const P_PFX = 'storePersist:v1:';
    const _ck  = k => `${localStorage.getItem('store_id') || 'x'}:${k}`;

    const _cGet = k => {
        try { const r = sessionStorage.getItem(C_PFX + _ck(k)); if (!r) return null;
              const { data, exp } = JSON.parse(r); if (Date.now() > exp) { sessionStorage.removeItem(C_PFX + _ck(k)); return null; } return data; } catch { return null; }
    };
    const _cSet = (k, data) => {
        try { sessionStorage.setItem(C_PFX + _ck(k), JSON.stringify({ data, exp: Date.now() + CACHE_TTL })); } catch {}
    };
    const _cDel = (...keys) => keys.forEach(k => { try { sessionStorage.removeItem(C_PFX + _ck(k)); } catch {} });

    const _pGet = k => {
        try { const r = localStorage.getItem(P_PFX + _ck(k)); if (!r) return null;
              const { data, exp } = JSON.parse(r); if (Date.now() > exp) { localStorage.removeItem(P_PFX + _ck(k)); return null; } return data; } catch { return null; }
    };
    const _pSet = (k, data) => {
        try { localStorage.setItem(P_PFX + _ck(k), JSON.stringify({ data, exp: Date.now() + PERSIST_TTL })); } catch {}
    };
    const _pDel = (...keys) => keys.forEach(k => { try { localStorage.removeItem(P_PFX + _ck(k)); } catch {} });

    const PROFILE_KEY           = '/api/stores/settings/profile';
    const DASHBOARD_KEY         = '/api/stores/dashboard/today-summary';
    const ANALYTICS_KEY         = '/api/stores/dashboard/analytics';
    const DRINKS_KEY            = '/api/stores/menu/drinks';
    const TOPPINGS_KEY          = '/api/stores/menu/toppings';
    const FINANCE_CAT_KEY       = '/api/stores/finance/categorized?period=month';
    const FINANCE_TREND_KEY     = '/api/stores/finance/category-trend?period=this-week';
    const RATING_KEY            = '/api/stores/reports/rating-stats';
    const RECENT_REPORTS_KEY    = '/api/stores/reports/recent-daily';
    const PRODUCT_RANKING_KEY   = '/api/stores/reports/product-ranking?period=month';

    // 有快取就立即回傳，沒有則發請求
    const cachedGet = async path => {
        const hit = _cGet(path); if (hit) return hit;
        const data = await api.get(path); _cSet(path, data); return data;
    };
    // 一定發請求，更新快取
    const freshGet = async path => {
        const data = await api.get(path); _cSet(path, data); return data;
    };

    const api = {
        get: (path) =>
            fetch(`${BASE}${path}`, { headers: authHeaders() }).then(handleResponse),

        post: (path, body = {}) =>
            fetch(`${BASE}${path}`, {
                method: 'POST',
                headers: authHeaders(),
                body: JSON.stringify(body)
            }).then(handleResponse),

        put: (path, body = {}) =>
            fetch(`${BASE}${path}`, {
                method: 'PUT',
                headers: authHeaders(),
                body: JSON.stringify(body)
            }).then(handleResponse),

        patch: (path, body = {}) =>
            fetch(`${BASE}${path}`, {
                method: 'PATCH',
                headers: authHeaders(),
                body: JSON.stringify(body)
            }).then(handleResponse),
    };

    const StoreAPI = {
        TOKEN_KEY,
        getToken,
        clearToken,

        // ── 確認彈窗 ────────────────────────────
        showConfirm: ({ title = '確定嗎？', body = '', confirmText = '確定', confirmClass = 'danger' } = {}) => {
            return new Promise((resolve) => {
                const overlay = document.createElement('div');
                overlay.style.cssText = 'position:fixed;inset:0;z-index:9999;display:flex;align-items:center;justify-content:center;background:rgba(15,23,42,.55);backdrop-filter:blur(4px)';
                const btnStyle = confirmClass === 'danger'
                    ? 'flex-1;padding:.65rem 1rem;border-radius:.75rem;background:#dc2626;color:#fff;border:none;font-size:.875rem;font-weight:800;cursor:pointer'
                    : 'flex-1;padding:.65rem 1rem;border-radius:.75rem;background:#ec5b13;color:#fff;border:none;font-size:.875rem;font-weight:800;cursor:pointer';
                overlay.innerHTML = `
                  <div style="background:#fff;border-radius:1.25rem;padding:2rem 1.75rem;max-width:360px;width:90%;box-shadow:0 25px 50px -12px rgba(0,0,0,.3);text-align:center">
                    <div style="width:3rem;height:3rem;border-radius:50%;background:#fef2f2;display:flex;align-items:center;justify-content:center;margin:0 auto .875rem">
                      <span class="material-symbols-outlined" style="color:#dc2626;font-size:1.4rem">warning</span>
                    </div>
                    <h3 style="font-size:1rem;font-weight:900;color:#0f172a;margin-bottom:.4rem">${title}</h3>
                    ${body ? `<p style="font-size:.8rem;color:#64748b;margin-bottom:1.25rem">${body}</p>` : '<div style="margin-bottom:1.25rem"></div>'}
                    <div style="display:flex;gap:.625rem">
                      <button data-action="cancel" style="flex:1;padding:.65rem 1rem;border-radius:.75rem;background:#f1f5f9;color:#475569;border:none;font-size:.875rem;font-weight:700;cursor:pointer">取消</button>
                      <button data-action="confirm" style="flex:${btnStyle}">${confirmText}</button>
                    </div>
                  </div>`;
                document.body.appendChild(overlay);
                overlay.addEventListener('click', (e) => {
                    const action = e.target.closest('[data-action]')?.dataset.action;
                    if (!action) return;
                    overlay.remove();
                    resolve(action === 'confirm');
                });
            });
        },

        // ── 通知工具 ────────────────────────────
        showToast: (msg, type = 'success') => {
            const container = document.getElementById('toast-container') || (() => {
                const div = document.createElement('div');
                div.id = 'toast-container';
                div.className = 'fixed bottom-8 right-8 z-[100] flex flex-col gap-3 pointer-events-none';
                document.body.appendChild(div);
                return div;
            })();

            const toast = document.createElement('div');
            toast.className = `
                px-6 py-3 rounded-xl shadow-2xl border text-white font-bold text-sm
                flex items-center gap-3 transform translate-y-10 opacity-0 transition-all duration-300 pointer-events-auto
                ${type === 'success' ? 'bg-green-500 border-green-400' : 'bg-red-500 border-red-400'}
            `;
            
            const icon = type === 'success' ? 'check_circle' : 'error';
            toast.innerHTML = `
                <span class="material-symbols-outlined text-xl">${icon}</span>
                <span>${msg}</span>
            `;

            container.appendChild(toast);

            // 動畫進入
            requestAnimationFrame(() => {
                toast.classList.remove('translate-y-10', 'opacity-0');
            });

            // 自動移除
            setTimeout(() => {
                toast.classList.add('translate-y-[-20px]', 'opacity-0');
                setTimeout(() => toast.remove(), 300);
            }, 3000);
        },

        // 分店資訊（含快取）
        getProfile:          () => cachedGet(PROFILE_KEY),
        refreshProfile:      () => freshGet(PROFILE_KEY),
        getProfileSnapshot:  () => _pGet(PROFILE_KEY),
        saveProfileSnapshot: (d) => _pSet(PROFILE_KEY, d),
        updateStore: (data) => api.put('/api/stores/update', data)
            .then(r => { _cDel(PROFILE_KEY); _pDel(PROFILE_KEY); return r; }),
        updateStatus: (status) => api.put('/api/stores/status', { status })
            .then(r => { _cDel(PROFILE_KEY); _pDel(PROFILE_KEY); return r; }),

        // 圖片上傳
        uploadImage: (file) => {
            const formData = new FormData();
            formData.append('file', file);
            return fetch(`${BASE}/api/stores/upload-image`, {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${getToken()}` },
                body: formData
            }).then(handleResponse);
        },

        updateBusinessHours: (hours) => api.put('/api/stores/settings/business-hours', { openingHours: hours }),
        updateDeliveryConfig: (config) => api.patch('/api/stores/settings/delivery-config', config)
            .then(r => { _cDel(PROFILE_KEY); _pDel(PROFILE_KEY); return r; }),

        // 儀表板（含持久快取）
        getDashboardSummary:      () => cachedGet(DASHBOARD_KEY),
        refreshDashboard:         () => freshGet(DASHBOARD_KEY),
        getDashboardSnapshot:     () => _pGet(DASHBOARD_KEY),
        saveDashboardSnapshot:    (d) => _pSet(DASHBOARD_KEY, d),

        getPendingOrders: () => api.get('/api/stores/dashboard/orders/pending'),
        getActiveOrders:  () => api.get('/api/stores/dashboard/orders/active'),
        getSalesTrend:    () => api.get('/api/stores/dashboard/sales-trend'),

        getAnalytics:          () => cachedGet(ANALYTICS_KEY),
        refreshAnalytics:      () => freshGet(ANALYTICS_KEY),
        getAnalyticsSnapshot:  () => _pGet(ANALYTICS_KEY),
        saveAnalyticsSnapshot: (d) => _pSet(ANALYTICS_KEY, d),

        // 數據報表（含持久快取）
        getTodaySummary:             () => cachedGet(DASHBOARD_KEY),
        getRecentDailyReports:       () => cachedGet(RECENT_REPORTS_KEY),
        refreshRecentReports:        () => freshGet(RECENT_REPORTS_KEY),
        getRecentReportsSnapshot:    () => _pGet(RECENT_REPORTS_KEY),
        saveRecentReportsSnapshot:   (d) => _pSet(RECENT_REPORTS_KEY, d),

        getProductRanking:           (period = 'month') => cachedGet(PRODUCT_RANKING_KEY),
        refreshProductRanking:       () => freshGet(PRODUCT_RANKING_KEY),
        getProductRankingSnapshot:   () => _pGet(PRODUCT_RANKING_KEY),
        saveProductRankingSnapshot:  (d) => _pSet(PRODUCT_RANKING_KEY, d),

        getRatingStats:          () => cachedGet(RATING_KEY),
        refreshRatingStats:      () => freshGet(RATING_KEY),
        getRatingStatsSnapshot:  () => _pGet(RATING_KEY),
        saveRatingStatsSnapshot: (d) => _pSet(RATING_KEY, d),

        getOrders: (status) => api.get('/api/stores/orders' + (status ? `?status=${status}` : '')),
        acceptOrder:         (orderId) => api.post(`/api/stores/dashboard/orders/${orderId}/accept`),
        rejectOrder:         (orderId) => api.post(`/api/stores/dashboard/orders/${orderId}/reject`),
        completeProduction:  (orderId) => api.post(`/api/stores/dashboard/orders/${orderId}/complete-production`),
        finalizeOrder:       (orderId) => api.post(`/api/stores/dashboard/orders/${orderId}/finalize`),

        // 庫存管理 飲品（含持久快取）
        getMenuDrinks:          () => cachedGet(DRINKS_KEY),
        refreshMenuDrinks:      () => freshGet(DRINKS_KEY),
        getMenuDrinksSnapshot:  () => _pGet(DRINKS_KEY),
        saveMenuDrinksSnapshot: (d) => _pSet(DRINKS_KEY, d),
        toggleDrinkSupply: (drinkId, isAvailable) => api.patch(`/api/stores/menu/drinks/${drinkId}/toggle`, { isAvailable })
            .then(r => { _cDel(DRINKS_KEY); return r; }),

        // 庫存管理 配料（含持久快取）
        getMenuToppings:           () => cachedGet(TOPPINGS_KEY),
        getToppings:               () => cachedGet(TOPPINGS_KEY),
        refreshMenuToppings:       () => freshGet(TOPPINGS_KEY),
        getMenuToppingsSnapshot:   () => _pGet(TOPPINGS_KEY),
        saveMenuToppingsSnapshot:  (d) => _pSet(TOPPINGS_KEY, d),
        toggleToppingSupply: (brandToppingId, isAvailable) => api.patch(`/api/stores/menu/toppings/${brandToppingId}/toggle`, { isAvailable })
            .then(r => { _cDel(TOPPINGS_KEY); return r; }),
        toggleToppingStock: (id) => api.patch(`/api/stores/menu/toppings/${id}/toggle`)
            .then(r => { _cDel(TOPPINGS_KEY); return r; }),

        // 財務（含持久快取）
        getFinanceSummary:    (period = 'month') => api.get(`/api/stores/finance/summary?period=${period}`),
        getFinanceCommissions:(period = 'month') => api.get(`/api/stores/finance/commissions?period=${period}`),

        getFinanceCategorized:          (period = 'month') => cachedGet(FINANCE_CAT_KEY),
        refreshFinanceCategorized:      () => freshGet(FINANCE_CAT_KEY),
        getFinanceCategorizedSnapshot:  () => _pGet(FINANCE_CAT_KEY),
        saveFinanceCategorizedSnapshot: (d) => _pSet(FINANCE_CAT_KEY, d),

        getFinanceCategoryTrend:          (period = 'this-week') => cachedGet(FINANCE_TREND_KEY),
        refreshFinanceTrend:              () => freshGet(FINANCE_TREND_KEY),
        getFinanceTrendSnapshot:          () => _pGet(FINANCE_TREND_KEY),
        saveFinanceTrendSnapshot:         (d) => _pSet(FINANCE_TREND_KEY, d),
    };

    window.StoreAPI = StoreAPI;
})();
