/**
 * 規格與配料管理頁 API 綁定
 */
(async () => {
  BrandAPI.requireAuth();
  BrandAPI.renderAdminHeader();

  let brandSpecs = {};
  let brandToppings = [];
  let editTarget = null; // { type: 'spec' | 'topping', id: number }

  let draggingSpecId = null;
  let draggingSpecType = null;
  let draggingOrderSnapshot = [];
  let dragMoved = false;
  let isPersistingOrder = false;
  let dragHandleArmed = false;
  let reloadPromise = null;
  let specsLoadState = "idle";

  const SPEC_TYPES = ["ICE", "SWEETNESS", "SIZE"];
  const SPEC_TBODY_MAP = {
    ICE: '[data-hq-temperature-tbody]',
    SWEETNESS: '[data-hq-sweetness-tbody]',
    SIZE: '[data-hq-size-tbody]',
  };
  const MENU_SETUP_REQUIRED_MESSAGE = "請先在「規格與配料管理」完成至少一項啟用中的規格設定，之後飲品的規格套用選項才會顯示你們已開啟的規格。";

  const displayNameCollator = new Intl.Collator(undefined, {
    sensitivity: "base",
    usage: "search",
  });

  function normalizeDisplayName(value) {
    return typeof value === "string" ? value.trim() : "";
  }

  function isSameDisplayName(left, right) {
    const normalizedLeft = normalizeDisplayName(left);
    const normalizedRight = normalizeDisplayName(right);
    if (!normalizedLeft || !normalizedRight) return normalizedLeft === normalizedRight;
    return displayNameCollator.compare(normalizedLeft, normalizedRight) === 0;
  }

  function toggleHTML(isEnabled) {
    const checked = isEnabled ? "checked" : "";
    const label = isEnabled ? "已啟用" : "已停用";
    return `
      <label class="relative inline-flex items-center cursor-pointer">
        <input type="checkbox" class="sr-only peer" ${checked} />
        <div class="w-9 h-5 bg-slate-200 peer-focus:outline-none rounded-full peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary"></div>
      </label>
      <span class="text-xs font-medium text-slate-600">${label}</span>
    `;
  }

  function specActionsHtml(id) {
    return `
      <span class="material-symbols-outlined text-slate-400 cursor-pointer hover:text-primary transition-colors" data-spec-edit="${id}" title="編輯">edit</span>
      <span class="material-symbols-outlined text-slate-400 cursor-pointer hover:text-red-500 transition-colors" data-spec-delete="${id}" title="刪除">delete</span>
    `;
  }

  function toppingActionsHtml(id) {
    return `
      <span class="material-symbols-outlined text-slate-400 cursor-pointer hover:text-primary transition-colors" data-topping-edit="${id}" title="編輯">edit</span>
      <span class="material-symbols-outlined text-slate-400 cursor-pointer hover:text-red-500 transition-colors" data-topping-delete="${id}" title="刪除">delete</span>
    `;
  }

  function buildSpecRow(spec, type) {
    const tr = document.createElement("tr");
    tr.className = "hover:bg-slate-50 transition-colors";
    tr.dataset.specId = spec.brandSpecId;
    tr.dataset.specType = type;
    tr.draggable = true;
    tr.innerHTML = `
      <td class="px-6 py-4 font-medium">
        <div class="flex items-center gap-2">
          <span class="material-symbols-outlined text-slate-300 cursor-grab select-none" data-drag-handle="true" title="拖曳排序">drag_indicator</span>
          <span>${spec.name}</span>
        </div>
      </td>
      <td class="px-6 py-4"><div class="flex items-center gap-2">${toggleHTML(spec.isEnabled === true)}</div></td>
      <td class="px-6 py-4 text-right">
        <div class="flex items-center justify-end gap-3">${specActionsHtml(spec.brandSpecId)}</div>
      </td>
    `;
    return tr;
  }

  function buildToppingRow(topping) {
    const tr = document.createElement("tr");
    tr.className = "hover:bg-slate-50 transition-colors";
    tr.dataset.toppingId = topping.brandToppingId;
    tr.innerHTML = `
      <td class="px-6 py-4 font-semibold">${topping.name}</td>
      <td class="px-6 py-4 font-medium">$${topping.price}</td>
      <td class="px-6 py-4"><div class="flex items-center gap-2">${toggleHTML(topping.isEnabled === true)}</div></td>
      <td class="px-6 py-4 text-right">
        <div class="flex items-center justify-end gap-3">${toppingActionsHtml(topping.brandToppingId)}</div>
      </td>
    `;
    return tr;
  }

  function renderSpec(type, tbody, list) {
    if (!tbody) return;
    tbody.innerHTML = "";
    if (!list || list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="3" class="px-6 py-4 text-sm text-slate-400 text-center">目前沒有資料</td></tr>';
      return;
    }
    list.forEach((spec) => tbody.appendChild(buildSpecRow(spec, type)));
  }

  function renderToppings(list) {
    const tbody = document.querySelector('[data-hq-topping-tbody]');
    if (!tbody) return;
    tbody.innerHTML = "";
    if (!list || list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="4" class="px-6 py-4 text-sm text-slate-400 text-center">目前沒有資料</td></tr>';
      return;
    }
    list.forEach((topping) => tbody.appendChild(buildToppingRow(topping)));
  }

  function renderAllSpecs() {
    renderSpec("ICE", document.querySelector(SPEC_TBODY_MAP.ICE), brandSpecs.ICE || []);
    renderSpec("SWEETNESS", document.querySelector(SPEC_TBODY_MAP.SWEETNESS), brandSpecs.SWEETNESS || []);
    renderSpec("SIZE", document.querySelector(SPEC_TBODY_MAP.SIZE), brandSpecs.SIZE || []);
  }

  function persistCatalogSnapshots() {
    BrandAPI.saveSpecsSnapshot(brandSpecs);
    BrandAPI.saveToppingsSnapshot(brandToppings);
  }

  function hydrateCatalogSnapshots() {
    const cachedSpecs = BrandAPI.getSpecsSnapshot();
    const cachedToppings = BrandAPI.getToppingsSnapshot();

    if (cachedSpecs) {
      brandSpecs = cachedSpecs;
      specsLoadState = "hydrated";
      renderAllSpecs();
      persistCatalogSnapshots();
    }

    if (cachedToppings) {
      brandToppings = cachedToppings;
      renderToppings(brandToppings);
    }
  }

  function hasEnabledSpecsConfigured() {
    return BrandAPI.hasAnyEnabledSpecs(brandSpecs);
  }

  function bindMenuOverviewGuard() {
    document
      .querySelectorAll('a[href="hq-menu-overview.html"]')
      .forEach((link) => {
        link.addEventListener("click", async (event) => {
          event.preventDefault();

          if (link.dataset.guardPending === "true") return;
          link.dataset.guardPending = "true";

          try {
            if (!hasEnabledSpecsConfigured() && reloadPromise) {
              await reloadPromise;
            }

            if (!hasEnabledSpecsConfigured() && specsLoadState !== "ready") {
              await ensureSpecDataLoaded(true);
            }

            if (specsLoadState === "error") {
              BrandAPI.toast("規格資料載入失敗，請稍後再試", "error");
              return;
            }

            if (!hasEnabledSpecsConfigured()) {
              await BrandAPI.showInfoDialog(MENU_SETUP_REQUIRED_MESSAGE, {
                title: "請先完成規格設定",
                confirmText: "我知道了",
              });
              return;
            }

            window.location.href = link.href;
          } finally {
            delete link.dataset.guardPending;
          }
        });
      });
  }

  function getSpecTypeRows(type) {
    return Array.from(document.querySelectorAll(`tr[data-spec-type="${type}"]`));
  }

  function getCurrentOrder(type) {
    return getSpecTypeRows(type).map((row) => Number(row.dataset.specId));
  }

  function clearDragState() {
    document.querySelectorAll('tr[data-spec-id][data-spec-type].opacity-50')
      .forEach((el) => el.classList.remove("opacity-50"));
    draggingSpecId = null;
    draggingSpecType = null;
    draggingOrderSnapshot = [];
    dragMoved = false;
    dragHandleArmed = false;
  }

  async function reload() {
    specsLoadState = "loading";
    const [specResult, toppingResult] = await Promise.allSettled([
      BrandAPI.refreshSpecs(),
      BrandAPI.refreshToppings(),
    ]);

    if (specResult.status === "fulfilled") {
      brandSpecs = specResult.value || {};
      specsLoadState = "ready";
    } else {
      specsLoadState = Object.keys(brandSpecs || {}).length ? "hydrated" : "error";
      BrandAPI.toast("規格載入失敗：" + specResult.reason.message, "error");
    }

    if (toppingResult.status === "fulfilled") {
      brandToppings = toppingResult.value || [];
    } else {
      BrandAPI.toast("配料載入失敗：" + toppingResult.reason.message, "error");
    }

    renderAllSpecs();
    renderToppings(brandToppings);
    if (specResult.status === "fulfilled" || toppingResult.status === "fulfilled") {
      persistCatalogSnapshots();
    }
  }

  function ensureSpecDataLoaded(forceRefresh = false) {
    if (!forceRefresh && specsLoadState === "ready") {
      return Promise.resolve();
    }

    if (!reloadPromise) {
      reloadPromise = reload().finally(() => {
        reloadPromise = null;
      });
    }
    return reloadPromise;
  }

  hydrateCatalogSnapshots();
  bindMenuOverviewGuard();

  async function persistSpecOrder(type) {
    const orderedSpecIds = getCurrentOrder(type);
    if (orderedSpecIds.length === 0 || isPersistingOrder) return;
    if (draggingOrderSnapshot.length > 0 && orderedSpecIds.join(",") === draggingOrderSnapshot.join(",")) {
      return;
    }

    isPersistingOrder = true;
    try {
      const updatedSpecs = await BrandAPI.reorderSpecs(type, orderedSpecIds);
      if (updatedSpecs) brandSpecs = updatedSpecs;
      renderAllSpecs();
      persistCatalogSnapshots();
      BrandAPI.toast("規格順序已更新");
    } catch (err) {
      await reload();
      BrandAPI.toast("排序更新失敗：" + err.message, "error");
    } finally {
      isPersistingOrder = false;
    }
  }

  function findSpecById(id) {
    for (const type of SPEC_TYPES) {
      const spec = (brandSpecs[type] || []).find((item) => item.brandSpecId === id);
      if (spec) return { spec, type };
    }
    return { spec: null, type: null };
  }

  void ensureSpecDataLoaded(true);

  document.addEventListener("change", async (e) => {
    const checkbox = e.target;
    if (checkbox.type !== "checkbox") return;

    const specRow = checkbox.closest("tr[data-spec-id]");
    if (specRow) {
      const id = Number(specRow.dataset.specId);
      const label = specRow.querySelector("span.text-xs");
      const enabled = checkbox.checked;
      if (label) label.textContent = enabled ? "已啟用" : "已停用";
      try {
        await BrandAPI.toggleSpec(id);
        for (const type of SPEC_TYPES) {
          const item = (brandSpecs[type] || []).find((spec) => spec.brandSpecId === id);
          if (item) {
            item.isEnabled = enabled;
            break;
          }
        }
        persistCatalogSnapshots();
      } catch (err) {
        checkbox.checked = !enabled;
        if (label) label.textContent = !enabled ? "已啟用" : "已停用";
        BrandAPI.toast("切換失敗：" + err.message, "error");
      }
      return;
    }

    const toppingRow = checkbox.closest("tr[data-topping-id]");
    if (toppingRow) {
      const id = Number(toppingRow.dataset.toppingId);
      const label = toppingRow.querySelector("span.text-xs");
      const enabled = checkbox.checked;
      if (label) label.textContent = enabled ? "已啟用" : "已停用";
      try {
        await BrandAPI.toggleTopping(id);
        const item = brandToppings.find((topping) => topping.brandToppingId === id);
        if (item) item.isEnabled = enabled;
        persistCatalogSnapshots();
      } catch (err) {
        checkbox.checked = !enabled;
        if (label) label.textContent = !enabled ? "已啟用" : "已停用";
        BrandAPI.toast("切換失敗：" + err.message, "error");
      }
    }
  });

  document.addEventListener("mousedown", (e) => {
    dragHandleArmed = !!e.target.closest("[data-drag-handle]");
  });

  document.addEventListener("mouseup", () => {
    dragHandleArmed = false;
  });

  document.addEventListener("dragstart", (e) => {
    const row = e.target.closest("tr[data-spec-id][data-spec-type]");
    if (!row) return;
    if (!dragHandleArmed && !e.target.closest("[data-drag-handle]")) {
      e.preventDefault();
      return;
    }

    draggingSpecId = row.dataset.specId;
    draggingSpecType = row.dataset.specType;
    draggingOrderSnapshot = getCurrentOrder(draggingSpecType);
    dragMoved = false;
    row.classList.add("opacity-50");

    if (e.dataTransfer) {
      e.dataTransfer.effectAllowed = "move";
      e.dataTransfer.setData("text/plain", draggingSpecId);
    }
  });

  document.addEventListener("dragover", (e) => {
    if (!draggingSpecId || !draggingSpecType) return;
    const draggingRow = document.querySelector(`tr[data-spec-id="${draggingSpecId}"]`);
    if (!draggingRow) return;

    const row = e.target.closest("tr[data-spec-id][data-spec-type]");
    const tbody = e.target.closest(SPEC_TBODY_MAP[draggingSpecType]);
    if (!row && !tbody) return;

    e.preventDefault();

    if (row) {
      if (row.dataset.specId === draggingSpecId || row.dataset.specType !== draggingSpecType) return;
      const rect = row.getBoundingClientRect();
      const shouldInsertBefore = e.clientY < rect.top + rect.height / 2;
      if (shouldInsertBefore) {
        row.parentElement?.insertBefore(draggingRow, row);
      } else {
        row.parentElement?.insertBefore(draggingRow, row.nextSibling);
      }
      dragMoved = true;
      return;
    }

    if (tbody && tbody.lastElementChild !== draggingRow) {
      tbody.appendChild(draggingRow);
      dragMoved = true;
    }
  });

  document.addEventListener("drop", async (e) => {
    const row = e.target.closest("tr[data-spec-id][data-spec-type]");
    const tbody = draggingSpecType ? e.target.closest(SPEC_TBODY_MAP[draggingSpecType]) : null;
    if (!draggingSpecId || (!row && !tbody)) return;
    e.preventDefault();

    const type = draggingSpecType;
    if (type) {
      await persistSpecOrder(type);
    }
    clearDragState();
  });

  document.addEventListener("dragend", async () => {
    const type = draggingSpecType;
    const changed = type
      && draggingOrderSnapshot.length > 0
      && getCurrentOrder(type).join(",") !== draggingOrderSnapshot.join(",");

    if (changed || dragMoved) {
      await persistSpecOrder(type);
    }
    clearDragState();
  });

  const editModal = document.querySelector("[data-hq-edit-modal]");
  const editTitleEl = editModal?.querySelector("[data-hq-edit-title]");
  const editNameInp = editModal?.querySelector("[data-hq-edit-name]");
  const editPriceGroup = editModal?.querySelector("[data-hq-edit-price-group]");
  const editPriceInp = editModal?.querySelector("[data-hq-edit-price]");
  const editForm = editModal?.querySelector("[data-hq-edit-form]");

  editModal?.querySelectorAll("[data-hq-modal-close],[data-hq-modal-backdrop]").forEach((el) => {
    el.addEventListener("click", () => {
      editModal.classList.add("hidden");
      editTarget = null;
    });
  });

  document.addEventListener("click", (e) => {
    const icon = e.target.closest("[data-spec-edit]");
    if (!icon) return;
    const { spec } = findSpecById(Number(icon.dataset.specEdit));
    if (!spec) return;

    editTarget = { type: "spec", id: spec.brandSpecId };
    if (editTitleEl) editTitleEl.textContent = "編輯規格";
    if (editNameInp) editNameInp.value = spec.name;
    if (editPriceGroup) editPriceGroup.classList.add("hidden");
    editModal?.classList.remove("hidden");
    editNameInp?.focus();
  });

  document.addEventListener("click", async (e) => {
    const icon = e.target.closest("[data-spec-delete]");
    if (!icon) return;
    const { spec, type } = findSpecById(Number(icon.dataset.specDelete));
    if (!spec || !type) return;
    if (!window.confirm(`確定要刪除規格「${spec.name}」嗎？`)) return;

    try {
      await BrandAPI.deleteSpec(spec.brandSpecId);
      brandSpecs[type] = (brandSpecs[type] || []).filter((item) => item.brandSpecId !== spec.brandSpecId);
      renderSpec(type, document.querySelector(SPEC_TBODY_MAP[type]), brandSpecs[type]);
      persistCatalogSnapshots();
      BrandAPI.toast(`${spec.name} 已刪除`);
    } catch (err) {
      BrandAPI.toast("刪除失敗：" + err.message, "error");
    }
  });

  document.addEventListener("click", (e) => {
    const icon = e.target.closest("[data-topping-edit]");
    if (!icon) return;
    const topping = brandToppings.find((item) => item.brandToppingId === Number(icon.dataset.toppingEdit));
    if (!topping) return;

    editTarget = { type: "topping", id: topping.brandToppingId };
    if (editTitleEl) editTitleEl.textContent = "編輯配料";
    if (editNameInp) editNameInp.value = topping.name;
    if (editPriceGroup) editPriceGroup.classList.remove("hidden");
    if (editPriceInp) editPriceInp.value = topping.price ?? "";
    editModal?.classList.remove("hidden");
    editNameInp?.focus();
  });

  document.addEventListener("click", async (e) => {
    const icon = e.target.closest("[data-topping-delete]");
    if (!icon) return;
    const topping = brandToppings.find((item) => item.brandToppingId === Number(icon.dataset.toppingDelete));
    if (!topping) return;
    if (!window.confirm(`確定要刪除配料「${topping.name}」嗎？`)) return;

    try {
      await BrandAPI.deleteTopping(topping.brandToppingId);
      brandToppings = brandToppings.filter((item) => item.brandToppingId !== topping.brandToppingId);
      renderToppings(brandToppings);
      persistCatalogSnapshots();
      BrandAPI.toast(`${topping.name} 已刪除`);
    } catch (err) {
      BrandAPI.toast("刪除失敗：" + err.message, "error");
    }
  });

  editForm?.addEventListener("submit", async (e) => {
    e.preventDefault();
    if (!editTarget) return;

    const name = editNameInp?.value?.trim();
    if (!name) {
      BrandAPI.toast("請輸入名稱", "error");
      return;
    }

    const submitBtn = editForm.querySelector('[type="submit"]');
    if (submitBtn) submitBtn.disabled = true;

    try {
      if (editTarget.type === "spec") {
        for (const type of SPEC_TYPES) {
          const duplicated = (brandSpecs[type] || []).find(
            (item) => isSameDisplayName(item.name, name) && item.brandSpecId !== editTarget.id
          );
          if (duplicated) {
            BrandAPI.toast(`「${name}」已存在`, "error");
            if (submitBtn) submitBtn.disabled = false;
            return;
          }
        }

        const updated = await BrandAPI.updateSpec(editTarget.id, name);
        for (const type of SPEC_TYPES) {
          const item = (brandSpecs[type] || []).find((spec) => spec.brandSpecId === editTarget.id);
          if (item) {
            item.name = updated.name;
            item.isEnabled = updated.isEnabled === true;
            break;
          }
        }
        renderAllSpecs();
        persistCatalogSnapshots();
        BrandAPI.toast(`${updated.name} 已更新`);
      } else {
        const duplicated = brandToppings.find(
          (item) => isSameDisplayName(item.name, name) && item.brandToppingId !== editTarget.id
        );
        if (duplicated) {
          BrandAPI.toast(`「${name}」已存在`, "error");
          if (submitBtn) submitBtn.disabled = false;
          return;
        }

        const price = editPriceInp ? Number(editPriceInp.value) || 0 : 0;
        const updated = await BrandAPI.updateTopping(editTarget.id, name, price);
        const item = brandToppings.find((topping) => topping.brandToppingId === editTarget.id);
        if (item) {
          item.name = updated.name;
          item.price = updated.price;
          item.isEnabled = updated.isEnabled === true;
        }
        renderToppings(brandToppings);
        persistCatalogSnapshots();
        BrandAPI.toast(`${updated.name} 已更新`);
      }

      editModal?.classList.add("hidden");
      editTarget = null;
    } catch (err) {
      BrandAPI.toast("更新失敗：" + err.message, "error");
    } finally {
      if (submitBtn) submitBtn.disabled = false;
    }
  });

  function wireSpecCreate(openAttr, modalSelector, inputSelector, type) {
    const openBtn = document.querySelector(`[${openAttr}]`);
    const modal = document.querySelector(modalSelector);
    const input = modal?.querySelector(inputSelector);
    const form = modal?.querySelector("form[data-hq-create-form]");
    if (!modal || !input || !form) return;

    openBtn?.addEventListener("click", () => {
      modal.classList.remove("hidden");
      input.value = "";
      input.focus();
    });

    modal.querySelectorAll("[data-hq-modal-close],[data-hq-modal-backdrop]").forEach((el) => {
      el.addEventListener("click", () => modal.classList.add("hidden"));
    });

    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const name = input.value.trim();
      if (!name) {
        BrandAPI.toast("請輸入名稱", "error");
        return;
      }

      const existing = (brandSpecs[type] || []).find((item) => isSameDisplayName(item.name, name));
      if (existing) {
        BrandAPI.toast(`「${name}」已存在`, "error");
        return;
      }

      const submitBtn = form.querySelector('[type="submit"]');
      if (submitBtn) submitBtn.disabled = true;

      try {
        const newSpec = await BrandAPI.addSpec(type, name);
        if (!brandSpecs[type]) brandSpecs[type] = [];
        brandSpecs[type].push(newSpec);
        renderSpec(type, document.querySelector(SPEC_TBODY_MAP[type]), brandSpecs[type]);
        persistCatalogSnapshots();
        modal.classList.add("hidden");
        BrandAPI.toast(`${name} 已新增`);
      } catch (err) {
        BrandAPI.toast("新增失敗：" + err.message, "error");
      } finally {
        if (submitBtn) submitBtn.disabled = false;
      }
    });
  }

  wireSpecCreate("data-hq-temperature-create-open", "[data-hq-temperature-create-modal]", "[data-hq-temperature-name]", "ICE");
  wireSpecCreate("data-hq-sweetness-create-open", "[data-hq-sweetness-create-modal]", "[data-hq-sweetness-name]", "SWEETNESS");
  wireSpecCreate("data-hq-size-create-open", "[data-hq-size-create-modal]", "[data-hq-size-name]", "SIZE");

  const toppingModal = document.querySelector("[data-hq-topping-create-modal]");
  const toppingOpenBtn = document.querySelector("[data-hq-topping-create-open]");
  const toppingNameInp = toppingModal?.querySelector("[data-hq-topping-name]");
  const toppingPriceInp = toppingModal?.querySelector("[data-hq-topping-price]");
  const toppingForm = toppingModal?.querySelector("form[data-hq-create-form]");

  toppingOpenBtn?.addEventListener("click", () => {
    toppingModal?.classList.remove("hidden");
    if (toppingNameInp) toppingNameInp.value = "";
    if (toppingPriceInp) toppingPriceInp.value = "";
    toppingNameInp?.focus();
  });

  toppingModal?.querySelectorAll("[data-hq-modal-close],[data-hq-modal-backdrop]").forEach((el) => {
    el.addEventListener("click", () => toppingModal.classList.add("hidden"));
  });

  toppingForm?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const name = toppingNameInp?.value?.trim();
    const price = toppingPriceInp ? Number(toppingPriceInp.value) || 0 : 0;

    if (!name) {
      BrandAPI.toast("請輸入配料名稱", "error");
      return;
    }

    if (brandToppings.find((item) => isSameDisplayName(item.name, name))) {
      BrandAPI.toast(`「${name}」已存在`, "error");
      return;
    }

    const submitBtn = toppingForm.querySelector('[type="submit"]');
    if (submitBtn) submitBtn.disabled = true;

    try {
      const newTopping = await BrandAPI.addTopping(name, price);
      brandToppings.push(newTopping);
      renderToppings(brandToppings);
      persistCatalogSnapshots();
      toppingModal.classList.add("hidden");
      BrandAPI.toast(`${name} 已新增`);
    } catch (err) {
      BrandAPI.toast("新增失敗：" + err.message, "error");
    } finally {
      if (submitBtn) submitBtn.disabled = false;
    }
  });
})();
