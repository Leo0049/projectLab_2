/* store-list.js — 店家列表頁完整邏輯 */
(() => {
  // ─── helpers ──────────────────────────────────────────────────────────────
  function getTodayHours(openingHours) {
    if (!openingHours) return null;
    const days = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];
    const today = days[new Date().getDay()];
    try {
      const h = typeof openingHours === 'string' ? JSON.parse(openingHours) : openingHours;
      const d = h[today];
      if (!d) return null;
      if (d.closed) return '今日休息';
      return `${d.open} - ${d.close}`;
    } catch { return null; }
  }

  // ─── 品牌 Logo 背景色（依品牌名首字 hash）────────────────────────────────
  const BRAND_COLORS = ['#FF6B35','#FF8C42','#F7B731','#26C9A8','#4ECDC4','#45B7D1','#A29BFE','#FD79A8'];
  function brandColor(name) {
    let hash = 0;
    for (let i = 0; i < (name || '').length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash);
    return BRAND_COLORS[Math.abs(hash) % BRAND_COLORS.length];
  }

  // ─── 渲染卡片 ─────────────────────────────────────────────────────────────
  function renderCard(store) {
    const todayHours = getTodayHours(store.openingHours);
    const brandInitial = (store.brandName || '').charAt(0).toUpperCase() || '?';
    const hasBrandBadge = !!(store.brandLogoUrl || store.brandName);
    return `
      <div
        onclick="location.href='store.html?id=${store.id}'"
        class="bg-white rounded-[24px] overflow-hidden card-shadow group cursor-pointer hover:-translate-y-1 transition-all duration-300 relative"
        data-store-id="${store.id}"
        data-store-name="${(store.name || store.storeName || '').toLowerCase().replace(/"/g, '')}"
        data-brand-name="${(store.brandName || '').toLowerCase().replace(/"/g, '')}"
        data-rating="${store.rating || 0}">

        <!-- 收藏按鈕 -->
        <button
          onclick="event.stopPropagation(); toggleStoreFavorite(this, ${store.id})"
          class="absolute top-3 left-3 z-20 w-11 h-11 bg-white/90 backdrop-blur-sm rounded-full flex items-center justify-center shadow-md hover:scale-110 transition-all text-gray-400">
          <i data-lucide="heart" class="w-5 h-5"></i>
        </button>

        <div class="h-36 sm:h-48 relative overflow-hidden">
          <img src="${store.imageUrl || store.image || store.coverUrl || 'images/store-placeholder.svg'}"
               class="w-full h-full object-cover transition-transform duration-500 group-hover:scale-110"
               loading="lazy"
               alt="${store.name || store.storeName || '店家封面'}"
               onerror="this.onerror=null;this.src='images/store-placeholder.svg'">
          <!-- 評分 badge -->
          <div class="absolute top-4 right-4 bg-white/90 backdrop-blur-sm px-3 py-1 rounded-full shadow-sm flex items-center gap-1 group-hover:bg-brand-orange group-hover:text-white transition-colors">
            <i data-lucide="star" class="w-3 h-3 fill-current"></i>
            <span class="text-xs font-bold">${store.rating ? Number(store.rating).toFixed(1) : '--'}</span>
          </div>
          <!-- 外送 badge -->
          ${store.allowsDelivery ? `
          <div class="absolute bottom-3 left-3 z-10 bg-white/90 backdrop-blur-sm text-brand-orange px-2 py-0.5 rounded-full flex items-center gap-1 shadow-sm text-xs font-semibold">
            <i data-lucide="bike" class="w-3.5 h-3.5"></i>
            <span>外送</span>
          </div>` : ''}
        </div>

        <div class="p-4 sm:p-5">
          <div class="flex justify-between items-start mb-2 text-brand-text-dark">
            <div class="flex items-center gap-2 min-w-0">
              ${hasBrandBadge ? `
              <div class="flex-shrink-0 rounded-full overflow-hidden border border-gray-100 shadow-sm"
                   style="width:48px;height:48px;background:${brandColor(store.brandName)}">
                ${store.brandLogoUrl
                  ? `<img src="${store.brandLogoUrl}" class="w-full h-full object-cover" alt="${store.brandName || ''}"
                          onerror="this.style.display='none';this.nextElementSibling.style.display='flex'">`
                  : ''}
                <span class="w-full h-full flex items-center justify-center text-white font-bold text-sm"
                      style="${store.brandLogoUrl ? 'display:none' : ''}">${brandInitial}</span>
              </div>` : ''}
              <h3 class="font-extrabold text-lg truncate">${store.name || store.storeName || ''}</h3>
            </div>
            <div class="flex items-center text-brand-orange">
              <i data-lucide="footprints" class="w-3.5 h-3.5"></i>
              <span class="text-xs font-black">${store.distance != null ? store.distance + ' km' : '-- km'}</span>
            </div>
          </div>
          ${store.minDeliveryAmount != null ? `
          <div class="flex items-center gap-2 mb-4 text-xs text-brand-text-gray">
            <span>•</span><span>最低外送 $${store.minDeliveryAmount}</span>
          </div>` : ''}
          <div class="flex items-center justify-between">
            ${todayHours ? `
            <div class="flex items-center gap-1.5 text-xs text-brand-text-gray">
              <i data-lucide="clock" class="w-3 h-3 text-gray-400"></i>
              <span>${todayHours}</span>
            </div>` : '<div></div>'}
            <button aria-label="開始點餐" class="bg-brand-orange/10 text-brand-orange p-3 rounded-xl group-hover:bg-brand-orange group-hover:text-white transition-all">
              <i data-lucide="shopping-cart" class="w-5 h-5"></i>
            </button>
          </div>
        </div>
      </div>
    `;
  }

  // ─── 狀態 ─────────────────────────────────────────────────────────────────
  let allStores = [];          // 全部店家（從 API 載入）
  let nearbyStores = [];       // 基準附近店家（不受搜尋影響）
  let selectedBrandId = null;  // 下拉選中的品牌 ID（供 API 篩選）
  let fuzzyKeyword = '';       // 文字輸入的模糊搜尋詞
  let minRating = null;        // 評分篩選
  let headerSearchTimer = null; // debounce timer
  let filterTimer = null;       // debounce timer for API filter

  const grid = document.getElementById('store-grid');

  // ─── 渲染結果到 grid ───────────────────────────────────────────────────────
  function renderStores(stores) {
    if (!grid) return;
    if (stores.length === 0) {
      // 空狀態除了說「沒有」，也要告訴使用者下一步可以做什麼
      grid.innerHTML = `
        <div class="col-span-full flex flex-col items-center justify-center px-6 py-16 text-center">
          <div class="w-16 h-16 rounded-full bg-brand-orange/10 flex items-center justify-center mb-4">
            <i data-lucide="store" class="w-7 h-7 text-brand-orange"></i>
          </div>
          <p class="font-bold text-brand-text-dark mb-1">找不到符合條件的店家</p>
          <p class="text-sm text-brand-text-gray max-w-xs">試著放寬評分條件、清除品牌篩選，或換一個位置再找找看。</p>
        </div>`;
      if (window.lucide) lucide.createIcons();
    } else {
      grid.innerHTML = stores.map(renderCard).join('');
    }
    if (window.lucide) lucide.createIcons();
  }

  // ─── 呼叫後端篩選 API ─────────────────────────────────────────────────────
  async function applyFiltersViaAPI() {
    const lat = localStorage.getItem('userLat') || '24.1505191';
    const lng = localStorage.getItem('userLng') || '120.6510085';

    const params = new URLSearchParams({ lat, lng, size: 100 });
    if (selectedBrandId)  params.set('brandId',   selectedBrandId);
    if (minRating != null) params.set('minRating', minRating);

    if (!grid) return;
    grid.innerHTML = '<div class="col-span-full py-10 text-center text-gray-400">篩選中...</div>';
    try {
      const res = await NavAuth.fetchAPI(`/api/stores/nearby?${params}`);
      const stores = Array.isArray(res?.stores) ? res.stores : (Array.isArray(res) ? res : []);
      renderStores(stores);
    } catch (e) {
      console.error('篩選失敗', e);
      grid.innerHTML = '<div class="col-span-full py-10 text-center text-red-400">篩選失敗，請稍後再試</div>';
    }
  }

  // ─── 篩選並重繪（client-side，用於無品牌選擇時的 fuzzy 篩選）──────────────
  function applyFilters() {
    // 若有選品牌（by ID）或評分 → 走後端 API
    if (selectedBrandId || minRating != null) {
      clearTimeout(filterTimer);
      filterTimer = setTimeout(applyFiltersViaAPI, 0);
      return;
    }

    // 僅 fuzzyKeyword → client-side 過濾已載入的附近店家
    const filtered = allStores.filter(store => {
      const name  = (store.name      || store.storeName || '').toLowerCase();
      const brand = (store.brandName || '').toLowerCase();
      return !fuzzyKeyword || name.includes(fuzzyKeyword) || brand.includes(fuzzyKeyword);
    });
    renderStores(filtered);
  }

  // ─── 品牌下拉 combobox ────────────────────────────────────────────────────
  function initBrandDropdown(brands) {
    const combobox  = document.getElementById('brand-combobox');
    const input     = document.getElementById('brand-search');
    const toggleBtn = document.querySelector('.brand-combobox__toggle');
    const dropdown  = document.getElementById('brand-dropdown');
    const listEl    = document.getElementById('brand-options');
    if (!input || !dropdown || !listEl) return;

    const closeDropdown = () => {
      dropdown.hidden = true;
      input.setAttribute('aria-expanded', 'false');
    };

    const renderOptions = (items) => {
      listEl.innerHTML = '';
      // 「全部品牌」選項
      const allLi = document.createElement('li');
      allLi.className = 'brand-dropdown__item';
      allLi.textContent = '全部品牌';
      allLi.addEventListener('click', () => {
        input.value = '';
        selectedBrandId = null;
        fuzzyKeyword = '';
        // 恢復附近店家
        allStores = [...nearbyStores];
        closeDropdown();
        renderStores(allStores);
      });
      listEl.appendChild(allLi);

      items.forEach(brand => {
        const li = document.createElement('li');
        li.className = 'brand-dropdown__item';
        // 顯示 logo + 名稱
        li.innerHTML = brand.logoUrl
          ? `<span style="display:flex;align-items:center;gap:8px;">
               <img src="${brand.logoUrl}" style="width:20px;height:20px;border-radius:50%;object-fit:cover;" onerror="this.style.display='none'">
               ${brand.brandName}
             </span>`
          : brand.brandName;
        li.addEventListener('click', () => {
          input.value = brand.brandName;
          selectedBrandId = brand.brandId;
          fuzzyKeyword = '';
          closeDropdown();
          applyFilters();
        });
        listEl.appendChild(li);
      });
    };

    const openDropdown = () => {
      const keyword = (input.value || '').trim().toLowerCase();
      const filtered = keyword
        ? brands.filter(b => b.brandName.toLowerCase().includes(keyword))
        : brands;
      renderOptions(filtered);
      dropdown.hidden = false;
      input.setAttribute('aria-expanded', 'true');
    };

    toggleBtn?.addEventListener('click', () => {
      if (dropdown.hidden) openDropdown();
      else closeDropdown();
    });

    input.addEventListener('focus', openDropdown);

    input.addEventListener('input', () => {
      const val = (input.value || '').trim().toLowerCase();
      // 下拉清單即時過濾
      openDropdown();
      // 若已選品牌但使用者又開始打字 → 切換成模糊搜尋模式
      const exact = brands.find(b => b.brandName.toLowerCase() === val);
      if (exact) {
        selectedBrandId = exact.brandId;
        fuzzyKeyword = '';
      } else {
        selectedBrandId = null;
        fuzzyKeyword = val;
      }
      applyFilters();
    });

    document.addEventListener('click', ev => {
      if (!combobox?.contains(ev.target)) closeDropdown();
    });
  }

  // ─── 評分按鈕 ─────────────────────────────────────────────────────────────
  function initRatingButtons() {
    const ratingWrap = document.querySelector('.rating-buttons');
    if (!ratingWrap) return;
    // 預設不篩評分（移除 active）
    ratingWrap.querySelectorAll('.rating-btn').forEach(btn => {
      btn.classList.remove('active');
      btn.addEventListener('click', () => {
        const wasActive = btn.classList.contains('active');
        ratingWrap.querySelectorAll('.rating-btn').forEach(b => b.classList.remove('active'));
        if (!wasActive) {
          btn.classList.add('active');
          const match = btn.textContent.match(/(\d+(?:\.\d+)?)/);
          minRating = match ? Number(match[1]) : null;
        } else {
          minRating = null;
        }
        applyFilters();
      });
    });
  }

  // ─── 收藏 toggle ──────────────────────────────────────────────────────────
  window.toggleStoreFavorite = async (btn, storeId) => {
    const userId = localStorage.getItem('JOIN_USER_ID');
    if (!userId) {
      alert('請先登入以收藏店家');
      window.location.href = `./login.html?redirect=${encodeURIComponent(window.location.href)}`;
      return;
    }
    try {
      btn.disabled = true;
      const result = await NavAuth.toggleFavorite(userId, storeId);
      if (result && result.status === 'added') {
        btn.classList.add('text-brand-orange');
        btn.innerHTML = '<i data-lucide="heart" class="w-5 h-5" style="fill:currentColor"></i>';
      } else {
        btn.classList.remove('text-brand-orange');
        btn.innerHTML = '<i data-lucide="heart" class="w-5 h-5"></i>';
      }
      if (window.lucide) lucide.createIcons();
    } catch (e) {
      console.error('收藏失敗', e);
    } finally {
      btn.disabled = false;
    }
  };

  // ─── 更新區塊標題 ────────────────────────────────────────────────────────
  function updateSectionTitle(text) {
    const title = document.querySelector('.section-title');
    if (title) title.textContent = text;
  }

  // ─── 頁首搜尋框 ──────────────────────────────────────────────────────────
  function goToSearch(keyword) {
    window.location.href = `search_result.html?q=${encodeURIComponent(keyword)}`;
  }

  function initHeaderSearch() {
    const input = document.getElementById('header-search-input');
    if (!input) return;

    input.addEventListener('input', () => {
      clearTimeout(headerSearchTimer);
      const keyword = input.value.trim();
      if (!keyword) return;
      headerSearchTimer = setTimeout(() => goToSearch(keyword), 500);
    });

    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        clearTimeout(headerSearchTimer);
        const keyword = input.value.trim();
        if (keyword) goToSearch(keyword);
      }
    });
  }

  // ─── 主流程 ───────────────────────────────────────────────────────────────
  async function main() {
    if (!grid) return;
    grid.innerHTML = '<div class="col-span-full py-10 text-center text-gray-400">正在載入附近店家...</div>';

    const lat = localStorage.getItem('userLat') || '24.1505191';
    const lng = localStorage.getItem('userLng') || '120.6510085';

    try {
      const [storesRes, brandsRes] = await Promise.all([
        NavAuth.fetchAPI(`/api/stores/nearby?lat=${lat}&lng=${lng}&size=12`, { cache: true, ttl: 300000 }),
        NavAuth.fetchAPI('/api/brands', { cache: true, ttl: 3600000 }),
      ]);

      allStores = Array.isArray(storesRes?.stores) ? storesRes.stores : (Array.isArray(storesRes) ? storesRes : []);
      nearbyStores = [...allStores]; // 保留基準（供清除搜尋後還原）
      const brands = Array.isArray(brandsRes) ? brandsRes : [];

      applyFilters();
      initBrandDropdown(brands);
      initRatingButtons();
      initHeaderSearch();

      // 定位更新後重載附近店家
      window.addEventListener('locationChanged', () => {
        const newLat = localStorage.getItem('userLat') || '24.1505191';
        const newLng = localStorage.getItem('userLng') || '120.6510085';
        NavAuth.fetchAPI(`/api/stores/nearby?lat=${newLat}&lng=${newLng}&size=12`, { cache: true, ttl: 300000 })
          .then(res => {
            allStores = Array.isArray(res?.stores) ? res.stores : (Array.isArray(res) ? res : []);
            nearbyStores = [...allStores];
            updateSectionTitle('附近熱門店家');
            applyFilters();
          })
          .catch(() => {});
      });

    } catch (err) {
      console.error('載入失敗', err);
      grid.innerHTML = '<div class="col-span-full py-10 text-center text-red-400">載入失敗，請重新整理頁面</div>';
    }
  }

  document.addEventListener('DOMContentLoaded', main);
})();
