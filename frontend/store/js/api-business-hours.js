/**
 * api-business-hours.js — 營業時間
 * Stale-while-revalidate：先顯示持久快取，背景靜默更新
 */
(() => {
    const DAY_KEYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
    const DEFAULT_OPEN  = '09:00';
    const DEFAULT_CLOSE = '21:00';

    const applyClosedState = (day, isClosed) => {
        const row = document.querySelector(`[data-day-row="${day}"]`);
        if (!row) return;
        const startEl = row.querySelector(`[data-day-start="${day}"]`);
        const endEl   = row.querySelector(`[data-day-end="${day}"]`);
        const label   = row.querySelector(`[data-day-closed-label="${day}"]`);
        if (startEl) { startEl.disabled = isClosed; startEl.classList.toggle('opacity-50', isClosed); }
        if (endEl)   { endEl.disabled   = isClosed; endEl.classList.toggle('opacity-50',   isClosed); }
        if (label)   { label.className  = `text-sm font-bold transition-colors ${isClosed ? 'text-red-500' : 'text-slate-500'}`; }
        if (isClosed) { row.classList.add('bg-slate-50/50'); row.classList.remove('hover:bg-slate-50'); }
        else          { row.classList.remove('bg-slate-50/50'); row.classList.add('hover:bg-slate-50'); }
    };

    const extractHours = (profile) => {
        if (!profile?.openingHours) return null;
        return typeof profile.openingHours === 'string'
            ? JSON.parse(profile.openingHours)
            : profile.openingHours;
    };

    const applyData = (hours) => {
        if (!hours) return;
        DAY_KEYS.forEach(day => {
            const dayData  = hours[day] || { closed: false, open: DEFAULT_OPEN, close: DEFAULT_CLOSE };
            const startEl  = document.querySelector(`[data-day-start="${day}"]`);
            const endEl    = document.querySelector(`[data-day-end="${day}"]`);
            const closedEl = document.querySelector(`[data-day-closed="${day}"]`);
            if (startEl && dayData.open)  startEl.value  = dayData.open;
            if (endEl   && dayData.close) endEl.value    = dayData.close;
            const closedDesktopEl = document.querySelector(`[data-day-closed-desktop="${day}"]`);
            if (closedEl) {
                closedEl.checked = !!dayData.closed;
                applyClosedState(day, !!dayData.closed);
            }
            if (closedDesktopEl) closedDesktopEl.checked = !!dayData.closed;
        });
        const container = document.getElementById('businessHoursContainer');
        if (container) container.classList.remove('opacity-0');
        
        // 資料填入後移除外層隱藏狀態
        const root = document.getElementById('mainContentFade');
        if (root) root.classList.remove('opacity-0');
    };

    const init = async () => {
        // 1. 持久快取 → 立即顯示
        const snapshot = window.StoreAPI.getProfileSnapshot();
        const snapHours = extractHours(snapshot);
        if (snapHours) applyData(snapHours);

        // 2. 背景靜默刷新
        try {
            const profile = await window.StoreAPI.refreshProfile();
            const hours   = extractHours(profile);
            if (hours) applyData(hours);
            window.StoreAPI.saveProfileSnapshot(profile);
        } catch (err) {
            if (!snapHours) {
                console.warn('[api-business-hours] 載入失敗:', err.message);
                const container = document.getElementById('businessHoursContainer');
                if (container) container.classList.remove('opacity-0');
                const root = document.getElementById('mainContentFade');
                if (root) root.classList.remove('opacity-0');
            }
        }

        // 店休 checkbox 事件（mobile + desktop 雙向同步）
        DAY_KEYS.forEach(day => {
            const closedEl        = document.querySelector(`[data-day-closed="${day}"]`);
            const closedDesktopEl = document.querySelector(`[data-day-closed-desktop="${day}"]`);
            if (closedEl) closedEl.addEventListener('change', () => {
                if (closedDesktopEl) closedDesktopEl.checked = closedEl.checked;
                applyClosedState(day, closedEl.checked);
            });
            if (closedDesktopEl) closedDesktopEl.addEventListener('change', () => {
                if (closedEl) closedEl.checked = closedDesktopEl.checked;
                applyClosedState(day, closedDesktopEl.checked);
            });
        });

        // ── 儲存按鈕 ────────────────────────────
        const saveBtn = document.querySelector('button.bg-primary');
        if (saveBtn) {
            saveBtn.onclick = async () => {
                const payload = {};
                DAY_KEYS.forEach(day => {
                    const s = document.querySelector(`[data-day-start="${day}"]`);
                    const e = document.querySelector(`[data-day-end="${day}"]`);
                    const c = document.querySelector(`[data-day-closed="${day}"]`);
                    payload[day] = { closed: c?.checked || false, open: s?.value || DEFAULT_OPEN, close: e?.value || DEFAULT_CLOSE };
                });

                const oldHtml = saveBtn.innerHTML;
                saveBtn.disabled = true;
                saveBtn.innerHTML = '<span class="material-symbols-outlined animate-spin text-sm">sync</span> 儲存中...';

                try {
                    await window.StoreAPI.updateBusinessHours(payload);
                    window.StoreAPI.showToast('營業時間儲存成功！', 'success');

                    // 重新拉最新資料更新快取
                    const fresh = await window.StoreAPI.refreshProfile();
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
