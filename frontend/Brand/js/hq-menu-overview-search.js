(function () {
    "use strict";

    function normalize(text) {
        return String(text || "").trim().toLowerCase();
    }

    function getDrinkNameFromCard(cardEl) {
        if (!cardEl) return "";
        var nameEl = cardEl.querySelector("p.font-bold");
        return nameEl ? (nameEl.textContent || "") : "";
    }

    function getTabsRoot() {
        return document.querySelector('[data-hq-menu-category-tabs]');
    }

    function getTabButton(key) {
        var tabsRoot = getTabsRoot();
        if (!tabsRoot) return null;
        return tabsRoot.querySelector('button[data-hq-menu-category-tab="' + key + '"]');
    }

    function getSelectedTabKey() {
        var tabsRoot = getTabsRoot();
        if (!tabsRoot) return '';
        var selected = tabsRoot.querySelector('button[data-hq-menu-category-tab][aria-selected="true"]');
        return selected ? (selected.getAttribute('data-hq-menu-category-tab') || '').trim() : '';
    }

    function clickTab(key) {
        var btn = getTabButton(key);
        if (btn && btn instanceof HTMLButtonElement) btn.click();
    }

    function applyFilter(query) {
        var q = normalize(query);
        var panels = document.querySelectorAll('section[data-hq-menu-category-panel]');

        // Hide/show cards first
        panels.forEach(function (panel) {
            var cards = panel.querySelectorAll('.group');
            cards.forEach(function (card) {
                var name = normalize(getDrinkNameFromCard(card));
                var match = !q || name.indexOf(q) !== -1;
                card.classList.toggle('hidden', !match);
            });
        });

        // Then hide panels that have no visible cards when searching
        panels.forEach(function (panel) {
            if (!q) {
                panel.classList.remove('hidden');
                return;
            }

            var anyVisible = false;
            var cards = panel.querySelectorAll('.group');
            cards.forEach(function (card) {
                if (!card.classList.contains('hidden')) anyVisible = true;
            });

            panel.classList.toggle('hidden', !anyVisible);
        });
    }

    function init() {
        var input = document.querySelector("input[data-hq-menu-search]");
        if (!(input instanceof HTMLInputElement)) return;

        var lastTabKeyBeforeSearch = '';
        var programmaticTabClick = false;

        input.addEventListener('input', function () {
            var value = input.value;
            var q = normalize(value);

            if (q) {
                if (!lastTabKeyBeforeSearch) lastTabKeyBeforeSearch = getSelectedTabKey() || '全部';
                // 搜尋時切到「全部」，由搜尋邏輯決定要顯示哪些分類
                programmaticTabClick = true;
                clickTab('全部');
                programmaticTabClick = false;
                applyFilter(value);
                return;
            }

            // 清空搜尋：先清掉所有 card 的 hidden，再恢復原本 tab 顯示狀態
            applyFilter('');
            if (lastTabKeyBeforeSearch) {
                programmaticTabClick = true;
                clickTab(lastTabKeyBeforeSearch);
                programmaticTabClick = false;
            }
            lastTabKeyBeforeSearch = '';
        });

        var tabsRoot = getTabsRoot();
        if (tabsRoot) {
            tabsRoot.addEventListener('click', function (e) {
                if (programmaticTabClick) return;
                var q = normalize(input.value);
                if (!q) return;

                var target = e.target;
                if (!(target instanceof Element)) return;

                var btn = target.closest('button[data-hq-menu-category-tab]');
                if (!btn) return;

                // 使用者在搜尋中點 tab：維持搜尋視圖（回到「全部」＋依結果隱藏分類）
                programmaticTabClick = true;
                clickTab('全部');
                programmaticTabClick = false;
                applyFilter(input.value);
            });
        }

        // initial
        var initialQ = normalize(input.value);
        if (initialQ) {
            lastTabKeyBeforeSearch = getSelectedTabKey() || '全部';
            programmaticTabClick = true;
            clickTab('全部');
            programmaticTabClick = false;
            applyFilter(input.value);
        } else {
            applyFilter('');
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();
