(function () {
    const DISABLED_INPUT_CLASSES = ['bg-slate-100', 'text-slate-400', 'cursor-not-allowed'];
    const ENABLED_INPUT_CLASSES = ['bg-slate-50', 'text-slate-700'];

    function setInputDisabled(input, disabled) {
        input.disabled = disabled;

        if (disabled) {
            input.classList.remove(...ENABLED_INPUT_CLASSES);
            input.classList.add(...DISABLED_INPUT_CLASSES);
        } else {
            input.classList.remove(...DISABLED_INPUT_CLASSES);
            input.classList.add(...ENABLED_INPUT_CLASSES);
        }
    }

    function setSectionDisabled(section, disabled) {
        if (disabled) {
            section.classList.add('opacity-60');
        } else {
            section.classList.remove('opacity-60');
        }
    }

    function sync(root, enabled) {
        const dependentInputs = root.querySelectorAll('[data-delivery-dependent]');
        dependentInputs.forEach((input) => setInputDisabled(input, !enabled));

        const dependentSections = root.querySelectorAll('[data-delivery-dependent-section]');
        dependentSections.forEach((section) => setSectionDisabled(section, !enabled));
    }

    function init() {
        const root = document.getElementById('deliverySettingsRoot');
        const toggle = document.getElementById('deliveryEnabledToggle');
        if (!root || !toggle) return;

        // initial
        sync(root, toggle.checked);

        toggle.addEventListener('change', () => {
            sync(root, toggle.checked);
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
