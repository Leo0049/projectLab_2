/**
 * page-loading.js — 頁面載入 spinner overlay
 * 使用方式：
 *   1. 在 API script 之前載入此檔案
 *   2. API 完成後呼叫 window.PageLoading.done()
 */
(function () {
    var overlay;

    function showLoader() {
        overlay = document.createElement('div');
        overlay.id = 'pageLoadingOverlay';
        overlay.style.cssText = [
            'position:fixed',
            'top:0',
            'left:256px',
            'right:0',
            'bottom:0',
            'display:flex',
            'flex-direction:column',
            'align-items:center',
            'justify-content:center',
            'background:rgba(248,246,246,0.9)',
            'z-index:200',
            'backdrop-filter:blur(3px)',
            'transition:opacity 0.25s ease',
        ].join(';');

        overlay.innerHTML =
            '<span class="material-symbols-outlined" style="font-size:2.5rem;color:#ec5b13;animation:pl-spin 0.8s linear infinite">sync</span>' +
            '<p style="margin-top:0.75rem;font-size:0.875rem;color:#94a3b8;font-weight:600;font-family:inherit">載入中...</p>';

        if (!document.getElementById('page-loading-style')) {
            var style = document.createElement('style');
            style.id = 'page-loading-style';
            style.textContent = '@keyframes pl-spin{from{transform:rotate(0deg)}to{transform:rotate(-360deg)}}';
            document.head.appendChild(style);
        }

        document.body.appendChild(overlay);
    }

    window.PageLoading = {
        done: function () {
            // 顯示內容
            var container = document.getElementById('mainContentFade');
            if (container) container.classList.remove('opacity-0');

            // 淡出 overlay
            if (overlay) {
                overlay.style.opacity = '0';
                var el = overlay;
                setTimeout(function () { if (el.parentNode) el.parentNode.removeChild(el); }, 260);
            }
        }
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', showLoader);
    } else {
        showLoader();
    }
})();
