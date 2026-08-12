(async () => {
  BrandAPI.requireAuth();
  BrandAPI.renderAdminHeader();

  let allStores = [];
  let regions = [];
  const modalInlineNoticeTimers = new WeakMap();

  const tbody = document.querySelector('[data-hq-store-table] tbody');
  const regionSel = document.querySelector('[data-hq-store-region]');
  const regionLbl = document.querySelector('[data-hq-store-region-label]');
  const searchInp = document.querySelector('[data-hq-store-search]');

  function clearSearchAutofill() {
    if (!searchInp) return;

    searchInp.setAttribute('autocomplete', 'off');
    searchInp.setAttribute('autocapitalize', 'off');
    searchInp.setAttribute('autocorrect', 'off');
    searchInp.setAttribute('spellcheck', 'false');
    searchInp.setAttribute('readonly', 'readonly');
    searchInp.value = '';
  }

  function unlockSearchInput() {
    searchInp?.removeAttribute('readonly');
  }

  function ensureModalInlineNotice(modal) {
    if (!modal) return null;

    let notice = modal.querySelector('[data-hq-modal-inline-notice]');
    if (notice) return notice;

    const saveButton = modal.querySelector('[data-hq-store-create-save],[data-hq-store-edit-save]');
    const footer = saveButton?.parentElement;
    if (!footer) return null;

    footer.classList.remove('justify-between');
    footer.classList.add('justify-end', 'items-end');

    let actions = footer.querySelector('[data-hq-modal-footer-actions]');
    if (!actions) {
      actions = document.createElement('div');
      actions.className = 'ml-auto flex shrink-0 items-center justify-end gap-3';
      actions.setAttribute('data-hq-modal-footer-actions', 'true');
      Array.from(footer.children).forEach((child) => actions.appendChild(child));
      footer.appendChild(actions);
    }

    notice = document.createElement('p');
    notice.className = 'invisible min-w-0 flex-1 text-sm font-semibold leading-6 text-rose-500';
    notice.setAttribute('data-hq-modal-inline-notice', 'true');
    footer.prepend(notice);
    return notice;
  }

  function clearModalInlineNotice(modal) {
    if (!modal) return;

    const timer = modalInlineNoticeTimers.get(modal);
    if (timer) {
      window.clearTimeout(timer);
      modalInlineNoticeTimers.delete(modal);
    }

    const notice = ensureModalInlineNotice(modal);
    if (!notice) return;
    notice.textContent = '';
    notice.classList.add('hidden');
    notice.classList.add('invisible');
  }

  function showModalInlineNotice(modal, message, duration = 3000) {
    const notice = ensureModalInlineNotice(modal);
    if (!notice) return;

    clearModalInlineNotice(modal);
    notice.textContent = message;
    notice.classList.remove('hidden');
    notice.classList.remove('invisible');

    const timer = window.setTimeout(() => {
      notice.textContent = '';
      notice.classList.add('hidden');
      notice.classList.add('invisible');
      modalInlineNoticeTimers.delete(modal);
    }, duration);

    modalInlineNoticeTimers.set(modal, timer);
  }

  function ensureRequiredMarker(target) {
    if (!target) return;
    if (target.querySelector?.('[data-hq-required-marker]')) return;

    const hasExistingStaticMarker = Array.from(target.querySelectorAll('span')).some((el) => {
      const text = (el.textContent || '').trim();
      return text === '*' || text === '＊';
    });
    if (hasExistingStaticMarker) return;

    const marker = document.createElement('span');
    marker.className = 'ml-1 text-rose-500';
    marker.textContent = '*';
    marker.setAttribute('data-hq-required-marker', 'true');
    marker.setAttribute('aria-hidden', 'true');
    target.appendChild(marker);
  }

  function findFieldLabel(field) {
    if (!(field instanceof HTMLElement)) return null;
    return field.closest('.flex.flex-col.gap-2, .space-y-2')?.querySelector('label') || null;
  }

  function ensureStoreRequiredMarkers(modal, mode) {
    if (!modal) return;

    const coverGroup = modal
      .querySelector(mode === 'create' ? '[data-create-cover-zone]' : '[data-edit-cover-zone]')
      ?.parentElement;
    ensureRequiredMarker(coverGroup?.querySelector('label'));

    [
      `[data-hq-store-${mode}-name]`,
      `[data-hq-store-${mode}-region]`,
      `[data-hq-store-${mode}-manager]`,
      `[data-hq-store-${mode}-phone]`,
      `[data-hq-store-${mode}-address]`,
      `[data-hq-store-${mode}-lat]`,
      `[data-hq-store-${mode}-lng]`,
    ].forEach((selector) => {
      const field = modal.querySelector(selector);
      if (field instanceof HTMLInputElement || field instanceof HTMLSelectElement) {
        field.required = true;
      }
      ensureRequiredMarker(findFieldLabel(field));
    });

    if (mode === 'create') {
      const accountField = modal.querySelector('[data-store-account]');
      const passwordField = modal.querySelector('[data-hq-store-create-password]');
      if (accountField instanceof HTMLInputElement) accountField.required = true;
      if (passwordField instanceof HTMLInputElement) passwordField.required = true;
      ensureRequiredMarker(findFieldLabel(accountField));
      ensureRequiredMarker(findFieldLabel(passwordField));
    }
  }

  function failStoreValidation(modal, field, message) {
    showModalInlineNotice(modal, message);
    field?.focus?.();
    return null;
  }

  function getFieldValue(field) {
    return field?.value?.trim() || '';
  }

  function isValidTaiwanMobile(value) {
    const normalized = String(value || '').replace(/[^\d]/g, '');
    return /^09\d{8}$/.test(normalized);
  }

  function getStoreValidationMessages() {
    return {
      cover: '請上傳分店封面照',
      name: '請輸入分店名稱',
      region: '請選擇所屬區域',
      account: '請輸入分店帳號',
      password: '請輸入初始密碼',
      manager: '請輸入店長名稱',
      phoneRequired: '請輸入店長手機號碼',
      phoneInvalid: '請輸入正確的店長手機號碼',
      address: '請輸入門市地址',
      latitudeRequired: '請輸入緯度',
      longitudeRequired: '請輸入經度',
      latitudeLabel: '緯度',
      longitudeLabel: '經度',
      coordinateInvalid: (label) => `請輸入有效的${label}`,
      coordinateOutOfRange: (label) => `${label}超出範圍`,
    };
  }

  function validateCoordinate(rawValue, label, min, max) {
    const numericValue = Number(rawValue);
    const messages = getStoreValidationMessages();
    if (!Number.isFinite(numericValue)) {
      return { ok: false, message: messages.coordinateInvalid(label) };
    }
    if (numericValue < min || numericValue > max) {
      return { ok: false, message: messages.coordinateOutOfRange(label) };
    }
    return { ok: true, value: numericValue };
  }

  function validateStoreForm(modal, options = {}) {
    if (!modal) return null;

    const messages = getStoreValidationMessages();
    const {
      mode = 'create',
      requireCover = false,
      coverFile = null,
      coverUrl = null,
    } = options;

    const isCreate = mode === 'create';
    const coverInput = modal.querySelector(isCreate ? '[data-create-cover-input]' : '[data-edit-cover-input]');
    const nameInput = modal.querySelector(`[data-hq-store-${mode}-name]`);
    const regionInput = modal.querySelector(`[data-hq-store-${mode}-region]`);
    const managerInput = modal.querySelector(`[data-hq-store-${mode}-manager]`);
    const phoneInput = modal.querySelector(`[data-hq-store-${mode}-phone]`);
    const addressInput = modal.querySelector(`[data-hq-store-${mode}-address]`);
    const latInput = modal.querySelector(`[data-hq-store-${mode}-lat]`);
    const lngInput = modal.querySelector(`[data-hq-store-${mode}-lng]`);
    const accountInput = isCreate ? modal.querySelector('[data-store-account]') : null;
    const passwordInput = isCreate ? modal.querySelector('[data-hq-store-create-password]') : null;

    const storeName = getFieldValue(nameInput);
    const regionId = getFieldValue(regionInput);
    const managerName = getFieldValue(managerInput);
    const managerPhone = getFieldValue(phoneInput);
    const address = getFieldValue(addressInput);
    const latitudeRaw = getFieldValue(latInput);
    const longitudeRaw = getFieldValue(lngInput);
    const account = getFieldValue(accountInput);
    const password = getFieldValue(passwordInput);

    if (requireCover && !coverFile && !coverUrl) {
      return failStoreValidation(modal, coverInput, messages.cover);
    }
    if (!storeName) {
      return failStoreValidation(modal, nameInput, messages.name);
    }
    if (!regionId) {
      return failStoreValidation(modal, regionInput, messages.region);
    }
    if (isCreate && !account) {
      return failStoreValidation(modal, accountInput, messages.account);
    }
    if (isCreate && !password) {
      return failStoreValidation(modal, passwordInput, messages.password);
    }
    if (!managerName) {
      return failStoreValidation(modal, managerInput, messages.manager);
    }
    if (!managerPhone) {
      return failStoreValidation(modal, phoneInput, messages.phoneRequired);
    }
    if (!isValidTaiwanMobile(managerPhone)) {
      return failStoreValidation(modal, phoneInput, messages.phoneInvalid);
    }
    if (!address) {
      return failStoreValidation(modal, addressInput, messages.address);
    }
    if (!latitudeRaw) {
      return failStoreValidation(modal, latInput, messages.latitudeRequired);
    }
    if (!longitudeRaw) {
      return failStoreValidation(modal, lngInput, messages.longitudeRequired);
    }

    const latitudeResult = validateCoordinate(latitudeRaw, messages.latitudeLabel, -90, 90);
    if (!latitudeResult.ok) {
      return failStoreValidation(modal, latInput, latitudeResult.message);
    }

    const longitudeResult = validateCoordinate(longitudeRaw, messages.longitudeLabel, -180, 180);
    if (!longitudeResult.ok) {
      return failStoreValidation(modal, lngInput, longitudeResult.message);
    }

    const payload = {
      storeName,
      managerName,
      managerPhone,
      address,
      regionId: Number(regionId),
      latitude: latitudeResult.value,
      longitude: longitudeResult.value,
    };

    if (isCreate) {
      payload.account = account;
      payload.password = password;
    }

    if (coverUrl) {
      payload.coverUrl = coverUrl;
    }

    return payload;
  }

  function bindStoreRequiredValidation(modal, mode) {
    if (!modal) return;

    const messages = getStoreValidationMessages();
    const fields = [
      { selector: `[data-hq-store-${mode}-name]`, message: messages.name },
      { selector: `[data-hq-store-${mode}-region]`, message: messages.region },
      { selector: `[data-hq-store-${mode}-manager]`, message: messages.manager },
      { selector: `[data-hq-store-${mode}-address]`, message: messages.address },
      { selector: `[data-hq-store-${mode}-lat]`, message: messages.latitudeRequired },
      { selector: `[data-hq-store-${mode}-lng]`, message: messages.longitudeRequired },
    ];

    if (mode === 'create') {
      fields.push({ selector: '[data-store-account]', message: messages.account });
      fields.push({ selector: '[data-hq-store-create-password]', message: messages.password });
    }

    fields.forEach(({ selector, message }) => {
      const field = modal.querySelector(selector);
      if (!(field instanceof HTMLInputElement || field instanceof HTMLSelectElement)) return;
      if (field.dataset.requiredValidationBound === 'true') return;

      field.addEventListener('blur', () => {
        if (!getFieldValue(field)) {
          showModalInlineNotice(modal, message);
        }
      });

      field.dataset.requiredValidationBound = 'true';
    });
  }

  function bindStorePhoneValidation(modal, mode) {
    if (!modal) return;

    const phoneInput = modal.querySelector(`[data-hq-store-${mode}-phone]`);
    if (!(phoneInput instanceof HTMLInputElement) || phoneInput.dataset.phoneValidationBound === 'true') return;

    const messages = getStoreValidationMessages();

    phoneInput.addEventListener('blur', () => {
      const value = getFieldValue(phoneInput);
      if (!value) {
        showModalInlineNotice(modal, messages.phoneRequired);
        return;
      }
      if (!isValidTaiwanMobile(value)) {
        showModalInlineNotice(modal, messages.phoneInvalid);
      }
    });

    phoneInput.dataset.phoneValidationBound = 'true';
  }

  function setImagePreview(previewEl, url, isAvatar, hintEl) {
    if (!previewEl) return;
    if (url) {
      previewEl.innerHTML = `<img src="${url}" class="w-full h-full object-cover" />`;
      if (hintEl) hintEl.textContent = '已選擇圖片';
      return;
    }

    const icon = isAvatar ? 'add_a_photo' : 'image';
    previewEl.innerHTML = `<span class="material-symbols-outlined text-2xl">${icon}</span>`;
    if (hintEl) hintEl.textContent = isAvatar ? '建議尺寸 200x200px' : '建議比例 16:9';
  }

  function bindUpload(inputEl, previewEl, hintEl, isAvatar, onUploaded) {
    if (!inputEl) return;
    inputEl.addEventListener('change', async () => {
      const file = inputEl.files?.[0];
      if (!file) return;

      const localUrl = URL.createObjectURL(file);
      setImagePreview(previewEl, localUrl, isAvatar, hintEl);
      if (hintEl) hintEl.textContent = '圖片上傳中...';

      try {
        const cloudUrl = await BrandAPI.uploadImage(file, 'stores');
        setImagePreview(previewEl, cloudUrl, isAvatar, hintEl);
        onUploaded(cloudUrl);
      } catch (err) {
        BrandAPI.toast(`圖片上傳失敗：${err.message}`, 'error');
        setImagePreview(previewEl, null, isAvatar, hintEl);
        onUploaded(null);
      } finally {
        URL.revokeObjectURL(localUrl);
        inputEl.value = '';
      }
    });
  }

  function populateRegionSelect(selectEl, includeAll = true) {
    if (!selectEl) return;
    while (selectEl.options.length > (includeAll ? 1 : 0)) {
      selectEl.remove(includeAll ? 1 : 0);
    }
    regions.forEach((region) => {
      const opt = document.createElement('option');
      opt.value = region.id;
      opt.textContent = region.name;
      selectEl.appendChild(opt);
    });
  }

  function applyFilters() {
    if (!tbody) return;

    const query = (searchInp?.value || '').toLowerCase().trim();
    const regionId = regionSel?.value || 'all';
    const filtered = allStores.filter((store) => {
      const matchQuery = !query || (store.storeName || '').toLowerCase().includes(query);
      const matchRegion = regionId === 'all' || String(store.regionId) === String(regionId);
      return matchQuery && matchRegion;
    });

    const countEl = document.querySelector('[data-store-count]');
    tbody.innerHTML = '';

    if (!filtered.length) {
      tbody.innerHTML = '<tr><td colspan="7" class="px-6 py-8 text-center text-sm text-slate-400">目前沒有分店資料</td></tr>';
      if (countEl) countEl.textContent = '共 0 間分店';
      return;
    }

    if (countEl) countEl.textContent = `共 ${filtered.length} 間分店 / 全部 ${allStores.length} 間`;

    filtered.forEach((store) => {
      const statusText =
        store.status === 'active' ? '營業中' : '休息中';
      const statusClass =
        store.status === 'active'
          ? 'bg-green-100 text-green-700'
          : 'bg-slate-100 text-slate-500';

      tbody.insertAdjacentHTML(
        'beforeend',
        `
        <tr class="hover:bg-slate-50 transition-colors" data-store-row
            data-store-id="${store.storeId || store.id}"
            data-store-name="${(store.storeName || '').replace(/"/g, '&quot;')}"
            data-account="${(store.account || '').replace(/"/g, '&quot;')}"
            data-region-id="${store.regionId || ''}"
            data-manager="${(store.managerName || '').replace(/"/g, '&quot;')}"
            data-phone="${(store.managerPhone || store.storePhone || '').replace(/"/g, '&quot;')}"
            data-address="${(store.address || '').replace(/"/g, '&quot;')}"
            data-cover-url="${(store.coverUrl || '').replace(/"/g, '&quot;')}"
            data-lat="${store.latitude || ''}"
            data-lng="${store.longitude || ''}">
          <td class="px-6 py-4 text-sm font-semibold">${store.storeName || '-'}</td>
          <td class="px-6 py-4 text-sm text-slate-500">${store.avgRating != null ? store.avgRating : '-'}</td>
          <td class="px-6 py-4 text-sm text-slate-500">${store.regionName || '-'}</td>
          <td class="px-6 py-4 text-sm text-slate-500">${store.managerName || '-'}</td>
          <td class="px-6 py-4 text-sm text-slate-500">${store.managerPhone || store.storePhone || '-'}</td>
          <td class="px-6 py-4"><span class="px-2 py-1 rounded-full text-xs font-bold ${statusClass}">${statusText}</span></td>
          <td class="px-6 py-4 text-sm text-center align-middle"><a href="#" data-hq-store-edit-open class="text-primary font-semibold hover:underline">[編輯]</a></td>
        </tr>`
      );
    });
  }

  async function loadAll() {
    try {
      [regions] = await Promise.all([BrandAPI.getRegions()]);
      const detail = await BrandAPI.getBrandDetail();
      allStores = detail.stores || [];

      populateRegionSelect(regionSel, true);
      populateRegionSelect(document.querySelector('[data-hq-store-create-region]'), false);
      populateRegionSelect(document.querySelector('[data-hq-store-edit-region]'), false);

      if (regionLbl) regionLbl.textContent = '全部地區';
      applyFilters();
    } catch (err) {
      if (tbody) {
        tbody.innerHTML = `<tr><td colspan="7" class="px-6 py-8 text-center text-sm text-red-500">載入失敗：${err.message}</td></tr>`;
      }
      console.error(err);
    }
  }

  clearSearchAutofill();
  window.addEventListener('pageshow', clearSearchAutofill);
  searchInp?.addEventListener('pointerdown', unlockSearchInput, { once: true });
  searchInp?.addEventListener('focus', unlockSearchInput, { once: true });
  searchInp?.addEventListener('keydown', unlockSearchInput, { once: true });

  await loadAll();

  searchInp?.addEventListener('input', applyFilters);
  regionSel?.addEventListener('change', () => {
    if (regionLbl) {
      const opt = regionSel.options[regionSel.selectedIndex];
      regionLbl.textContent = opt ? opt.textContent : '全部地區';
    }
    applyFilters();
  });

  const createModal = document.querySelector('[data-hq-store-create-modal]');
  let createCoverFile = null;
  let createCoverPreviewUrl = null;

  if (createModal) {
    ensureStoreRequiredMarkers(createModal, 'create');
    bindStoreRequiredValidation(createModal, 'create');
    bindStorePhoneValidation(createModal, 'create');

    const createCoverInput = createModal.querySelector('[data-create-cover-input]');
    const createCoverPreview = createModal.querySelector('[data-create-cover-preview]');
    const createCoverHint = createModal.querySelector('[data-create-cover-hint]');

    const resetCreateModalState = () => {
      clearModalInlineNotice(createModal);

      if (createCoverPreviewUrl) {
        URL.revokeObjectURL(createCoverPreviewUrl);
        createCoverPreviewUrl = null;
      }

      createCoverFile = null;

      if (createCoverInput) createCoverInput.value = '';

      const fields = createModal.querySelectorAll('input, select, textarea');
      fields.forEach((field) => {
        if (field instanceof HTMLInputElement) {
          if (field.type === 'file') return;
          field.value = '';
          return;
        }
        if (field instanceof HTMLSelectElement) {
          field.selectedIndex = 0;
          return;
        }
        field.value = '';
      });

      setImagePreview(createCoverPreview, null, false, createCoverHint);
    };

    createCoverInput?.addEventListener('change', () => {
      const file = createCoverInput.files?.[0] ?? null;

      if (createCoverPreviewUrl) {
        URL.revokeObjectURL(createCoverPreviewUrl);
        createCoverPreviewUrl = null;
      }

      createCoverFile = file;

      if (!file) {
        setImagePreview(createCoverPreview, null, false, createCoverHint);
        return;
      }

      createCoverPreviewUrl = URL.createObjectURL(file);
      setImagePreview(createCoverPreview, createCoverPreviewUrl, false, createCoverHint);
      if (createCoverHint) createCoverHint.textContent = '已選擇圖片，將於儲存時上傳';
    });

    createModal.addEventListener('hq-store-create-modal-will-close', resetCreateModalState);
    createModal
      .querySelectorAll('[data-hq-store-create-close],[data-hq-store-create-backdrop]')
      .forEach((el) => el.addEventListener('click', () => clearModalInlineNotice(createModal)));
    document
      .querySelector('[data-hq-store-create-open]')
      ?.addEventListener('click', () => clearModalInlineNotice(createModal));

    const saveBtn = createModal.querySelector('[data-hq-store-create-save]');

    if (saveBtn) {
      saveBtn.addEventListener(
        'click',
        async (e) => {
          e.stopImmediatePropagation();
          clearModalInlineNotice(createModal);

          const payload = validateStoreForm(createModal, {
            mode: 'create',
            requireCover: true,
            coverFile: createCoverFile,
          });
          if (!payload) return;

          const orig = saveBtn.innerHTML;

          saveBtn.disabled = true;
          saveBtn.innerHTML = '儲存中...';

          try {
            await BrandAPI.createStoreWithImages(
              payload,
              {
                coverFile: createCoverFile,
              }
            );

            await loadAll();
            clearModalInlineNotice(createModal);
            createModal.dispatchEvent(new CustomEvent('hq-store-create-modal-will-close'));
            createModal.classList.add('hidden');
            document.body.classList.remove('overflow-hidden');
            BrandAPI.toast('新增分店成功');
          } catch (err) {
            showModalInlineNotice(createModal, `新增失敗：${err.message}`);
          } finally {
            saveBtn.disabled = false;
            saveBtn.innerHTML = orig;
          }
        },
        true
      );
    }
  }

  const editModal = document.querySelector('[data-hq-store-edit-modal]');
  let currentEditStoreId = null;
  let editCoverUrl = null;

  if (editModal) {
    ensureStoreRequiredMarkers(editModal, 'edit');
    bindStoreRequiredValidation(editModal, 'edit');
    bindStorePhoneValidation(editModal, 'edit');

    bindUpload(
      editModal.querySelector('[data-edit-cover-input]'),
      editModal.querySelector('[data-edit-cover-preview]'),
      editModal.querySelector('[data-edit-cover-hint]'),
      false,
      (url) => {
        editCoverUrl = url;
      }
    );
    editModal
      .querySelectorAll('[data-hq-store-edit-close],[data-hq-store-edit-backdrop]')
      .forEach((el) => el.addEventListener('click', () => clearModalInlineNotice(editModal)));
  }

  document.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-hq-store-edit-open]');
    if (!btn || !editModal) return;

    const row = btn.closest('[data-store-row]');
    if (!row) return;

    const d = row.dataset;
    currentEditStoreId = d.storeId || null;
    editCoverUrl = d.coverUrl || null;
    clearModalInlineNotice(editModal);

    const set = (sel, val) => {
      const el = editModal.querySelector(sel);
      if (el) el.value = val || '';
    };

    set('[data-hq-store-edit-name]', d.storeName);
    set('[data-hq-store-edit-manager]', d.manager);
    set('[data-hq-store-edit-phone]', d.phone);
    set('[data-hq-store-edit-address]', d.address);
    set('[data-hq-store-edit-lat]', d.lat);
    set('[data-hq-store-edit-lng]', d.lng);

    const regSel = editModal.querySelector('[data-hq-store-edit-region]');
    if (regSel && d.regionId) regSel.value = d.regionId;

    setImagePreview(
      editModal.querySelector('[data-edit-cover-preview]'),
      editCoverUrl || null,
      false,
      editModal.querySelector('[data-edit-cover-hint]')
    );
  });

  const editSaveBtn = document.querySelector('[data-hq-store-edit-save]');
  if (editSaveBtn && editModal) {
    editSaveBtn.addEventListener('click', async () => {
      clearModalInlineNotice(editModal);
      if (!currentEditStoreId) {
        showModalInlineNotice(editModal, '缺少分店 ID');
        return;
      }

      const body = validateStoreForm(editModal, {
        mode: 'edit',
        coverUrl: editCoverUrl,
      });
      if (!body) return;

      body.coverUrl = editCoverUrl || null;

      const orig = editSaveBtn.innerHTML;
      editSaveBtn.disabled = true;
      editSaveBtn.innerHTML = '儲存中...';

      try {
        await BrandAPI.updateStore(currentEditStoreId, body);
        clearModalInlineNotice(editModal);
        BrandAPI.toast('分店更新成功');
        editModal.classList.add('hidden');
        document.body.classList.remove('overflow-hidden');
        currentEditStoreId = null;
        await loadAll();
      } catch (err) {
        showModalInlineNotice(editModal, `更新失敗：${err.message}`);
      } finally {
        editSaveBtn.disabled = false;
        editSaveBtn.innerHTML = orig;
      }
    });
  }
})();
