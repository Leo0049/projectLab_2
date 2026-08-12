(function () {
    const createModal = document.querySelector('[data-hq-category-create-modal]');
    const editModal = document.querySelector('[data-hq-category-edit-modal]');
    const deleteModal = document.querySelector('[data-hq-category-delete-modal]');
    const existsModal = document.querySelector('[data-hq-category-exists-modal]');
    if (!createModal || !editModal || !deleteModal) return;

    const createInput = createModal.querySelector('[data-hq-category-create-input]');
    const createSubmit = createModal.querySelector('[data-hq-category-create-submit]');
    const createRegionNorth = createModal.querySelector('[data-hq-category-create-region-north]');
    const createRegionCentral = createModal.querySelector('[data-hq-category-create-region-central]');
    const createRegionSouth = createModal.querySelector('[data-hq-category-create-region-south]');
    const editInput = editModal.querySelector('[data-hq-category-edit-input]');
    const editSave = editModal.querySelector('[data-hq-category-edit-save]');
    const editDelete = editModal.querySelector('[data-hq-category-edit-delete]');
    const editRegionNorth = editModal.querySelector('[data-hq-category-edit-region-north]');
    const editRegionCentral = editModal.querySelector('[data-hq-category-edit-region-central]');
    const editRegionSouth = editModal.querySelector('[data-hq-category-edit-region-south]');

    const deleteName = deleteModal.querySelector('[data-hq-category-delete-name]');
    const deleteConfirm = deleteModal.querySelector('[data-hq-category-delete-confirm]');

    const existsMessage = existsModal ? existsModal.querySelector('[data-hq-category-exists-message]') : null;
    const existsConfirm = existsModal ? existsModal.querySelector('[data-hq-category-exists-confirm]') : null;

    if (!(createInput instanceof HTMLInputElement)) return;
    if (!(createSubmit instanceof HTMLButtonElement)) return;
    if (!(createRegionNorth instanceof HTMLInputElement)) return;
    if (!(createRegionCentral instanceof HTMLInputElement)) return;
    if (!(createRegionSouth instanceof HTMLInputElement)) return;
    if (!(editInput instanceof HTMLInputElement)) return;
    if (!(editSave instanceof HTMLButtonElement)) return;
    if (!(editDelete instanceof HTMLButtonElement)) return;
    if (!(editRegionNorth instanceof HTMLInputElement)) return;
    if (!(editRegionCentral instanceof HTMLInputElement)) return;
    if (!(editRegionSouth instanceof HTMLInputElement)) return;
    if (!(deleteName instanceof HTMLElement)) return;
    if (!(deleteConfirm instanceof HTMLButtonElement)) return;

    let lastActiveElement = null;
    let currentEditKey = '';
    let pendingDeleteKey = '';
    let existsReturnFocusEl = null;

    function hasExistsModal() {
        return existsModal instanceof HTMLElement && existsConfirm instanceof HTMLButtonElement;
    }

    function isExistsModalOpen() {
        if (!(existsModal instanceof HTMLElement)) return false;
        return !existsModal.classList.contains('hidden');
    }

    function openExistsModal(options) {
        if (!hasExistsModal()) {
            alert('分類名稱已存在，請換一個。');
            return;
        }

        const message = typeof options?.message === 'string' && options.message.trim()
            ? options.message.trim()
            : '此分類名稱已存在，請換一個。';

        if (existsMessage instanceof HTMLElement) {
            existsMessage.textContent = message;
        }

        existsReturnFocusEl = options?.returnFocus instanceof HTMLElement ? options.returnFocus : null;

        existsModal.classList.remove('hidden');
        existsModal.setAttribute('aria-hidden', 'false');
        existsConfirm.focus();
    }

    function closeExistsModal() {
        if (!(existsModal instanceof HTMLElement)) return;

        existsModal.classList.add('hidden');
        existsModal.setAttribute('aria-hidden', 'true');

        if (existsReturnFocusEl) {
            existsReturnFocusEl.focus();
            if (existsReturnFocusEl instanceof HTMLInputElement) existsReturnFocusEl.select();
        }

        existsReturnFocusEl = null;
    }

    function escapeKeyForSelector(key) {
        const raw = String(key ?? '');
        if (typeof CSS !== 'undefined' && typeof CSS.escape === 'function') return CSS.escape(raw);
        return raw.replace(/"/g, '\\"');
    }

    function getTabsRoot() {
        return document.querySelector('[data-hq-menu-category-tabs]');
    }

    function getGridRoot() {
        return document.querySelector('[data-hq-menu-category-grid]');
    }

    function getCategoryKeys() {
        const tabsRoot = getTabsRoot();
        if (!tabsRoot) return [];

        return Array.from(tabsRoot.querySelectorAll('button[data-hq-menu-category-tab]'))
            .map((b) => (b.getAttribute('data-hq-menu-category-tab') || '').trim())
            .filter(Boolean)
            .filter((k) => k !== '全部');
    }

    function categoryExists(key) {
        const normalized = (key || '').trim();
        if (!normalized) return false;

        const keys = getCategoryKeys();
        return keys.some((k) => k === normalized);
    }

    function openEditModal(triggerElement) {
        lastActiveElement = document.activeElement instanceof HTMLElement ? document.activeElement : null;

        const key = triggerElement instanceof Element
            ? (triggerElement.getAttribute('data-hq-category-key') || '').trim()
            : '';
        if (!key) return;

        currentEditKey = key;
        editInput.value = key;

        const panel = getCategoryPanelEl(key);
        const region = getRegionDeltasFromPanel(panel);
        editRegionNorth.value = String(region.north);
        editRegionCentral.value = String(region.central);
        editRegionSouth.value = String(region.south);

        editModal.classList.remove('hidden');
        editModal.setAttribute('aria-hidden', 'false');

        editInput.focus();
        editInput.select();
    }

    function openCreateModal() {
        lastActiveElement = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        createModal.classList.remove('hidden');
        createModal.setAttribute('aria-hidden', 'false');
        createRegionNorth.value = '0';
        createRegionCentral.value = '0';
        createRegionSouth.value = '0';
        createInput.focus();
        createInput.select();
    }

    function closeCreateModal() {
        createModal.classList.add('hidden');
        createModal.setAttribute('aria-hidden', 'true');

        if (lastActiveElement) lastActiveElement.focus();
    }

    function closeEditModal() {
        editModal.classList.add('hidden');
        editModal.setAttribute('aria-hidden', 'true');
        currentEditKey = '';

        if (lastActiveElement) lastActiveElement.focus();
    }

    function openDeleteModalForCategory(key) {
        const k = (key || '').trim();
        if (!k) return;

        pendingDeleteKey = k;
        deleteName.textContent = k;

        deleteModal.classList.remove('hidden');
        deleteModal.setAttribute('aria-hidden', 'false');

        deleteConfirm.focus();
    }

    function closeDeleteModal() {
        deleteModal.classList.add('hidden');
        deleteModal.setAttribute('aria-hidden', 'true');
        pendingDeleteKey = '';

        if (isEditModalOpen()) {
            editDelete.focus();
        }
    }

    function isCreateModalOpen() {
        return !createModal.classList.contains('hidden');
    }

    function isEditModalOpen() {
        return !editModal.classList.contains('hidden');
    }

    function isDeleteModalOpen() {
        return !deleteModal.classList.contains('hidden');
    }

    function createTabButton(key) {
        const button = document.createElement('button');
        button.type = 'button';
        button.setAttribute('role', 'tab');
        button.setAttribute('data-hq-menu-category-tab', key);
        button.setAttribute('aria-selected', 'false');
        button.setAttribute('tabindex', '-1');
        button.className = 'px-6 py-3 border-b-2 border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200 font-medium text-sm transition-colors';
        button.textContent = key;
        return button;
    }

    function createPanelSection(key) {
        const section = document.createElement('section');
        section.setAttribute('data-hq-menu-category-panel', key);
        section.className = 'bg-white dark:bg-zinc-900 rounded-xl p-6 shadow-sm border border-slate-100 dark:border-zinc-800';

        section.innerHTML = `
            <div class="flex items-center justify-between mb-6">
                <h3 class="text-lg font-bold flex items-center gap-2">
                    <span class="w-1.5 h-6 bg-primary rounded-full"></span>
                    <span data-hq-category-title></span>
                </h3>
                <button type="button" data-hq-category-edit-open data-hq-category-key="${key}" class="text-primary text-sm font-bold flex items-center hover:underline">編輯分類</button>
            </div>
            <div class="space-y-4"></div>
        `;

        const title = section.querySelector('[data-hq-category-title]');
        if (title) title.textContent = key;

        return section;
    }

    function parseDelta(raw) {
        if (raw == null) return 0;
        const str = String(raw).trim();
        if (!str) return 0;
        const num = Number(str);
        return Number.isFinite(num) ? num : 0;
    }

    function getCategoryPanelEl(key) {
        const k = (key || '').trim();
        if (!k) return null;
        const sel = escapeKeyForSelector(k);
        const panel = document.querySelector(`[data-hq-menu-category-panel="${sel}"]`);
        return panel instanceof HTMLElement ? panel : null;
    }

    function getRegionDeltasFromPanel(panelEl) {
        const north = panelEl ? parseDelta(panelEl.getAttribute('data-hq-category-region-north') || '') : 0;
        const central = panelEl ? parseDelta(panelEl.getAttribute('data-hq-category-region-central') || '') : 0;
        const south = panelEl ? parseDelta(panelEl.getAttribute('data-hq-category-region-south') || '') : 0;
        return { north, central, south };
    }

    function setRegionDeltasOnPanel(panelEl, region) {
        if (!panelEl) return;
        const north = parseDelta(region?.north);
        const central = parseDelta(region?.central);
        const south = parseDelta(region?.south);

        panelEl.setAttribute('data-hq-category-region-north', String(north));
        panelEl.setAttribute('data-hq-category-region-central', String(central));
        panelEl.setAttribute('data-hq-category-region-south', String(south));
    }

    function addCategory(key, region) {
        const normalized = (key || '').trim();
        if (!normalized) return false;

        if (normalized === '全部') {
            alert('分類名稱不可為「全部」。');
            return false;
        }

        if (/["<>]/.test(normalized)) {
            alert('分類名稱不可包含 "、<、>');
            return false;
        }

        if (categoryExists(normalized)) {
            openExistsModal({ returnFocus: createInput });
            return false;
        }

        const tabsRoot = getTabsRoot();
        const gridRoot = getGridRoot();
        if (!tabsRoot || !gridRoot) return false;

        tabsRoot.appendChild(createTabButton(normalized));
        const panel = createPanelSection(normalized);
        setRegionDeltasOnPanel(panel, region);
        gridRoot.appendChild(panel);
        addCategoryOptionToSelects(normalized);

        // 重新套用目前 tab，確保面板顯示狀態正確
        const selectedTab = tabsRoot.querySelector('button[data-hq-menu-category-tab][aria-selected="true"]');
        if (selectedTab instanceof HTMLButtonElement) selectedTab.click();

        return true;
    }

    function renameCategory(oldKey, newKey) {
        const oldK = (oldKey || '').trim();
        const newK = (newKey || '').trim();
        if (!oldK || !newK || oldK === newK) return;

        const oldKeySel = escapeKeyForSelector(oldK);
        const tab = document.querySelector(`button[data-hq-menu-category-tab="${oldKeySel}"]`);
        if (tab) {
            tab.setAttribute('data-hq-menu-category-tab', newK);
            tab.textContent = newK;
        }

        const panel = document.querySelector(`[data-hq-menu-category-panel="${oldKeySel}"]`);
        if (panel) {
            panel.setAttribute('data-hq-menu-category-panel', newK);
            const title = panel.querySelector('[data-hq-category-title]');
            if (title) title.textContent = newK;

            const editBtn = panel.querySelector('[data-hq-category-edit-open]');
            if (editBtn) editBtn.setAttribute('data-hq-category-key', newK);
        }

        renameCategoryOptionInSelects(oldK, newK);

        // 若目前正在該分類頁籤，維持選取（透過點擊新 tab 觸發 tabs 腳本重新套用）
        const tabsRoot = getTabsRoot();
        const selected = tabsRoot
            ? tabsRoot.querySelector('button[data-hq-menu-category-tab][aria-selected="true"]')
            : null;

        const selectedKey = (selected?.getAttribute('data-hq-menu-category-tab') || '').trim();
        if (selectedKey === newK) {
            // already ok
        } else if (selectedKey === oldK) {
            const newTab = tabsRoot
                ? tabsRoot.querySelector(`button[data-hq-menu-category-tab="${escapeKeyForSelector(newK)}"]`)
                : null;
            if (newTab instanceof HTMLButtonElement) newTab.click();
        }
    }

    function deleteCategory(key) {
        const k = (key || '').trim();
        if (!k) return;

        const tabsRoot = getTabsRoot();
        const oldKeySel = escapeKeyForSelector(k);

        const tab = document.querySelector(`button[data-hq-menu-category-tab="${oldKeySel}"]`);
        const wasSelected = tab?.getAttribute('aria-selected') === 'true';
        tab?.remove();

        const panel = document.querySelector(`[data-hq-menu-category-panel="${oldKeySel}"]`);
        panel?.remove();

        removeCategoryOptionFromSelects(k);

        if (wasSelected && tabsRoot) {
            const allTab = tabsRoot.querySelector('button[data-hq-menu-category-tab="全部"]');
            if (allTab instanceof HTMLButtonElement) allTab.click();
        }
    }

    function addCategoryOptionToSelects(key) {
        const selects = Array.from(document.querySelectorAll('select[data-hq-drink-category]'))
            .filter((s) => s instanceof HTMLSelectElement);

        for (const select of selects) {
            const exists = Array.from(select.options).some((o) => o.value === key);
            if (exists) continue;

            const option = document.createElement('option');
            option.value = key;
            option.textContent = key;
            select.appendChild(option);
        }
    }

    function renameCategoryOptionInSelects(oldKey, newKey) {
        const selects = Array.from(document.querySelectorAll('select[data-hq-drink-category]'))
            .filter((s) => s instanceof HTMLSelectElement);

        for (const select of selects) {
            for (const option of Array.from(select.options)) {
                if (option.value !== oldKey) continue;
                option.value = newKey;
                option.textContent = newKey;
            }
        }
    }

    function removeCategoryOptionFromSelects(key) {
        const selects = Array.from(document.querySelectorAll('select[data-hq-drink-category]'))
            .filter((s) => s instanceof HTMLSelectElement);

        for (const select of selects) {
            const wasSelected = select.value === key;
            for (const option of Array.from(select.options)) {
                if (option.value !== key) continue;
                option.remove();
            }

            // 若剛好選到被刪除的分類，退回第一個選項
            if (wasSelected) {
                const first = select.options[0];
                if (first) select.value = first.value;
            }
        }
    }

    // Open triggers (event delegation to cover dynamically added section buttons)
    document.addEventListener('click', (event) => {
        const el = event.target instanceof Element ? event.target : null;
        if (!el) return;

        const createOpen = el.closest('[data-hq-category-create-open]');
        if (createOpen) {
            openCreateModal();
            return;
        }

        const editOpen = el.closest('[data-hq-category-edit-open]');
        if (editOpen) openEditModal(editOpen);
    });

    function wireModalClose(modalEl, closeFn) {
        modalEl.addEventListener('click', (event) => {
            const target = event.target instanceof Element ? event.target : null;
            if (!target) return;

            if (target.closest('[data-hq-modal-close]')) {
                closeFn();
                return;
            }

            if (target.hasAttribute('data-hq-modal-backdrop')) {
                closeFn();
            }
        });
    }

    wireModalClose(createModal, closeCreateModal);
    wireModalClose(editModal, closeEditModal);
    wireModalClose(deleteModal, closeDeleteModal);
    if (hasExistsModal()) wireModalClose(existsModal, closeExistsModal);

    document.addEventListener('keydown', (event) => {
        if (event.key !== 'Escape') return;

        if (isExistsModalOpen()) {
            closeExistsModal();
            return;
        }

        if (isDeleteModalOpen()) {
            closeDeleteModal();
            return;
        }

        if (isCreateModalOpen()) closeCreateModal();
        if (isEditModalOpen()) closeEditModal();
    });

    function validateCategoryName(name, fallbackValue) {
        const normalized = (name || '').trim();
        if (!normalized) return { ok: false, value: fallbackValue, message: '請輸入分類名稱。' };
        if (normalized === '全部') return { ok: false, value: fallbackValue, message: '分類名稱不可為「全部」。' };
        if (/["><>]/.test(normalized)) return { ok: false, value: fallbackValue, message: '分類名稱不可包含 "、<、>' };
        return { ok: true, value: normalized };
    }

    function commitEditSave() {
        const oldKey = (currentEditKey || '').trim();
        if (!oldKey) return;

        const validation = validateCategoryName(editInput.value, oldKey);
        if (!validation.ok) {
            alert(validation.message);
            editInput.value = oldKey;
            editInput.focus();
            editInput.select();
            return;
        }

        const newKey = validation.value;
        const region = {
            north: editRegionNorth.value,
            central: editRegionCentral.value,
            south: editRegionSouth.value,
        };

        // 先拿到 panel（rename 前後都會是同一個 DOM 節點）
        const panelEl = getCategoryPanelEl(oldKey);

        if (newKey !== oldKey) {
            if (categoryExists(newKey)) {
                openExistsModal({ returnFocus: editInput });
                editInput.value = oldKey;
                return;
            }

            renameCategory(oldKey, newKey);
            currentEditKey = newKey;
            editInput.value = newKey;
        }

        setRegionDeltasOnPanel(panelEl, region);
        closeEditModal();
    }

    editSave.addEventListener('click', () => {
        commitEditSave();
    });

    editInput.addEventListener('keydown', (event) => {
        if (event.key !== 'Enter') return;
        commitEditSave();
        event.preventDefault();
    });

    editDelete.addEventListener('click', () => {
        const key = (currentEditKey || '').trim();
        if (!key) return;

        openDeleteModalForCategory(key);
    });

    deleteConfirm.addEventListener('click', () => {
        const key = (pendingDeleteKey || '').trim();
        if (!key) {
            closeDeleteModal();
            return;
        }

        deleteCategory(key);
        closeDeleteModal();
        closeEditModal();
    });

    createSubmit.addEventListener('click', () => {
        const value = createInput.value;
        const region = {
            north: createRegionNorth.value,
            central: createRegionCentral.value,
            south: createRegionSouth.value,
        };

        const ok = addCategory(value, region);
        if (!ok) {
            if (!isExistsModalOpen()) {
                createInput.focus();
                createInput.select();
            }
            return;
        }

        createInput.value = '';
        closeCreateModal();
    });

    createInput.addEventListener('keydown', (event) => {
        if (event.key !== 'Enter') return;
        createSubmit.click();
        event.preventDefault();
    });
})();
