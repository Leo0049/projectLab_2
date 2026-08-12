/**
 * api-inventory.js — 飲品與配料庫存管理
 * Stale-while-revalidate：先顯示持久快取，背景靜默更新
 */
(() => {
    const init = () => {
        const isDrinksPage = !!document.getElementById('menuDrinksRoot');
        const drinkSearch  = document.querySelector('[data-menu-drinks-search]');
        const toppingSearch = document.getElementById('toppingSearch');
        const categoryFilterContainer = document.querySelector('[data-category-filter]');

        const drinksContainer   = document.getElementById('drinksContentArea');
        const toppingsContainer = document.getElementById('toppingsContentArea');
        const toppingsTableBody = document.getElementById('toppingsTableBody');

        let allData     = [];
        let currentFilter = '全部';

        // ── 飲品渲染 ────────────────────────────
        const renderDrinks = (filterText = '') => {
            if (!drinksContainer) return;
            const normalizedFilter = filterText.trim().toLowerCase();
            drinksContainer.innerHTML = '';

            const categories = [...new Set(allData.map(d => d.categoryName))];
            const gridWrapper = document.createElement('div');
            gridWrapper.className = 'grid grid-cols-1 lg:grid-cols-2 gap-8 items-start';

            categories.forEach(catName => {
                const drinksInCat = allData.filter(d =>
                    d.categoryName === catName &&
                    (currentFilter === '全部' || d.categoryName === currentFilter) &&
                    (d.drinkName || '').toLowerCase().includes(normalizedFilter)
                );
                if (drinksInCat.length === 0) return;

                const section = document.createElement('section');
                section.className = 'bg-white dark:bg-slate-900 rounded-xl border border-primary/10 shadow-sm overflow-hidden';

                let icon = 'local_bar';
                if (catName.includes('茶')) icon = 'eco';
                if (catName.includes('奶')) icon = 'opacity';
                if (catName.includes('限定')) icon = 'temp_preferences_custom';

                section.innerHTML = `
                    <div class="px-4 md:px-6 py-3 md:py-4 bg-primary/5 border-b border-primary/10 flex items-center justify-between">
                        <div class="flex items-center gap-2 md:gap-3">
                            <span class="material-symbols-outlined text-primary text-lg md:text-xl">${icon}</span>
                            <h3 class="text-base md:text-lg font-bold">${catName}</h3>
                        </div>
                        <span class="text-[10px] md:text-xs font-medium text-slate-500 px-2 py-0.5 md:py-1 bg-slate-100 dark:bg-slate-800 rounded">${drinksInCat.length} 項</span>
                    </div>
                    <div class="overflow-x-auto">
                        <table class="w-full text-left" style="table-layout:fixed">
                            <colgroup><col style="width:42%"><col style="width:18%"><col style="width:40%"></colgroup>
                            <thead class="bg-slate-50 dark:bg-slate-800/50">
                                <tr>
                                    <th class="px-3 md:px-6 py-3 text-[10px] md:text-xs font-bold uppercase text-slate-500">品項名稱</th>
                                    <th class="px-2 py-3 text-[10px] md:text-xs font-bold uppercase text-slate-500 text-center">價格</th>
                                    <th class="px-3 md:px-6 py-3 text-[10px] md:text-xs font-bold uppercase text-slate-500 text-right md:text-left">供應狀態</th>
                                </tr>
                            </thead>
                            <tbody class="divide-y divide-primary/5"></tbody>
                        </table>
                    </div>
                `;

                const tbody = section.querySelector('tbody');
                drinksInCat.forEach(drink => {
                    const tr = document.createElement('tr');
                    const isAvailable = drink.isAvailable !== false;
                    tr.innerHTML = `
                        <td class="px-3 md:px-6 py-3 md:py-4 align-middle">
                            <span class="text-xs md:text-sm font-semibold text-slate-700 dark:text-slate-200 truncate block">${drink.drinkName}</span>
                        </td>
                        <td class="px-2 py-3 md:py-4 text-center align-middle">
                            <span class="text-[10px] md:text-sm text-primary font-bold">$${drink.price}</span>
                        </td>
                        <td class="px-3 md:px-6 py-3 md:py-4 align-middle text-right md:text-left">
                            <select class="supply-select w-full max-w-[90px] md:max-w-[160px] text-[10px] md:text-xs font-bold rounded-lg focus:ring-primary focus:border-primary transition-colors py-1 px-1.5">
                                <option value="true" ${isAvailable ? 'selected' : ''}>供應中</option>
                                <option value="false" ${!isAvailable ? 'selected' : ''}>今日已售完</option>
                            </select>
                        </td>
                    `;
                    const select = tr.querySelector('.supply-select');
                    updateDrinkSelectStyle(select, isAvailable);
                    select.addEventListener('change', async () => {
                        const newStatus = select.value === 'true';
                        try {
                            await window.StoreAPI.toggleDrinkSupply(drink.drinkId, newStatus);
                            drink.isAvailable = newStatus;
                            updateDrinkSelectStyle(select, newStatus);
                        } catch (err) {
                            window.StoreAPI.showToast('切換失敗: ' + err.message, 'error');
                            select.value = String(!newStatus);
                        }
                    });
                    tbody.appendChild(tr);
                });
                gridWrapper.appendChild(section);
            });
            drinksContainer.appendChild(gridWrapper);
            drinksContainer.classList.remove('opacity-0');
            
            // 資料填入後移除外層隱藏狀態
            const root = document.getElementById('mainContentFade');
            if (root) root.classList.remove('opacity-0');
        };

        const updateDrinkSelectStyle = (select, isAvailable) => {
            if (isAvailable) {
                select.className = 'supply-select w-full max-w-[90px] md:max-w-[160px] bg-emerald-50 dark:bg-emerald-900/10 border-emerald-200 dark:border-emerald-800/50 text-emerald-700 dark:text-emerald-400 text-[10px] md:text-xs font-bold rounded-lg focus:ring-primary focus:border-primary py-1 px-1.5';
            } else {
                select.className = 'supply-select w-full max-w-[90px] md:max-w-[160px] bg-rose-50 dark:bg-rose-900/10 border-rose-200 dark:border-rose-800/50 text-rose-700 dark:text-rose-400 text-[10px] md:text-xs font-bold rounded-lg focus:ring-primary focus:border-primary py-1 px-1.5';
            }
        };

        const buildCategoryFilter = (data) => {
            if (!categoryFilterContainer) return;
            const categories = ['全部', ...new Set(data.map(d => d.categoryName))];
            categoryFilterContainer.innerHTML = '<span class="text-xs font-bold text-slate-400 mr-2 uppercase tracking-wider">分類篩選:</span>';
            categories.forEach(cat => {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.textContent = cat;
                btn.className = `px-4 py-1.5 rounded-full text-xs font-bold transition-all ${cat === '全部' ? 'bg-primary text-white shadow-sm shadow-primary/20' : 'bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-primary/10 hover:text-primary'}`;
                btn.addEventListener('click', () => {
                    currentFilter = cat;
                    categoryFilterContainer.querySelectorAll('button').forEach(b => {
                        b.className = 'px-4 py-1.5 rounded-full bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 text-xs font-bold hover:bg-primary/10 hover:text-primary transition-all';
                    });
                    btn.className = 'px-4 py-1.5 rounded-full bg-primary text-white text-xs font-bold transition-all shadow-sm shadow-primary/20';
                    renderDrinks(drinkSearch ? drinkSearch.value : '');
                });
                categoryFilterContainer.appendChild(btn);
            });
        };

        // ── 配料渲染 ────────────────────────────
        const renderToppings = (filterText = '') => {
            if (!toppingsTableBody) return;
            const normalizedFilter = filterText.trim().toLowerCase();

            const fragment = document.createDocumentFragment();

            allData.filter(t => (t.name || '').toLowerCase().includes(normalizedFilter)).forEach(topping => {
                const tr = document.createElement('tr');
                tr.className = 'hover:bg-slate-50/50 dark:hover:bg-slate-800/30 transition-colors';
                const isAvailable = topping.isEnabled !== false;
                tr.innerHTML = `
                    <td class="px-3 md:px-6 py-3 md:py-5 font-semibold text-slate-900 dark:text-white">${topping.name}</td>
                    <td class="px-3 md:px-6 py-3 md:py-5 font-medium">$${topping.price}</td>
                    <td class="px-3 md:px-6 py-3 md:py-5">
                        <div class="flex items-center gap-2 md:gap-3">
                            <div class="topping-toggle w-11 h-6 rounded-full relative cursor-pointer transition-colors duration-200 ease-out ${isAvailable ? 'bg-primary' : 'bg-slate-300'}"
                                 role="switch" aria-checked="${isAvailable}">
                                <div class="absolute left-1 top-1 w-4 h-4 bg-white rounded-full shadow-sm transition-transform duration-200 ease-out ${isAvailable ? 'translate-x-5' : 'translate-x-0'}"></div>
                            </div>
                            <p class="status-label text-xs font-bold whitespace-nowrap ${isAvailable ? 'text-green-500' : 'text-slate-400'}">
                                ${isAvailable ? '供應中' : '已停供'}
                            </p>
                        </div>
                    </td>
                    <td class="hidden md:table-cell px-6 py-5 text-slate-600 dark:text-slate-400">${topping.description || '總部提供之優質配料'}</td>
                `;
                const toggle = tr.querySelector('.topping-toggle');
                const dot    = toggle.querySelector('div');
                const label  = tr.querySelector('.status-label');
                toggle.addEventListener('click', async () => {
                    const currentStatus = toggle.getAttribute('aria-checked') === 'true';
                    const newStatus = !currentStatus;
                    try {
                        await window.StoreAPI.toggleToppingSupply(topping.brandToppingId, newStatus);
                        toggle.setAttribute('aria-checked', String(newStatus));
                        toggle.className = `topping-toggle w-11 h-6 rounded-full relative cursor-pointer transition-colors duration-200 ease-out ${newStatus ? 'bg-primary' : 'bg-slate-300'}`;
                        dot.className    = `absolute left-1 top-1 w-4 h-4 bg-white rounded-full shadow-sm transition-transform duration-200 ease-out ${newStatus ? 'translate-x-5' : 'translate-x-0'}`;
                        label.textContent = newStatus ? '供應中' : '已停供';
                        label.className   = `status-label text-xs font-bold whitespace-nowrap w-16 ${newStatus ? 'text-green-500' : 'text-slate-400'}`;
                        topping.isEnabled = newStatus;
                    } catch (err) {
                        window.StoreAPI.showToast('切換失敗: ' + err.message, 'error');
                    }
                });
                fragment.appendChild(tr);
            });

            // 原子替換：一次性換掉所有 row，避免清空後重填造成閃爍
            toppingsTableBody.replaceChildren(fragment);
            if (toppingsContainer) toppingsContainer.classList.remove('opacity-0');

            // 資料填入後移除外層隱藏狀態
            const root = document.getElementById('mainContentFade');
            if (root) root.classList.remove('opacity-0');
        };

        // ── 搜尋監聽 ────────────────────────────
        if (drinkSearch)   drinkSearch.addEventListener('input',   e => renderDrinks(e.target.value));
        if (toppingSearch) toppingSearch.addEventListener('input', e => renderToppings(e.target.value));

        // ── SWR 資料載入 ────────────────────────
        if (isDrinksPage) {
            // 1. 持久快取 → 立即顯示
            const snap = window.StoreAPI.getMenuDrinksSnapshot();
            if (snap && snap.length) {
                allData = snap;
                buildCategoryFilter(allData);
                renderDrinks();
            }
            // 2. 背景靜默刷新
            window.StoreAPI.refreshMenuDrinks().then(fresh => {
                allData = fresh;
                buildCategoryFilter(fresh);
                renderDrinks(drinkSearch ? drinkSearch.value : '');
                window.StoreAPI.saveMenuDrinksSnapshot(fresh);
            }).catch(err => {
                if (!snap || !snap.length) {
                    console.error('[Inventory] 飲品載入失敗:', err);
                    if (drinksContainer) drinksContainer.classList.remove('opacity-0');
                }
            });
        } else {
            // 配料頁
            const snap = window.StoreAPI.getMenuToppingsSnapshot();
            if (snap && snap.length) {
                allData = snap;
                renderToppings();
            }
            window.StoreAPI.refreshMenuToppings().then(fresh => {
                allData = fresh;
                renderToppings(toppingSearch ? toppingSearch.value : '');
                window.StoreAPI.saveMenuToppingsSnapshot(fresh);
            }).catch(err => {
                if (!snap || !snap.length) {
                    console.error('[Inventory] 配料載入失敗:', err);
                    if (toppingsContainer) toppingsContainer.classList.remove('opacity-0');
                }
            });
        }
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
