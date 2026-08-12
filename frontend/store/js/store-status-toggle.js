/**
 * store-status-toggle.js — 營業狀態即時切換 (整合 API)
 */
(() => {
    const toggleButton = document.getElementById("storeStatusToggle");

    if (!toggleButton) return;

    toggleButton.addEventListener("click", async () => {
        // 1. 取得目前狀態
        const currentStatus = toggleButton.getAttribute("data-status");
        const isCurrentlyOpen = (currentStatus === "active" || currentStatus === "open");
        
        // 2. 準備新狀態：營業中(active) ↔ 休息中(closed)
        const newStatus = isCurrentlyOpen ? "closed" : "active";
        
        // 3. 視覺反饋
        if (window.updateGlobalStatusUI) {
            window.updateGlobalStatusUI(newStatus);
        }

        try {
            // 4. 呼叫後端 API：這裡應確保 StoreAPI 傳送的是正確的字串
            await window.StoreAPI.updateStatus(newStatus);
            
            // 5. 提示使用者
            const isNowOpen = newStatus === "active";
            const msg = isNowOpen ? "門市已開啟接單" : "門市已暫停接單";
            window.StoreAPI.showToast(msg, isNowOpen ? "success" : "warning");
            
        } catch (err) {
            console.error("[StatusToggle] 更新狀態失敗:", err);
            window.StoreAPI.showToast("狀態更新失敗: " + err.message, "error");
            
            // 6. 失敗時恢復原狀
            if (window.updateGlobalStatusUI) {
                window.updateGlobalStatusUI(currentStatus);
            }
        }
    });
})();