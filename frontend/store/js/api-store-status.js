/**
 * api-store-status.js — 全局營業狀態與頭像同步 (極速無閃爍版)
 */
(() => {
    const LOGO_CACHE_KEY = 'cached_brand_logo';
    const STATUS_CACHE_KEY = 'cached_store_status';

    // ── 立即執行：恢復快取 ──────────────────────────
    const applyCache = () => {
        const cachedLogo = localStorage.getItem(LOGO_CACHE_KEY);
        const cachedStatus = localStorage.getItem(STATUS_CACHE_KEY);

        if (cachedLogo) {
            const avatarImg = document.querySelector('header img[alt="用戶頭像"]');
            if (avatarImg) {
                avatarImg.src = cachedLogo;
                avatarImg.classList.add('object-contain', 'p-1', 'bg-white');
            }
        }

        if (cachedStatus) {
            try {
                const data = JSON.parse(cachedStatus);
                updateStatusUI(data.status);
            } catch(e) {}
        }
    };

    const updateStatusUI = (status) => {
        const dot = document.getElementById('storeStatusDot');
        const label = document.getElementById('storeStatusLabel');
        const toggle = document.getElementById('storeStatusToggle');
        
        if (!dot || !label || !toggle) return;

        // 統一判斷邏輯：不分大小寫，包含 'active' 或 'open'
        const s = String(status || '').toLowerCase();
        const isOpen = (s === 'active' || s === 'open');
        const actualStatus = isOpen ? 'active' : 'closed';
        
        // 更新樣式與文字
        dot.className = `w-2 h-2 rounded-full mr-2 ${isOpen ? 'bg-green-500' : 'bg-red-500'}`;
        label.textContent = isOpen ? '營業中' : '休息中';
        label.className = `text-sm font-bold ${isOpen ? 'text-green-700' : 'text-red-700'}`;
        
        // 設定正確的 data-status 供切換腳本讀取
        toggle.setAttribute('data-status', actualStatus);
        toggle.className = `flex items-center px-4 py-2 rounded-lg border transition-colors ${isOpen ? 'bg-green-50 border-green-200 hover:bg-green-100' : 'bg-red-50 border-red-200 hover:bg-red-100'}`;
        
        // 儲存狀態快取
        localStorage.setItem(STATUS_CACHE_KEY, JSON.stringify({ status: actualStatus }));
    };

    const updateProfileCache = (profile) => {
        if (!profile) return;

        // 1. Logo (頭像)
        const logoUrl = profile.brand?.logoUrl;
        if (logoUrl) {
            const avatarImg = document.querySelector('header img[alt="用戶頭像"]');
            if (avatarImg) {
                avatarImg.src = logoUrl;
                avatarImg.classList.add('object-contain', 'p-1', 'bg-white');
            }
            localStorage.setItem(LOGO_CACHE_KEY, logoUrl);
        }
    };

    const init = async () => {
        // 1. 嘗試立即套用快取
        applyCache();

        try {
            // 使用 freshGet 確保取得的是最新資料而非舊快取
            const profile = await window.StoreAPI.refreshProfile();
            window.STORE_PROFILE = profile;
            
            // 2. 更新最新狀態並寫入快取
            updateStatusUI(profile.status);
            updateProfileCache(profile);
            
        } catch (err) {
            console.error('[Status] 獲取最新狀態失敗:', err);
            // 失敗時至少維持快取顯示
            applyCache();
        }
    };

    // 為了極致防閃爍，我們在載入時執行一次，DOMContentLoaded 再執行一次
    applyCache();

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
    
    window.updateGlobalStatusUI = updateStatusUI;
})();