(function () {
    "use strict";

    function isHidden(el) {
        return el.classList.contains("hidden");
    }

    function show(el) {
        el.classList.remove("hidden");
        el.setAttribute("aria-hidden", "false");
    }

    function hide(el) {
        el.classList.add("hidden");
        el.setAttribute("aria-hidden", "true");
    }

    function getFirstFocusable(modal) {
        return modal.querySelector(
            'input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [tabindex]:not([tabindex="-1"])'
        );
    }

    function setSelectValue(selectEl, value) {
        if (!selectEl) return;
        var options = Array.prototype.slice.call(selectEl.options || []);
        var found = options.some(function (opt) {
            if (opt.value === value) {
                opt.selected = true;
                return true;
            }
            return false;
        });

        if (!found && options.length) {
            options[0].selected = true;
        }
    }

    function parseCardPrices(text) {
        // Example: "M: $35 / L: $45"
        var result = {};
        if (!text) return result;
        var regex = /([SML])\s*:\s*\$\s*(\d+)/g;
        var match;
        // eslint-disable-next-line no-cond-assign
        while ((match = regex.exec(text)) !== null) {
            result[match[1]] = match[2];
        }
        return result;
    }

    function fillDrinkModalFromCard(modal, card) {
        if (!modal || !card) return;

        var nameEl = modal.querySelector("[data-hq-drink-name]");
        var categoryEl = modal.querySelector("[data-hq-drink-category]");

        var cardName = card.querySelector("p.font-bold");
        if (nameEl && cardName) {
            nameEl.value = (cardName.textContent || "").trim();
        }

        var categorySection = card.closest("section[data-hq-menu-category-panel]");
        var category = categorySection ? categorySection.getAttribute("data-hq-menu-category-panel") : "";
        if (categoryEl) {
            setSelectValue(categoryEl, category);
        }

        var priceTextEl = card.querySelector("p.text-sm.font-bold");
        var parsed = parseCardPrices(priceTextEl ? priceTextEl.textContent : "");
        Object.keys(parsed).forEach(function (size) {
            var input = modal.querySelector('[data-hq-drink-size-price="' + size + '"]');
            if (input) input.value = parsed[size];
        });
    }

    function resetCreateModal(modal) {
        if (!modal) return;
        var nameEl = modal.querySelector("[data-hq-drink-name]");
        var categoryEl = modal.querySelector("[data-hq-drink-category]");
        if (nameEl) nameEl.value = "";
        if (categoryEl && categoryEl.options && categoryEl.options.length) {
            categoryEl.selectedIndex = 0;
        }

        var checks = modal.querySelectorAll("[data-hq-drink-size-check]");
        checks.forEach(function (c) {
            if (c instanceof HTMLInputElement) c.checked = true;
        });

        // 設定預設杯型價格
        var defaultPrices = { S: '40', M: '50', L: '60' };
        modal.querySelectorAll('[data-hq-drink-size-price]').forEach(function (p) {
            if (!(p instanceof HTMLInputElement)) return;
            var size = p.getAttribute('data-hq-drink-size-price');
            p.value = (size && defaultPrices[size]) ? defaultPrices[size] : '';
        });
    }

    function syncOptionGreyForCheckbox(checkbox) {
        if (!(checkbox instanceof HTMLInputElement)) return;
        if (checkbox.type !== "checkbox") return;

        var enabled = checkbox.checked;

        // Size row (spec + price)
        if (checkbox.matches("[data-hq-drink-size-check]") && checkbox.closest("[data-hq-size-row]")) {
            var row = checkbox.closest("[data-hq-size-row]");
            if (row) {
                row.classList.toggle("opacity-50", !enabled);
                var priceInput = row.querySelector('input[type="number"]');
                if (priceInput instanceof HTMLInputElement) {
                    priceInput.disabled = !enabled;
                }
            }
            return;
        }

        // Temperature / Sweetness chip
        var chip = checkbox.closest("[data-hq-drink-chip]");
        if (chip) {
            chip.classList.toggle("opacity-50", !enabled);
        }
    }

    function syncAllOptionGreys(modalEl) {
        if (!modalEl) return;
        modalEl
            .querySelectorAll('input[type="checkbox"][data-hq-drink-size-check], input[type="checkbox"][data-hq-drink-chip-check]')
            .forEach(function (checkbox) {
                syncOptionGreyForCheckbox(checkbox);
            });
    }

    function wireOptionGreySync(modalEl) {
        if (!modalEl) return;

        modalEl.addEventListener("change", function (e) {
            var target = e.target;
            if (!(target instanceof HTMLInputElement)) return;
            if (target.type !== "checkbox") return;
            if (!target.matches("[data-hq-drink-size-check], [data-hq-drink-chip-check]")) return;

            syncOptionGreyForCheckbox(target);
        });

        syncAllOptionGreys(modalEl);
    }

    function init() {
        var createModal = document.querySelector("[data-hq-drink-create-modal]");
        var editModal = document.querySelector("[data-hq-drink-edit-modal]");
        var deleteModal = document.querySelector("[data-hq-drink-delete-modal]");
        if (!createModal || !editModal || !deleteModal) return;

        var lastFocused = null;
        var pendingDeleteCard = null;

        function openModal(modalEl) {
            lastFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
            show(modalEl);
            var focusEl = getFirstFocusable(modalEl);
            if (focusEl && focusEl instanceof HTMLElement) {
                focusEl.focus();
            }
        }

        function closeModal(modalEl) {
            if (!isHidden(modalEl)) hide(modalEl);
            if (lastFocused) lastFocused.focus();
            lastFocused = null;
        }

        function openDeleteModal(cardEl) {
            pendingDeleteCard = cardEl || null;
            openModal(deleteModal);
        }

        function closeDeleteModal() {
            pendingDeleteCard = null;
            closeModal(deleteModal);
        }

        function wireModalClose(modalEl) {
            modalEl.querySelectorAll("[data-hq-modal-close]").forEach(function (btn) {
                btn.addEventListener("click", function () {
                    closeModal(modalEl);
                });
            });
            var backdrop = modalEl.querySelector("[data-hq-modal-backdrop]");
            if (backdrop) {
                backdrop.addEventListener("click", function () {
                    closeModal(modalEl);
                });
            }
        }

        wireModalClose(createModal);
        wireModalClose(editModal);
        wireModalClose(deleteModal);

        wireOptionGreySync(createModal);
        wireOptionGreySync(editModal);

        document.addEventListener("keydown", function (e) {
            if (e.key !== "Escape") return;

            if (createModal && !isHidden(createModal)) {
                e.preventDefault();
                closeModal(createModal);
                return;
            }

            if (editModal && !isHidden(editModal)) {
                e.preventDefault();
                closeModal(editModal);
                return;
            }

            if (deleteModal && !isHidden(deleteModal)) {
                e.preventDefault();
                closeDeleteModal();
            }
        });

        var createOpenBtn = document.querySelector("[data-hq-drink-create-open]");
        if (createOpenBtn) {
            createOpenBtn.addEventListener("click", function () {
                resetCreateModal(createModal);
                syncAllOptionGreys(createModal);
                openModal(createModal);
            });
        }

        document.addEventListener("click", function (e) {
            var target = e.target;
            if (!(target instanceof Element)) return;

            var editBtn = target.closest("[data-hq-drink-edit-open]");
            if (!editBtn) return;

            // 編輯資料與 modal 開啟交由 hq-menu-api.js 統一處理，避免先顯示舊的靜態狀態。
        });

        document.addEventListener("click", function (e) {
            var target = e.target;
            if (!(target instanceof Element)) return;

            var deleteBtn = target.closest("[data-hq-drink-delete-open]");
            if (!deleteBtn) return;

            var card = deleteBtn.closest(".group");
            if (!card) return;

            openDeleteModal(card);
        });

        // delete confirm 的 API 呼叫由 hq-menu-api.js 處理
        // 此處只做 modal 關閉，避免與 API re-render 衝突
        var deleteConfirmBtn = deleteModal.querySelector("[data-hq-drink-delete-confirm]");
        if (deleteConfirmBtn) {
            deleteConfirmBtn.addEventListener("click", function () {
                pendingDeleteCard = null;
                closeDeleteModal();
            });
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();
