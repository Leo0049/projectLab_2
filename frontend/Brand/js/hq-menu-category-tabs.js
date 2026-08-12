(function () {
    const tabsRoot = document.querySelector('[data-hq-menu-category-tabs]');
    if (!tabsRoot) return;

    const ACTIVE_CLASSES = ['border-primary', 'bg-primary/10', 'text-primary', 'font-bold'];
    const INACTIVE_CLASSES = ['border-transparent', 'bg-transparent', 'text-slate-500', 'font-medium'];
    const DRAGGING_CLASSES = ['bg-primary/15', 'text-primary', 'shadow-md', 'shadow-primary/10', 'scale-[1.02]', 'cursor-grabbing'];
    const DROP_TARGET_CLASSES = ['bg-slate-100', 'dark:bg-slate-800/80'];

    let draggedButton = null;
    let originalOrder = [];
    let didDrop = false;
    let suppressClickUntil = 0;
    let currentDropTarget = null;

    function getButtons() {
        return Array.from(
            tabsRoot.querySelectorAll('button[data-hq-menu-category-tab]')
        );
    }

    function getPanels() {
        return Array.from(
            document.querySelectorAll('[data-hq-menu-category-panel]')
        );
    }

    function getMovableButtons() {
        return getButtons().filter((button) => !isFixedButton(button));
    }

    function isFixedButton(button) {
        return !(button instanceof HTMLButtonElement) || !button.dataset.categoryId;
    }

    function getFixedButton() {
        return getButtons().find(isFixedButton) || null;
    }

    function ensureHint() {
        let hint = document.querySelector('[data-hq-menu-category-tabs-hint]');
        if (hint) return hint;

        hint = document.createElement('p');
        hint.setAttribute('data-hq-menu-category-tabs-hint', 'true');
        hint.className = 'mb-8 -mt-5 text-xs font-medium tracking-wide text-slate-400 dark:text-slate-500';
        hint.textContent = '飲品分類可⌈左右⌋拖移調整順序，由左至右即為顧客看到的顯示順序。';
        tabsRoot.insertAdjacentElement('afterend', hint);
        return hint;
    }

    function syncButtonMeta() {
        tabsRoot.setAttribute('role', 'tablist');
        ensureHint();

        for (const button of getButtons()) {
            button.setAttribute('role', 'tab');
            button.classList.add('shrink-0', 'rounded-t-xl', 'select-none', 'transition-all', 'duration-150');
            if (isFixedButton(button)) {
                button.removeAttribute('draggable');
                button.classList.remove('cursor-grab');
                button.classList.add('cursor-default');
            } else {
                button.setAttribute('draggable', 'true');
                button.classList.remove('cursor-default');
                button.classList.add('cursor-grab');
            }
        }
    }

    function setButtonState(button, isActive) {
        button.setAttribute('aria-selected', String(isActive));
        button.setAttribute('tabindex', isActive ? '0' : '-1');

        for (const cls of ACTIVE_CLASSES) button.classList.toggle(cls, isActive);
        for (const cls of INACTIVE_CLASSES) button.classList.toggle(cls, !isActive);
    }

    function applyTab(tabKey) {
        const key = (tabKey || '').trim();
        if (!key) return;

        const buttons = getButtons();
        const panels = getPanels();
        if (buttons.length === 0 || panels.length === 0) return;

        const selectedButton = buttons.find((button) => {
            const btnKey = (button.getAttribute('data-hq-menu-category-tab') || '').trim();
            return btnKey === key;
        }) || getFixedButton();

        for (const button of buttons) {
            setButtonState(button, button === selectedButton);
        }

        if (isFixedButton(selectedButton)) {
            for (const panel of panels) panel.classList.remove('hidden');
            return;
        }

        for (const panel of panels) {
            const panelKey = (panel.getAttribute('data-hq-menu-category-panel') || '').trim();
            panel.classList.toggle('hidden', panelKey !== key);
        }
    }

    function setDropTarget(button) {
        if (currentDropTarget === button) return;

        if (currentDropTarget) {
            for (const cls of DROP_TARGET_CLASSES) currentDropTarget.classList.remove(cls);
        }

        currentDropTarget = button && button !== draggedButton ? button : null;
        if (currentDropTarget) {
            for (const cls of DROP_TARGET_CLASSES) currentDropTarget.classList.add(cls);
        }
    }

    function getOrderedCategoryIds(buttons) {
        return buttons
            .filter((button) => !isFixedButton(button))
            .map((button) => Number(button.dataset.categoryId))
            .filter((categoryId) => Number.isFinite(categoryId) && categoryId > 0);
    }

    function restoreOriginalOrder() {
        if (!originalOrder.length) return;
        for (const button of originalOrder) {
            tabsRoot.appendChild(button);
        }
    }

    function cleanupDragState() {
        setDropTarget(null);
        if (draggedButton) {
            for (const cls of DRAGGING_CLASSES) draggedButton.classList.remove(cls);
        }
        tabsRoot.classList.remove('cursor-grabbing');
        draggedButton = null;
        originalOrder = [];
        didDrop = false;
    }

    function resolveDropPlacement(clientX) {
        const movableButtons = getMovableButtons().filter((button) => button !== draggedButton);
        if (!movableButtons.length) {
            return {
                insertBefore: null,
                highlight: null,
            };
        }

        for (const button of movableButtons) {
            const rect = button.getBoundingClientRect();
            const midpoint = rect.left + rect.width / 2;
            if (clientX < midpoint) {
                return {
                    insertBefore: button,
                    highlight: button,
                };
            }
        }

        return {
            insertBefore: null,
            highlight: movableButtons[movableButtons.length - 1],
        };
    }

    tabsRoot.addEventListener('click', (event) => {
        if (Date.now() < suppressClickUntil) {
            event.preventDefault();
            return;
        }

        const target = event.target instanceof Element
            ? event.target.closest('button[data-hq-menu-category-tab]')
            : null;
        if (!target) return;

        const tabKey = target.getAttribute('data-hq-menu-category-tab') || '';
        applyTab(tabKey);
    });

    tabsRoot.addEventListener('keydown', (event) => {
        const key = event.key;
        if (key !== 'ArrowLeft' && key !== 'ArrowRight' && key !== 'Home' && key !== 'End') return;

        const buttons = getButtons();
        if (buttons.length === 0) return;

        const activeIndex = buttons.findIndex((button) => button.getAttribute('aria-selected') === 'true');
        const currentIndex = activeIndex >= 0 ? activeIndex : 0;

        let nextIndex = currentIndex;
        if (key === 'ArrowLeft') nextIndex = (currentIndex - 1 + buttons.length) % buttons.length;
        if (key === 'ArrowRight') nextIndex = (currentIndex + 1) % buttons.length;
        if (key === 'Home') nextIndex = 0;
        if (key === 'End') nextIndex = buttons.length - 1;

        const nextButton = buttons[nextIndex];
        nextButton.focus();

        const tabKey = nextButton.getAttribute('data-hq-menu-category-tab') || '';
        applyTab(tabKey);

        event.preventDefault();
    });

    tabsRoot.addEventListener('dragstart', (event) => {
        const target = event.target instanceof Element
            ? event.target.closest('button[data-hq-menu-category-tab]')
            : null;
        if (!target || isFixedButton(target)) {
            event.preventDefault();
            return;
        }

        draggedButton = target;
        originalOrder = getButtons();
        didDrop = false;
        tabsRoot.classList.add('cursor-grabbing');
        for (const cls of DRAGGING_CLASSES) draggedButton.classList.add(cls);
        event.dataTransfer?.setData('text/plain', target.dataset.categoryId || '');
        if (event.dataTransfer) {
            event.dataTransfer.effectAllowed = 'move';
        }
    });

    tabsRoot.addEventListener('dragover', (event) => {
        if (!draggedButton) return;

        event.preventDefault();
        const placement = resolveDropPlacement(event.clientX);
        const insertBefore = placement.insertBefore;

        if (insertBefore === null) {
            tabsRoot.appendChild(draggedButton);
        } else if (insertBefore !== draggedButton) {
            tabsRoot.insertBefore(draggedButton, insertBefore);
        }

        setDropTarget(placement.highlight);
    });

    tabsRoot.addEventListener('drop', (event) => {
        if (!draggedButton) return;

        event.preventDefault();
        didDrop = true;
        suppressClickUntil = Date.now() + 250;

        const nextOrderedIds = getOrderedCategoryIds(getButtons());
        const originalOrderedIds = getOrderedCategoryIds(originalOrder);
        if (JSON.stringify(nextOrderedIds) !== JSON.stringify(originalOrderedIds)) {
            window.dispatchEvent(new CustomEvent('hq:category-tab-reorder-request', {
                detail: { orderedCategoryIds: nextOrderedIds }
            }));
        }
    });

    tabsRoot.addEventListener('dragend', () => {
        if (!didDrop) {
            restoreOriginalOrder();
        }
        cleanupDragState();
    });

    const observer = new MutationObserver(() => {
        syncButtonMeta();
    });
    observer.observe(tabsRoot, { childList: true });

    syncButtonMeta();
    const buttons = getButtons();
    if (buttons.length === 0) return;

    const selected = buttons.find((button) => button.getAttribute('aria-selected') === 'true');
    const initialKey = (selected?.getAttribute('data-hq-menu-category-tab') || '').trim();
    applyTab(initialKey || (buttons[0].getAttribute('data-hq-menu-category-tab') || ''));
})();
