/* Toggle "今日供應" switches on menu-toppings.html (same template as home inventory quick toggle) */

(function () {
    const STORAGE_KEY = 'btpro.toppings.supply.v1';
    const SUPPLY_ON_TEXT = '供應中';
    const SUPPLY_OFF_TEXT = '暫停供應';

    function safeParse(json) {
        try {
            return JSON.parse(json);
        } catch {
            return null;
        }
    }

    function loadSupply() {
        const raw = localStorage.getItem(STORAGE_KEY);
        const parsed = raw ? safeParse(raw) : null;
        const map = parsed && typeof parsed === 'object' ? parsed : null;
        return map && typeof map === 'object' ? map : {};
    }

    function saveSupply(map) {
        const payload = map && typeof map === 'object' ? map : {};
        localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
    }

    function ensureSeededFromDom(root) {
        const existing = loadSupply();
        const switches = Array.from(root.querySelectorAll('[data-topping-supply-toggle][role="switch"][data-topping-id]'));
        let changed = false;
        switches.forEach((switchEl) => {
            const id = String(switchEl.getAttribute('data-topping-id') || '').trim();
            if (!id) return;
            if (Object.prototype.hasOwnProperty.call(existing, id)) return;
            existing[id] = switchEl.getAttribute('aria-checked') === 'true';
            changed = true;
        });
        if (changed) saveSupply(existing);
        return existing;
    }

    function setSwitchState(switchEl, isOn) {
        const knob = switchEl.querySelector('div.absolute');
        const scope = switchEl.closest('td') || switchEl.closest('tr') || document;
        const statusEl = scope ? scope.querySelector('[data-topping-supply-status]') : null;

        switchEl.setAttribute('aria-checked', isOn ? 'true' : 'false');

        // Switch background (match home inventory quick toggle)
        if (isOn) {
            switchEl.classList.add('bg-primary');
            switchEl.classList.remove('bg-slate-200');
            switchEl.classList.remove('dark:bg-slate-600');
        } else {
            switchEl.classList.remove('bg-primary');
            switchEl.classList.add('bg-slate-200');
            switchEl.classList.add('dark:bg-slate-600');
        }

        // Knob position (match home inventory quick toggle)
        if (knob) {
            knob.style.willChange = 'transform';
            knob.classList.add('left-1');
            knob.classList.remove('right-1');
            knob.classList.toggle('translate-x-5', isOn);
        }

        // Status text + color
        if (statusEl) {
            statusEl.textContent = isOn ? SUPPLY_ON_TEXT : SUPPLY_OFF_TEXT;
            statusEl.classList.remove('text-green-500', 'text-red-500');
            statusEl.classList.add(isOn ? 'text-green-500' : 'text-red-500');
        }
    }

    function applyFromStore(root, supplyMap) {
        const switches = Array.from(root.querySelectorAll('[data-topping-supply-toggle][role="switch"][data-topping-id]'));
        switches.forEach((switchEl) => {
            const id = String(switchEl.getAttribute('data-topping-id') || '').trim();
            if (!id) return;
            const isOn = Boolean(supplyMap?.[id]);
            setSwitchState(switchEl, isOn);
        });
    }

    function init() {
        const root = document.querySelector('body');
        if (!root) return;

        let supplyMap = ensureSeededFromDom(root);
        applyFromStore(root, supplyMap);

        const switches = root.querySelectorAll('[data-topping-supply-toggle][role="switch"][data-topping-id]');
        switches.forEach((switchEl) => {
            const id = String(switchEl.getAttribute('data-topping-id') || '').trim();
            if (!id) return;

            switchEl.addEventListener('click', () => {
                const current = switchEl.getAttribute('aria-checked') === 'true';
                const next = !current;
                supplyMap = loadSupply();
                supplyMap[id] = next;
                saveSupply(supplyMap);
                setSwitchState(switchEl, next);
            });

            // Keyboard accessibility
            switchEl.tabIndex = 0;
            switchEl.addEventListener('keydown', (event) => {
                if (event.key !== 'Enter' && event.key !== ' ') return;
                event.preventDefault();
                const current = switchEl.getAttribute('aria-checked') === 'true';
                const next = !current;
                supplyMap = loadSupply();
                supplyMap[id] = next;
                saveSupply(supplyMap);
                setSwitchState(switchEl, next);
            });
        });

        window.addEventListener('storage', (event) => {
            if (event.key !== STORAGE_KEY) return;
            const next = loadSupply();
            applyFromStore(root, next);
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
