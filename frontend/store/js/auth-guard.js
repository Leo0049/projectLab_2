/**
 * auth-guard.js — JWT 驗證守衛
 * 若無 token 則跳轉到登入頁
 * 需在 store-api.js 之後載入
 */
(() => {
    const getLoginUrl = () => {
        try {
            const parts = window.location.pathname.split('/');
            const idx = parts.indexOf('frontend');
            if (idx >= 0) return window.location.origin + parts.slice(0, idx + 1).join('/') + '/Customer/auth/login.html';
        } catch (_) {}
        return '../Customer/auth/login.html';
    };

    const token = localStorage.getItem('store_token');
    if (!token) {
        window.location.href = getLoginUrl();
        return;
    }

    // 載入分店資料到 header
    const loadStoreInfo = async () => {
        try {
            const profile = await window.StoreAPI.getProfile();
            window.STORE_PROFILE = profile;

            // 只更新有 data-store-name 屬性的元素（避免覆蓋其他頁面的頁面名稱）
            const nameEls = document.querySelectorAll('[data-store-name]');
            nameEls.forEach(el => { el.textContent = profile.storeName || profile.store_name || ''; });

            // 初始化營業狀態
            const status = profile.status || 'active';
            window.dispatchEvent(new CustomEvent('store:profile-loaded', { detail: { profile, status } }));
        } catch (err) {
            console.warn('[auth-guard] 無法載入分店資料:', err.message);
        }
    };

    // 等 DOM 完成後載入
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', loadStoreInfo);
    } else {
        loadStoreInfo();
    }
})();
