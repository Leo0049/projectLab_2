/**
 * home-inventory-quick-toggle.js — 配料庫存快速設定 (修正版)
 */
(function () {
    const listContainer = document.getElementById('quickInventoryList');
    if (!listContainer) return;

    async function loadQuickToppings() {
        try {
            const toppings = await window.StoreAPI.getToppings();
            if (!toppings || toppings.length === 0) {
                listContainer.innerHTML = '<p class="text-xs text-slate-400 text-center py-4">目前尚無配料數據</p>';
                return;
            }

            // 取得前 3 個配料
            const displayList = toppings.slice(0, 3);

            listContainer.innerHTML = displayList.map(t => {
                // 修正：API 回傳的 ID 欄位是 brandToppingId，狀態是 isEnabled
                const tid = t.brandToppingId || t.id;
                const isAvailable = t.isEnabled === true || t.isEnabled === 'true' || t.isAvailable === true;
                
                // 根據配料名稱給予不同圖示
                const icon = t.name.includes('珍珠') ? 'grain' : (t.name.includes('椰果') ? 'bakery_dining' : 'layers');
                
                return `
                <div class="flex items-center justify-between group">
                    <div class="flex items-center gap-4">
                        <div class="w-10 h-10 rounded-xl bg-orange-50 dark:bg-orange-900/20 flex items-center justify-center text-primary shadow-sm border border-orange-100/50">
                            <span class="material-symbols-outlined text-xl">${icon}</span>
                        </div>
                        <div>
                            <p class="font-black text-slate-800 dark:text-slate-200 text-sm">${t.name}</p>
                            <p class="text-[10px] ${isAvailable ? 'text-green-500' : 'text-red-400'} font-bold transition-colors">
                                ${isAvailable ? '供應中' : '缺貨中'}
                            </p>
                        </div>
                    </div>
                    <button 
                        data-topping-id="${tid}" 
                        data-topping-status="${isAvailable ? 'on' : 'off'}"
                        class="quick-toggle-btn w-12 h-6 rounded-full relative transition-all duration-300 ${isAvailable ? 'bg-primary shadow-lg shadow-primary/20' : 'bg-slate-200 dark:bg-slate-700'}"
                        aria-label="切換供應狀態"
                    >
                        <span class="absolute top-1 w-4 h-4 bg-white rounded-full shadow-md transition-all duration-300 ${isAvailable ? 'right-1' : 'left-1'}"></span>
                    </button>
                </div>`;
            }).join('');

            attachToggleEvents();
        } catch (err) {
            console.error('[QuickInventory] 載入失敗:', err);
            listContainer.innerHTML = '<p class="text-xs text-red-400 text-center py-4">數據載入失敗</p>';
        }
    }

    function attachToggleEvents() {
        listContainer.querySelectorAll('.quick-toggle-btn').forEach(btn => {
            btn.onclick = async (e) => {
                e.preventDefault();
                const id = btn.getAttribute('data-topping-id');
                const isCurrentlyOn = btn.getAttribute('data-topping-status') === 'on';
                const nextOn = !isCurrentlyOn;

                // 1. 立即更新 UI (樂觀更新)
                btn.setAttribute('data-topping-status', nextOn ? 'on' : 'off');
                btn.className = `quick-toggle-btn w-12 h-6 rounded-full relative transition-all duration-300 ${nextOn ? 'bg-primary shadow-lg shadow-primary/20' : 'bg-slate-200 dark:bg-slate-700'}`;
                btn.querySelector('span').className = `absolute top-1 w-4 h-4 bg-white rounded-full shadow-md transition-all duration-300 ${nextOn ? 'right-1' : 'left-1'}`;
                
                const statusLabel = btn.closest('.group').querySelector('p:last-child');
                if (statusLabel) {
                    statusLabel.textContent = nextOn ? '供應中' : '缺貨中';
                    statusLabel.className = `text-[10px] ${nextOn ? 'text-green-500' : 'text-red-400'} font-bold transition-colors`;
                }

                try {
                    // 2. 呼叫 API
                    await window.StoreAPI.toggleToppingSupply(id, nextOn);
                    window.StoreAPI.showToast(`${nextOn ? '已恢復供應' : '已設為缺貨'}`, 'success');
                } catch (err) {
                    window.StoreAPI.showToast('更新失敗: ' + err.message, 'error');
                    loadQuickToppings(); // 失敗則重刷恢復原狀
                }
            };
        });
    }

    // 初始化與定期同步
    loadQuickToppings();
    setInterval(loadQuickToppings, 60000); // 每分鐘同步一次狀態
})();
