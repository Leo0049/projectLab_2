/**
 * api-store-profile.js — 商店基本資料管理
 * Stale-while-revalidate：先顯示持久快取，背景靜默更新
 */
(() => {
    let currentStoreId = null;

    const applyProfile = (data) => {
        if (!data) return;
        currentStoreId = data.id;
        if (!localStorage.getItem('store_id')) localStorage.setItem('store_id', data.id);

        setText('displayStoreName', data.name);
        setText('displayBrandName', data.brand?.name || '無品牌資訊');

        const headerLogo = document.getElementById('headerStoreLogo');
        if (headerLogo) {
            headerLogo.src = data.brand?.logoUrl || '';
            headerLogo.classList.add('object-contain', 'p-1', 'bg-white');
        }
        const brandLogo = document.getElementById('brandLogoUrl');
        if (brandLogo) {
            brandLogo.src = data.brand?.logoUrl || '';
            brandLogo.classList.add('object-contain', 'p-1');
        }
        const storeCover = document.getElementById('storeCoverUrl');
        if (storeCover) storeCover.src = data.coverUrl || 'https://images.unsplash.com/photo-1554118811-1e0d58224f24?q=80&w=2047&auto=format&fit=crop';

        setValue('storePhone',   data.phone);
        setValue('storeAddress', data.address);

        // 資料填入後移除隱藏狀態
        const root = document.getElementById('mainContentFade');
        if (root) root.classList.remove('opacity-0');
    };

    const init = async () => {
        // 1. 持久快取 → 立即顯示，不等 API
        const snapshot = window.StoreAPI.getProfileSnapshot();
        if (snapshot) applyProfile(snapshot);

        // 2. 背景靜默刷新
        try {
            const fresh = await window.StoreAPI.refreshProfile();
            applyProfile(fresh);
            window.StoreAPI.saveProfileSnapshot(fresh);
        } catch (err) {
            if (!snapshot) console.error('[Profile] 載入失敗:', err);
        }

        const saveBtn = document.getElementById('saveProfileBtn');
        if (saveBtn) saveBtn.onclick = handleSaveProfile;
    };

    const handleSaveProfile = async (e) => {
        const btn = e.currentTarget;
        const originalContent = btn.innerHTML;
        const phone   = document.getElementById('storePhone')?.value.trim();
        const address = document.getElementById('storeAddress')?.value.trim();
        const storeId = currentStoreId || localStorage.getItem('store_id');

        if (!storeId) {
            window.StoreAPI.showToast('無法取得店家 ID，請重新登入', 'error');
            return;
        }

        try {
            btn.disabled = true;
            btn.innerHTML = '<span class="material-symbols-outlined animate-spin text-xl">save</span> 儲存資料中...';

            await window.StoreAPI.updateStore({ id: storeId, managerPhone: phone, address });

            window.StoreAPI.showToast('商店資料儲存成功！', 'success');

            // 重新拉最新資料更新畫面與快取
            const fresh = await window.StoreAPI.refreshProfile();
            applyProfile(fresh);
            window.StoreAPI.saveProfileSnapshot(fresh);
        } catch (err) {
            window.StoreAPI.showToast('儲存失敗: ' + err.message, 'error');
        } finally {
            btn.disabled = false;
            btn.innerHTML = originalContent;
        }
    };

    const setText  = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val || '—'; };
    const setValue = (id, val) => { const el = document.getElementById(id); if (el) el.value = val || ''; };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
