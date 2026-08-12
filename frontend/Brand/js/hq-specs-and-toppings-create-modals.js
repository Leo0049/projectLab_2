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

    function clearForm(modalEl) {
        if (!modalEl) return;
        var form = modalEl.querySelector("form[data-hq-create-form]");
        if (!(form instanceof HTMLFormElement)) return;
        form.querySelectorAll("input").forEach(function (input) {
            if (!(input instanceof HTMLInputElement)) return;
            if (input.type === "checkbox" || input.type === "radio") return;
            input.value = "";
        });
    }

    function syncRowSwitch(input) {
        if (!(input instanceof HTMLInputElement)) return;
        if (input.type !== "checkbox") return;

        var row = input.closest("tr");
        if (!row) return;

        var enabled = input.checked;
        row.classList.toggle("opacity-60", !enabled);

        var wrapper = input.parentElement;
        if (wrapper) {
            var statusText = wrapper.nextElementSibling;
            if (statusText && statusText.tagName === "SPAN") {
                statusText.textContent = enabled ? "已啟用" : "已停用";
            }
        }
    }

    function wireRowSwitch(input) {
        if (!(input instanceof HTMLInputElement)) return;
        if (input.type !== "checkbox") return;
        syncRowSwitch(input);
        input.addEventListener("change", function () {
            syncRowSwitch(input);
        });
    }

    function createStatusCell() {
        var td = document.createElement("td");
        td.className = "px-6 py-4";
        td.innerHTML =
            '<div class="flex items-center gap-2">' +
            '<label class="relative inline-flex items-center cursor-pointer">' +
            '<input checked class="sr-only peer" type="checkbox" />' +
            '<div class="w-9 h-5 bg-slate-200 peer-focus:outline-none rounded-full peer dark:bg-slate-700 peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[\'\'] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all dark:border-gray-600 peer-checked:bg-primary"></div>' +
            "</label>" +
            '<span class="text-xs font-medium text-slate-600 dark:text-slate-400">已啟用</span>' +
            "</div>";
        return td;
    }

    function createActionsCell() {
        var td = document.createElement("td");
        td.className = "px-6 py-4 text-right flex items-center justify-end gap-3";
        td.innerHTML =
            '<span data-hq-edit-open class="material-symbols-outlined text-slate-400 cursor-pointer hover:text-primary transition-colors">edit</span>' +
            '<span data-hq-delete-open class="material-symbols-outlined text-slate-400 cursor-pointer hover:text-red-500 transition-colors">delete</span>';
        return td;
    }

    function parseNumber(text) {
        if (!text) return "";
        var m = String(text).match(/\d+(?:\.\d+)?/);
        return m ? m[0] : "";
    }

    function getRowType(rowEl) {
        if (!rowEl) return "";
        if (rowEl.closest("[data-hq-temperature-tbody]")) return "temperature";
        if (rowEl.closest("[data-hq-sweetness-tbody]")) return "sweetness";
        if (rowEl.closest("[data-hq-size-tbody]")) return "size";
        if (rowEl.closest("[data-hq-topping-tbody]")) return "topping";
        return "";
    }

    function appendTemperatureRow(tbody, name) {
        if (!tbody) return;
        var tr = document.createElement("tr");
        tr.className = "hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors";

        var tdName = document.createElement("td");
        tdName.className = "px-6 py-4 font-medium";
        tdName.textContent = name;

        var tdStatus = createStatusCell();
        var tdActions = createActionsCell();

        tr.appendChild(tdName);
        tr.appendChild(tdStatus);
        tr.appendChild(tdActions);
        tbody.appendChild(tr);

        var input = tr.querySelector('input[type="checkbox"].sr-only.peer');
        if (input) wireRowSwitch(input);
    }

    function appendSweetnessRow(tbody, name, percent) {
        if (!tbody) return;
        var tr = document.createElement("tr");
        tr.className = "hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors";

        var tdName = document.createElement("td");
        tdName.className = "px-6 py-4 font-medium";
        tdName.textContent = name;

        var tdPercent = document.createElement("td");
        tdPercent.className = "px-6 py-4 text-slate-500";
        tdPercent.textContent = String(percent) + "%";

        var tdStatus = createStatusCell();
        var tdActions = createActionsCell();

        tr.appendChild(tdName);
        tr.appendChild(tdPercent);
        tr.appendChild(tdStatus);
        tr.appendChild(tdActions);
        tbody.appendChild(tr);

        var input = tr.querySelector('input[type="checkbox"].sr-only.peer');
        if (input) wireRowSwitch(input);
    }

    function appendSizeRow(tbody, name) {
        if (!tbody) return;
        var tr = document.createElement("tr");
        tr.className = "hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors";

        var tdName = document.createElement("td");
        tdName.className = "px-6 py-4 font-medium";
        tdName.textContent = name;

        var tdStatus = createStatusCell();
        var tdActions = createActionsCell();

        tr.appendChild(tdName);
        tr.appendChild(tdStatus);
        tr.appendChild(tdActions);
        tbody.appendChild(tr);

        var input = tr.querySelector('input[type="checkbox"].sr-only.peer');
        if (input) wireRowSwitch(input);
    }

    function appendToppingRow(tbody, name, price) {
        if (!tbody) return;
        var tr = document.createElement("tr");
        tr.className = "hover:bg-slate-50/50 transition-colors";

        var tdName = document.createElement("td");
        tdName.className = "px-6 py-4 font-semibold";
        tdName.textContent = name;

        var tdPrice = document.createElement("td");
        tdPrice.className = "px-6 py-4 font-medium";
        tdPrice.textContent = "$" + String(price);

        var tdStatus = createStatusCell();
        var tdActions = createActionsCell();

        tr.appendChild(tdName);
        tr.appendChild(tdPrice);
        tr.appendChild(tdStatus);
        tr.appendChild(tdActions);
        tbody.appendChild(tr);

        var input = tr.querySelector('input[type="checkbox"].sr-only.peer');
        if (input) wireRowSwitch(input);
    }

    function init() {
        var modals = {
            temperature: document.querySelector("[data-hq-temperature-create-modal]"),
            sweetness: document.querySelector("[data-hq-sweetness-create-modal]"),
            size: document.querySelector("[data-hq-size-create-modal]"),
            topping: document.querySelector("[data-hq-topping-create-modal]"),
            deleteConfirm: document.querySelector("[data-hq-delete-confirm-modal]"),
            edit: document.querySelector("[data-hq-edit-modal]")
        };

        var openButtons = {
            temperature: document.querySelector("[data-hq-temperature-create-open]"),
            sweetness: document.querySelector("[data-hq-sweetness-create-open]"),
            size: document.querySelector("[data-hq-size-create-open]"),
            topping: document.querySelector("[data-hq-topping-create-open]")
        };

        var lastFocused = null;
        var currentModal = null;

        var tbodies = {
            temperature: document.querySelector("[data-hq-temperature-tbody]"),
            sweetness: document.querySelector("[data-hq-sweetness-tbody]"),
            size: document.querySelector("[data-hq-size-tbody]"),
            topping: document.querySelector("[data-hq-topping-tbody]")
        };

        var pendingDeleteRow = null;
        var deleteConfirmBtn = modals.deleteConfirm
            ? modals.deleteConfirm.querySelector("[data-hq-delete-confirm]")
            : null;

        var pendingEditRow = null;
        var pendingEditType = "";
        var editTitleEl = modals.edit ? modals.edit.querySelector("[data-hq-edit-title]") : null;
        var editNameLabelEl = modals.edit ? modals.edit.querySelector("[data-hq-edit-name-label]") : null;
        var editNameInput = modals.edit ? modals.edit.querySelector("[data-hq-edit-name]") : null;
        var editPercentGroup = modals.edit ? modals.edit.querySelector("[data-hq-edit-percent-group]") : null;
        var editPercentInput = modals.edit ? modals.edit.querySelector("[data-hq-edit-percent]") : null;
        var editPriceGroup = modals.edit ? modals.edit.querySelector("[data-hq-edit-price-group]") : null;
        var editPriceInput = modals.edit ? modals.edit.querySelector("[data-hq-edit-price]") : null;

        function openModal(modalEl) {
            if (!modalEl) return;

            if (currentModal && currentModal !== modalEl && !isHidden(currentModal)) {
                hide(currentModal);
            }

            lastFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
            clearForm(modalEl);
            show(modalEl);
            currentModal = modalEl;

            var focusEl = getFirstFocusable(modalEl);
            if (focusEl && focusEl instanceof HTMLElement) {
                focusEl.focus();
            }
        }

        function closeModal(modalEl) {
            if (!modalEl) return;
            if (!isHidden(modalEl)) hide(modalEl);
            if (currentModal === modalEl) currentModal = null;

            if (lastFocused) lastFocused.focus();
            lastFocused = null;

            if (modalEl === modals.edit) {
                pendingEditRow = null;
                pendingEditType = "";
            }
        }

        function openDeleteConfirm(rowEl) {
            if (!modals.deleteConfirm) return;
            pendingDeleteRow = rowEl || null;
            openModal(modals.deleteConfirm);
        }

        function openEditModal(rowEl, type) {
            if (!modals.edit) return;
            pendingEditRow = rowEl || null;
            pendingEditType = type || "";

            if (editPercentGroup) editPercentGroup.classList.add("hidden");
            if (editPriceGroup) editPriceGroup.classList.add("hidden");

            var title = "編輯";
            var nameLabel = "名稱";
            if (type === "temperature") {
                title = "編輯溫度選項";
                nameLabel = "溫度名稱";
            }
            if (type === "sweetness") {
                title = "編輯甜度選項";
                nameLabel = "甜度名稱";
                if (editPercentGroup) editPercentGroup.classList.remove("hidden");
            }
            if (type === "size") {
                title = "編輯容量規格";
                nameLabel = "規格名稱";
            }
            if (type === "topping") {
                title = "編輯配料";
                nameLabel = "配料名稱";
                if (editPriceGroup) editPriceGroup.classList.remove("hidden");
            }

            if (editTitleEl) editTitleEl.textContent = title;
            if (editNameLabelEl) editNameLabelEl.textContent = nameLabel;

            if (pendingEditRow) {
                var cells = pendingEditRow.querySelectorAll("td");
                var nameCell = cells && cells.length ? cells[0] : null;
                if (editNameInput && editNameInput instanceof HTMLInputElement) {
                    editNameInput.value = nameCell ? (nameCell.textContent || "").trim() : "";
                }

                if (type === "sweetness" && editPercentInput && editPercentInput instanceof HTMLInputElement) {
                    var percentCell = cells.length > 1 ? cells[1] : null;
                    editPercentInput.value = percentCell ? parseNumber(percentCell.textContent || "") : "";
                }

                if (type === "topping" && editPriceInput && editPriceInput instanceof HTMLInputElement) {
                    var priceCell = cells.length > 1 ? cells[1] : null;
                    editPriceInput.value = priceCell ? parseNumber(priceCell.textContent || "") : "";
                }
            }

            openModal(modals.edit);
            if (editNameInput && editNameInput instanceof HTMLInputElement) {
                editNameInput.focus();
                editNameInput.select();
            }
        }

        function wireModal(modalEl) {
            if (!modalEl) return;

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

            var form = modalEl.querySelector("form[data-hq-create-form]");
            if (form instanceof HTMLFormElement) {
                form.addEventListener("submit", function (e) {
                    e.preventDefault();

                    // Insert a new row into the corresponding table
                    if (modalEl === modals.temperature) {
                        var nameInput = modalEl.querySelector("[data-hq-temperature-name]");
                        var name = nameInput && nameInput.value ? nameInput.value.trim() : "";
                        if (name) appendTemperatureRow(tbodies.temperature, name);
                    }

                    if (modalEl === modals.sweetness) {
                        var sNameInput = modalEl.querySelector("[data-hq-sweetness-name]");
                        var pInput = modalEl.querySelector("[data-hq-sweetness-percent]");
                        var sName = sNameInput && sNameInput.value ? sNameInput.value.trim() : "";
                        var percent = pInput && pInput.value ? String(pInput.value).trim() : "";
                        if (sName && percent !== "") appendSweetnessRow(tbodies.sweetness, sName, percent);
                    }

                    if (modalEl === modals.size) {
                        var sizeInput = modalEl.querySelector("[data-hq-size-name]");
                        var sizeName = sizeInput && sizeInput.value ? sizeInput.value.trim() : "";
                        if (sizeName) appendSizeRow(tbodies.size, sizeName);
                    }

                    if (modalEl === modals.topping) {
                        var tNameInput = modalEl.querySelector("[data-hq-topping-name]");
                        var priceInput = modalEl.querySelector("[data-hq-topping-price]");
                        var tName = tNameInput && tNameInput.value ? tNameInput.value.trim() : "";
                        var price = priceInput && priceInput.value ? String(priceInput.value).trim() : "";
                        if (tName && price !== "") appendToppingRow(tbodies.topping, tName, price);
                    }

                    closeModal(modalEl);
                });
            }

            var editForm = modalEl.querySelector("form[data-hq-edit-form]");
            if (editForm instanceof HTMLFormElement) {
                editForm.addEventListener("submit", function (e) {
                    e.preventDefault();
                    if (!pendingEditRow) {
                        closeModal(modalEl);
                        return;
                    }

                    var cells = pendingEditRow.querySelectorAll("td");
                    var nextName = editNameInput && editNameInput instanceof HTMLInputElement ? editNameInput.value.trim() : "";
                    if (cells.length && nextName) {
                        cells[0].textContent = nextName;
                    }

                    if (pendingEditType === "sweetness" && cells.length > 1 && editPercentInput && editPercentInput instanceof HTMLInputElement) {
                        var nextPercent = editPercentInput.value !== "" ? String(editPercentInput.value).trim() : "";
                        if (nextPercent !== "") cells[1].textContent = nextPercent + "%";
                    }

                    if (pendingEditType === "topping" && cells.length > 1 && editPriceInput && editPriceInput instanceof HTMLInputElement) {
                        var nextPrice = editPriceInput.value !== "" ? String(editPriceInput.value).trim() : "";
                        if (nextPrice !== "") cells[1].textContent = "$" + nextPrice;
                    }

                    closeModal(modalEl);
                });
            }
        }

        // Create modals 的 form submit 由 hq-specs-api.js 負責（含 API 呼叫與 DOM 更新）
        // 此處只 wire delete confirm 與 edit modal
        wireModal(modals.deleteConfirm);
        wireModal(modals.edit);

        // Delete (event delegation for existing & newly added rows)
        document.addEventListener("click", function (e) {
            var target = e.target;
            if (!(target instanceof Element)) return;

            var deleteBtn = target.closest("[data-hq-delete-open]");
            if (!deleteBtn) return;

            var row = deleteBtn.closest("tr");
            if (!row) return;

            openDeleteConfirm(row);
        });

        // Edit (event delegation for existing & newly added rows)
        document.addEventListener("click", function (e) {
            var target = e.target;
            if (!(target instanceof Element)) return;

            var editBtn = target.closest("[data-hq-edit-open]");
            if (!editBtn) return;

            var row = editBtn.closest("tr");
            if (!row) return;

            var type = getRowType(row);
            openEditModal(row, type);
        });

        if (deleteConfirmBtn) {
            deleteConfirmBtn.addEventListener("click", function () {
                if (pendingDeleteRow && pendingDeleteRow.parentElement) {
                    pendingDeleteRow.parentElement.removeChild(pendingDeleteRow);
                }
                pendingDeleteRow = null;
                closeModal(modals.deleteConfirm);
            });
        }

        if (openButtons.temperature) {
            openButtons.temperature.addEventListener("click", function () {
                openModal(modals.temperature);
            });
        }
        if (openButtons.sweetness) {
            openButtons.sweetness.addEventListener("click", function () {
                openModal(modals.sweetness);
            });
        }
        if (openButtons.size) {
            openButtons.size.addEventListener("click", function () {
                openModal(modals.size);
            });
        }
        if (openButtons.topping) {
            openButtons.topping.addEventListener("click", function () {
                openModal(modals.topping);
            });
        }

        document.addEventListener("keydown", function (e) {
            if (e.key !== "Escape") return;
            if (!currentModal || isHidden(currentModal)) return;
            e.preventDefault();
            closeModal(currentModal);
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();
