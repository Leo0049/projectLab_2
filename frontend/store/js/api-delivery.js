/**
 * api-delivery.js — 外送設定
 * Stale-while-revalidate：先顯示持久快取，背景靜默更新
 */
(() => {
    const extractDelivery = (profile) => ({
        isDelivery: profile.isDeliveryAvailable ?? true,
        maxKm:      profile.maxDeliveryKm ?? 5,
        minAmount:  profile.minDeliveryAmount ?? 0,
        startTime:  profile.deliveryStartTime || '10:00',
        endTime:    profile.deliveryEndTime   || '21:00',
    });

    const applyData = (data) => {
        const toggle = document.getElementById('deliveryEnabledToggle');
        if (toggle) { toggle.checked = data.isDelivery; toggle.dispatchEvent(new Event('change')); }

        const kmEl     = document.getElementById('maxDeliveryKm');
        const amountEl = document.getElementById('minDeliveryAmount');
        const startEl  = document.getElementById('deliveryStartTime');
        const endEl    = document.getElementById('deliveryEndTime');
        if (kmEl)     kmEl.value     = data.maxKm;
        if (amountEl) amountEl.value = data.minAmount;
        if (startEl)  startEl.value  = data.startTime;
        if (endEl)    endEl.value    = data.endTime;

        // --- 自動偵測時間並更新外送狀態 ---
        checkAutoDeliveryStatus(data);

        // 資料填入完成後，移除隱藏狀態
        const root = document.getElementById('mainContentFade');
        if (root) root.classList.remove('opacity-0');
    };

    const checkAutoDeliveryStatus = async (data) => {
        const now = new Date();
        const currentTime = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
        
        const startTime = data.startTime || '10:00';
        const endTime   = data.endTime   || '21:00';
        
        // 判斷當前是否在區間內
        const isInRange = currentTime >= startTime && currentTime <= endTime;
        
        // 如果狀態不符合 (例如: 時間到了但還沒開，或時間過了但還沒關)，則自動更新
        if (isInRange !== data.isDelivery) {
            console.log(`[AutoDelivery] 時間偵測中 (${currentTime}), 自動將外送狀態設為: ${isInRange ? '開啟' : '關閉'}`);
            
            try {
                const payload = {
                    allowsDelivery:    isInRange,
                    maxDeliveryKm:     data.maxKm,
                    minDeliveryAmount: data.minAmount,
                    deliveryStartTime: data.startTime,
                    deliveryEndTime:   data.endTime,
                };
                
                await window.StoreAPI.updateDeliveryConfig(payload);
                
                // 更新成功後，同步 UI 並刷新快取
                const toggle = document.getElementById('deliveryEnabledToggle');
                if (toggle) {
                    toggle.checked = isInRange;
                    toggle.dispatchEvent(new Event('change'));
                }
                
                const fresh = await window.StoreAPI.refreshProfile();
                window.StoreAPI.saveProfileSnapshot(fresh);
                
                window.StoreAPI.showToast(`系統已根據外送時間設定自動${isInRange ? '開啟' : '關閉'}外送`, 'info');
            } catch (err) {
                console.error('[AutoDelivery] 自動更新失敗:', err);
            }
        }
    };

    const init = async () => {
        // 1. 持久快取 → 立即顯示
        const snapshot = window.StoreAPI.getProfileSnapshot();
        if (snapshot) applyData(extractDelivery(snapshot));

        // 2. 背景靜默刷新
        try {
            const profile = await window.StoreAPI.refreshProfile();
            applyData(extractDelivery(profile));
            window.StoreAPI.saveProfileSnapshot(profile);
        } catch (err) {
            if (!snapshot) console.warn('[api-delivery] 載入失敗:', err.message);
        }

        // ── 儲存按鈕 ────────────────────────────
        const saveBtn = document.querySelector('button.bg-primary');
        if (saveBtn) {
            saveBtn.onclick = async () => {
                const toggle   = document.getElementById('deliveryEnabledToggle');
                const kmEl     = document.getElementById('maxDeliveryKm');
                const amountEl = document.getElementById('minDeliveryAmount');
                const startEl  = document.getElementById('deliveryStartTime');
                const endEl    = document.getElementById('deliveryEndTime');

                const payload = {
                    allowsDelivery:    toggle?.checked ?? true,
                    maxDeliveryKm:     parseFloat(kmEl?.value) || 0,
                    minDeliveryAmount: parseFloat(amountEl?.value) || 0,
                    deliveryStartTime: startEl?.value || '10:00',
                    deliveryEndTime:   endEl?.value   || '21:00',
                };

                const oldHtml = saveBtn.innerHTML;
                saveBtn.disabled = true;
                saveBtn.innerHTML = '<span class="material-symbols-outlined animate-spin text-sm">sync</span> 儲存中...';

                try {
                    await window.StoreAPI.updateDeliveryConfig(payload);
                    window.StoreAPI.showToast('外送設定儲存成功！', 'success');

                    // 重新拉最新資料更新快取，並立即套用數據以觸發自動偵測
                    const fresh = await window.StoreAPI.refreshProfile();
                    applyData(extractDelivery(fresh));
                    window.StoreAPI.saveProfileSnapshot(fresh);

                    saveBtn.innerHTML = '<span class="material-symbols-outlined text-sm">check_circle</span> 已儲存';
                    setTimeout(() => { saveBtn.innerHTML = oldHtml; saveBtn.disabled = false; }, 2000);
                } catch (err) {
                    window.StoreAPI.showToast('儲存失敗：' + err.message, 'error');
                    saveBtn.disabled = false;
                    saveBtn.innerHTML = oldHtml;
                }
            };
        }
    };

    init();
})();
