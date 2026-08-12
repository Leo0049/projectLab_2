/**
 * Common frontend utilities for JOIN 揪飲
 * Handles authentication, API calls, and shared UI elements.
 */

if (!window.API_BASE_URL) {
    window.API_BASE_URL = (() => {
    if (window.JOIN_API_BASE && typeof window.JOIN_API_BASE === 'string') {
        return window.JOIN_API_BASE.replace(/\/$/, '');
    }

        // If port is 8081 (standard frontend dev/prod combo), the API is often on 8081 or 8082
        // If the port is NOT 8082, we might want to default to 8082 for local dev
        if (location.port && location.port !== '8082' && (location.hostname === 'localhost' || location.hostname === '127.0.0.1')) {
            return `${location.protocol}//${location.hostname}:8082`;
    }

    return location.origin;
})();
}
if (typeof API_BASE_URL === 'undefined') {
    var API_BASE_URL = window.API_BASE_URL;
}

const NavAuth = {
    getApiUrl(endpoint) {
        if (!endpoint) return API_BASE_URL;
        if (/^https?:\/\//i.test(endpoint)) return endpoint;
        return `${API_BASE_URL}${endpoint}`;
    },

    /**
     * Normalize specification names (e.g. '標準' -> '固定')
     */
    normalizeSpec(s) {
        if (!s) return s;
        const normalized = String(s).trim();
        return (normalized === '標準杯' || normalized === '標準' || normalized === '正常' || normalized === '正常冰' || normalized === '正常甜') ? '固定' : normalized;
    },

    /**
     * Get correct relative path to a frontend file (e.g. login.html)
     * Handles pages in subdirectories (like /merchant/)
     */
    getFrontendPath(file) {
        const path = window.location.pathname;
        const isSubDir = path.includes('/merchant/') || path.includes('/store/') || path.includes('/Brand/');
        const prefix = isSubDir ? '../' : './';
        return `${prefix}${file.replace(/^\//, '')}`;
    },

    /**
     * Fetch API wrapper with error handling, JSON parsing, and optional caching
     */
    async fetchAPI(endpoint, options = {}) {
        const url = this.getApiUrl(endpoint);
        
        // Caching Logic
        const cacheKey = `cache_${url}`;
        if (options.cache && options.method?.toUpperCase() !== 'POST') {
            const cached = sessionStorage.getItem(cacheKey);
            if (cached) {
                try {
                    const { data, timestamp } = JSON.parse(cached);
                    const ttl = options.ttl || 300000; // Default 5 minutes
                    if (Date.now() - timestamp < ttl) {
                        return data;
                    }
                } catch (e) {
                    sessionStorage.removeItem(cacheKey);
                }
            }
        }

        const token = localStorage.getItem('JOIN_TOKEN');
        const defaultHeaders = {
            'Content-Type': 'application/json',
        };

        const config = { ...options };
        if (config.headers) {
            config.headers = { ...defaultHeaders, ...config.headers };
        } else {
            config.headers = defaultHeaders;
        }

        // Remove our custom cache boolean before passing to native fetch
        // Also remove 'ttl' which is our custom property
        delete config.cache;
        delete config.ttl;
        delete config.raw;

        if (token && !config.headers.Authorization) {
            config.headers.Authorization = `Bearer ${token}`;
        }

        try {
            // ─── Timeout Protection ─────────────────────────────────
            const timeoutMs = options.timeout || 10000; // Default 10 seconds
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
            config.signal = controller.signal;

            let response;
            try {
                response = await fetch(url, config);
            } finally {
                clearTimeout(timeoutId);
            }
            const contentType = response.headers.get("content-type");
            const isJson = Boolean(contentType && contentType.indexOf("application/json") !== -1);
            const body = isJson ? await response.json() : await response.text();

            if (!response.ok) {
                if (body && typeof body === 'object') {
                    // Handle both V1 (msg) and V2 (message, error) error structures
                    throw new Error(body.msg || body.message || body.error || `API Error: ${response.status}`);
                }
                // Avoid throwing raw HTML (e.g. Spring error pages) as error message
                const isHtml = typeof body === 'string' && body.trimStart().startsWith('<');
                throw new Error(isHtml ? `伺服器錯誤 (${response.status})，請稍後再試` : (body || `API Error: ${response.status}`));
            }

            let result = body;

            // V1 Result Wrapper Handling (if not requested as raw)
            if (!options.raw && body && typeof body === 'object' && body.code !== undefined) {
                if (String(body.code) === '200' || String(body.code) === '0' || String(body.code) === 'success') {
                    result = (body.data !== undefined) ? body.data : body;
                } else {
                    throw new Error(body.msg || body.message || 'API error');
            }
            }

            // Save to cache if enabled
            if (options.cache && options.method?.toUpperCase() !== 'POST') {
                sessionStorage.setItem(cacheKey, JSON.stringify({
                    data: result,
                    timestamp: Date.now()
                }));
            }

            return result;
        } catch (error) {
            if (error.name === 'AbortError') {
                console.error(`Request timeout for ${url} (${options.timeout || 10000}ms)`);
                throw new Error('請求超時，請檢查網路連線後重試');
            }
            console.error(`Fetch error for ${url}:`, error);
            throw error;
        }
    },

    /**
     * Update navbar based on authentication status
     */
    checkAuth() {
        const userId = localStorage.getItem('JOIN_USER_ID');
        const userAvatar = userId ? localStorage.getItem(`userAvatar_${userId}`) : null;
        const navContainer = document.querySelector('[data-purpose="user-nav"]');

        if (userId && navContainer) {
            // User is logged in
            const loginBtn = navContainer.querySelector('button.bg-brand-orange');
            const avatarWrapper = document.getElementById('user-dropdown-wrapper');
            const userAvatarImg = document.getElementById('nav-user-avatar');

            if (loginBtn) loginBtn.classList.add('hidden');
            if (avatarWrapper) {
                avatarWrapper.classList.remove('hidden');
                // Ensure the avatar is updated
                if (userAvatarImg) {
                    userAvatarImg.src = userAvatar || 'images/default_avatar.png';
                    userAvatarImg.onerror = () => { userAvatarImg.src = 'images/default_avatar.png'; };
                }
            }
        } else if (navContainer) {
            // User is NOT logged in
            const loginBtn = navContainer.querySelector('button.bg-brand-orange');
            const avatarWrapper = document.getElementById('user-dropdown-wrapper');

            if (loginBtn) {
                loginBtn.classList.remove('hidden');
                loginBtn.onclick = () => window.location.href = this.getFrontendPath('auth/login.html');
            }
            if (avatarWrapper) avatarWrapper.classList.add('hidden');
        }
    },

    /**
     * Logout user and redirect to homepage
     */
    logout() {
        const logoutUserId = localStorage.getItem('JOIN_USER_ID');
        if (logoutUserId) localStorage.removeItem(`userAvatar_${logoutUserId}`);
        localStorage.removeItem('JOIN_USER_ID');
        localStorage.removeItem('JOIN_USER_NAME');
        localStorage.removeItem('JOIN_TOKEN');
        localStorage.removeItem('userRole');
        localStorage.removeItem('user');
        localStorage.removeItem('userLat');
        localStorage.removeItem('userLng');
        localStorage.removeItem('userLocationName');
        localStorage.removeItem('userAddress');
        localStorage.removeItem('JOIN_LOCATION_MODAL_SHOWN');
        sessionStorage.removeItem('activeGroupToken');
        sessionStorage.removeItem('nearbyStores');
        window.location.href = this.getFrontendPath('auth/login.html');
    },

    /**
     * Initialize location modal logic if present
     */
    initLocationModal() {
        const modal = document.getElementById('locationModal');
        const trigger = document.getElementById('locationTrigger');
        const closeBtn = document.getElementById('closeModalBtn');
        const confirmBtn = document.getElementById('confirmLocationBtn');
        const locationText = document.getElementById('nav-location-text');
        const addressInput = document.getElementById('modal-address-input');

        let map = null;
        let marker = null;

        // Load saved location name to navbar
        const savedLocation = localStorage.getItem('userLocationName');
        if (savedLocation && locationText) {
            locationText.innerText = savedLocation;
        } else if (locationText) {
            locationText.innerText = '尚未定位';
        }

        if (!modal) return;

        const openModal = () => {
            modal.classList.remove('hidden');
            document.body.classList.add('overflow-hidden');

            // Initialize map only once per open or resize
            setTimeout(() => {
                const storedLat = localStorage.getItem('userLat');
                const storedLng = localStorage.getItem('userLng');
                const defaultLat = parseFloat(storedLat) || 24.1505191;
                const defaultLng = parseFloat(storedLng) || 120.6510085;

                if (!map) {
                    map = L.map('map-container').setView([defaultLat, defaultLng], 15);
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                        attribution: '© OpenStreetMap contributors'
                    }).addTo(map);

                    marker = L.marker([defaultLat, defaultLng], { draggable: true }).addTo(map);

                    marker.on('dragend', async function (e) {
                        const position = marker.getLatLng();
                        await reverseGeocode(position.lat, position.lng);
                    });

                    // 只有使用者有儲存過座標才做 reverse geocode，避免 hardcoded 預設座標填入地址欄
                    if (!addressInput.value && storedLat && storedLng) {
                        reverseGeocode(defaultLat, defaultLng);
                    }
                } else {
                    map.invalidateSize();
                    map.setView([defaultLat, defaultLng]);
                    marker.setLatLng([defaultLat, defaultLng]);
                }
            }, 100);
        };

        const closeModal = () => {
            modal.classList.add('hidden');
            document.body.classList.remove('overflow-hidden');
        };

        const reverseGeocode = async (lat, lng) => {
            if (!window.MapLogic) return;
            const address = await window.MapLogic.reverseGeocode(lat, lng);
            if (address) {
                addressInput.value = address;
                addressInput.dataset.lat = lat;
                addressInput.dataset.lng = lng;
            }
        };

        const forwardGeocode = async (address) => {
            if (!address || !window.MapLogic) return;

            const result = await window.MapLogic.geocode(address);
            if (result) {
                map.setView([result.lat, result.lng], 16);
                marker.setLatLng([result.lat, result.lng]);

                addressInput.value = result.display_name;
                addressInput.dataset.lat = result.lat;
                addressInput.dataset.lng = result.lng;
            } else {
                console.warn('No results found for address:', address);
            }
        };

        if (addressInput) {
            addressInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    forwardGeocode(addressInput.value);
                }
            });
        }

        const searchBtn = document.getElementById('modal-search-btn');
        if (searchBtn) {
            searchBtn.addEventListener('click', () => {
                forwardGeocode(addressInput.value);
            });
        }

        if (trigger) trigger.addEventListener('click', openModal);
        if (closeBtn) closeBtn.addEventListener('click', closeModal);

        // Auto-locate button
        const autoLocateBtn = modal.querySelector('button.bg-brand-orange.text-white');
        if (autoLocateBtn) {
            autoLocateBtn.addEventListener('click', async () => {
                try {
                    autoLocateBtn.disabled = true;
                    autoLocateBtn.innerHTML = '<i data-lucide="refresh-cw" class="w-5 h-5 animate-spin"></i><span>定位中...</span>';
                    if (window.lucide) lucide.createIcons();

                    if (window.MapLogic) {
                        const coords = await window.MapLogic.getUserLocation();
                        if (map) map.setView([coords.lat, coords.lng], 16);
                        if (marker) marker.setLatLng([coords.lat, coords.lng]);

                        // 儲存 GPS 座標，確認後頁首顯示「當前位置」
                        addressInput.dataset.lat = coords.lat;
                        addressInput.dataset.lng = coords.lng;
                        addressInput.value = '當前位置';

                        // 清空手動地址欄位
                        const streetInput = document.getElementById('modal-street');
                        const cityInput = document.getElementById('modal-city');
                        const distInput = document.getElementById('modal-district');
                        if (streetInput) streetInput.value = '';
                        if (cityInput) cityInput.value = '';
                        if (distInput) {
                            distInput.value = '';
                            distInput.disabled = true;
                            distInput.placeholder = '請先選擇縣市';
                        }

                        // 更新定位反映欄位為「使用當前的定位」
                        const gpsRow = document.getElementById('modal-gps-status-row');
                        const gpsIndicator = document.getElementById('modal-gps-indicator');
                        const gpsText = document.getElementById('modal-gps-status-text');
                        if (gpsRow) {
                            gpsRow.classList.remove('border-gray-200', 'bg-white');
                            gpsRow.classList.add('border-brand-orange', 'bg-orange-50');
                        }
                        if (gpsIndicator) {
                            gpsIndicator.classList.remove('border-gray-300');
                            gpsIndicator.classList.add('border-brand-orange', 'bg-brand-orange');
                            gpsIndicator.innerHTML = '<div class="w-2 h-2 rounded-full bg-white"></div>';
                        }
                        if (gpsText) {
                            gpsText.textContent = '使用當前的定位';
                            gpsText.classList.remove('text-gray-400');
                            gpsText.classList.add('text-brand-dark', 'font-medium');
                        }
                    }
                } catch (err) {
                    NavAuth.showToast('無法獲取您的位置: ' + err.message, 'error');
                } finally {
                    autoLocateBtn.disabled = false;
                    autoLocateBtn.innerHTML = '<i data-lucide="map-pin" class="w-5 h-5"></i><span>自動定位</span>';
                    if (window.lucide) lucide.createIcons();
                }
            });
        }

        // 解析台灣地址字串，拆出縣市、行政區、街道
        function parseTaiwanAddress(fullAddr) {
            if (!fullAddr) return { city: '', district: '', street: fullAddr || '' };
            const cityMatch = fullAddr.match(/^(.+?[市縣])/);
            if (!cityMatch) return { city: '', district: '', street: fullAddr };
            const city = cityMatch[1];
            const rest = fullAddr.slice(city.length);
            const distMatch = rest.match(/^(.+?[區鎮鄉])/);
            if (!distMatch) return { city, district: '', street: rest };
            const district = distMatch[1];
            const street = rest.slice(district.length);
            return { city, district, street };
        }

        // Common address button
        const commonAddressBtn = document.getElementById('commonAddressBtn');
        const commonAddressPanel = document.getElementById('common-address-panel');
        const commonAddressList = document.getElementById('common-address-list');
        if (commonAddressBtn) {
            commonAddressBtn.addEventListener('click', async () => {
                const userId = localStorage.getItem('JOIN_USER_ID');
                if (!userId) {
                    NavAuth.showToast('請先登入以使用常用地址', 'warning');
                    return;
                }

                // 若面板已開啟則關閉
                if (commonAddressPanel && !commonAddressPanel.classList.contains('hidden')) {
                    commonAddressPanel.classList.add('hidden');
                    return;
                }

                try {
                    commonAddressBtn.disabled = true;
                    const originalHTML = commonAddressBtn.innerHTML;
                    commonAddressBtn.innerHTML = '<i data-lucide="loader-2" class="w-5 h-5 animate-spin"></i><span>讀取中...</span>';
                    if (window.lucide) lucide.createIcons();

                    const addresses = await NavAuth.fetchAPI(`/api/users/${userId}/address`);

                    commonAddressBtn.innerHTML = originalHTML;
                    if (window.lucide) lucide.createIcons();

                    if (!addresses || addresses.length === 0) {
                        NavAuth.showToast('您尚未設定常用地址，請先至個人資料設定。', 'warning');
                        return;
                    }

                    if (commonAddressList) {
                        commonAddressList.innerHTML = addresses.map(addr => {
                            const label = addr.label || '';
                            const fullAddr = addr.address || '';
                            const { city, district, street } = parseTaiwanAddress(fullAddr);
                            const display = fullAddr;
                            const badgeHtml = label
                                ? `<span class="inline-block text-xs font-bold bg-orange-100 text-brand-orange px-2 py-0.5 rounded-full mr-2">${label}</span>`
                                : '';
                            const defaultBadge = addr.isDefault
                                ? `<span class="inline-block text-xs bg-gray-100 text-gray-500 px-2 py-0.5 rounded-full ml-1">預設</span>`
                                : '';
                            return `<button type="button"
                                class="w-full text-left px-4 py-3 rounded-xl bg-gray-50 hover:bg-orange-50 hover:border-brand-orange border border-transparent transition-colors text-sm flex items-center gap-1 flex-wrap"
                                data-city="${city}"
                                data-district="${district}"
                                data-street="${street}"
                                data-lat="${addr.latitude || ''}"
                                data-lng="${addr.longitude || ''}"
                                onclick="window._selectCommonAddress(this)">
                                ${badgeHtml}<span class="text-brand-dark">${display}</span>${defaultBadge}
                            </button>`;
                        }).join('');
                    }

                    if (commonAddressPanel) commonAddressPanel.classList.remove('hidden');
                } catch (err) {
                    console.error('Failed to load common addresses:', err);
                    NavAuth.showToast('讀取常用地址失敗', 'error');
                } finally {
                    commonAddressBtn.disabled = false;
                }
            });

            // 選擇地址後填入並關閉面板
            window._selectCommonAddress = (btn) => {
                const city = btn.dataset.city;
                const district = btn.dataset.district;
                const street = btn.dataset.street;
                const lat = btn.dataset.lat;
                const lng = btn.dataset.lng;

                if (typeof window._fillLocationModalAddress === 'function') {
                    window._fillLocationModalAddress(city, district, street);
                } else {
                    // 降級：直接設值
                    const streetInput = document.getElementById('modal-street');
                    const cityInput = document.getElementById('modal-city');
                    const distInput = document.getElementById('modal-district');
                    if (cityInput) cityInput.value = city;
                    if (distInput) { distInput.value = district; distInput.disabled = false; }
                    if (streetInput) streetInput.value = street;
                    const hidden = document.getElementById('modal-address-input');
                    if (hidden) hidden.value = [city, district, street].filter(Boolean).join('');
                }

                // 填入地址後（syncModalAddress 會清除舊座標），再設定常用地址的座標
                if (lat && lng) {
                    addressInput.dataset.lat = lat;
                    addressInput.dataset.lng = lng;
                }

                if (typeof window._locationModalResetGps === 'function') window._locationModalResetGps();
                if (commonAddressPanel) commonAddressPanel.classList.add('hidden');
            };
        }

        if (confirmBtn) {
            confirmBtn.addEventListener('click', async () => {
                const isGps = addressInput.value === '當前位置';

                // 非 GPS 模式：驗證縣市與行政區必填
                if (!isGps) {
                    const cityInput = document.getElementById('modal-city');
                    const districtInput = document.getElementById('modal-district');
                    const cityVal = cityInput ? cityInput.value.trim() : '';
                    const districtVal = districtInput ? districtInput.value.trim() : '';

                    if (!cityVal) {
                        cityInput && cityInput.focus();
                        NavAuth.showToast('請選擇縣市', 'warning');
                        return;
                    }
                    if (!districtVal) {
                        districtInput && districtInput.focus();
                        NavAuth.showToast('請選擇行政區', 'warning');
                        return;
                    }
                }

                const newLocation = addressInput.value || '臺中市南屯區公益路二段51號';
                let lat = addressInput.dataset.lat ? parseFloat(addressInput.dataset.lat) : null;
                let lng = addressInput.dataset.lng ? parseFloat(addressInput.dataset.lng) : null;

                // 手動輸入時無座標 → 嘗試 forward geocoding 取得正確座標
                if (!lat && newLocation && !isGps && window.MapLogic) {
                    try {
                        const geocodeComponents = {
                            city: document.getElementById('modal-city')?.value || '',
                            district: document.getElementById('modal-district')?.value || '',
                            street: document.getElementById('modal-street')?.value || ''
                        };
                        const result = await window.MapLogic.geocode(newLocation, geocodeComponents);
                        if (result) {
                            lat = result.lat;
                            lng = result.lng;
                            if (map) map.setView([lat, lng], 16);
                            if (marker) marker.setLatLng([lat, lng]);
                        } else {
                            console.warn('Geocode failed, falling back to marker position');
                        }
                    } catch (e) {
                        console.warn('Geocode failed, falling back to marker position:', e);
                    }
                }

                // 最終退守：使用地圖標記座標
                if (!lat && marker) lat = marker.getLatLng().lat;
                if (!lng && marker) lng = marker.getLatLng().lng;

                localStorage.setItem('userLocationName', newLocation);
                localStorage.setItem('userLat', lat);
                localStorage.setItem('userLng', lng);

                if (locationText) locationText.innerText = newLocation;

                closeModal();
                // Custom event to notify other components that location changed
                window.dispatchEvent(new CustomEvent('locationChanged', {
                    detail: { location: newLocation, lat, lng }
                }));
            });
        }

        modal.addEventListener('click', (e) => {
            if (e.target === modal) closeModal();
        });
    },

    /**
     * Check if a store is in user's favorites
     */
    async checkFavorite(userId, storeId) {
        if (!userId || !storeId) return false;
        try {
            const data = await this.fetchAPI(`/api/user-favorites/check?userId=${userId}&storeId=${storeId}`);
            return Boolean(data?.isFavorite ?? data?.isFavorited);
        } catch (error) {
            console.error('Check favorite error:', error);
            return false;
        }
    },

    /**
     * Toggle a store in user's favorites
     */
    async toggleFavorite(userId, storeId) {
        if (!userId) {
            NavAuth.showToast('請先登入以收藏店家', 'warning');
            setTimeout(() => { window.location.href = `${this.getFrontendPath('auth/login.html')}?redirect=${encodeURIComponent(window.location.href)}`; }, 1500);
            return null;
        }
        try {
            return await this.fetchAPI('/api/user-favorites/toggle', {
                method: 'POST',
                body: JSON.stringify({ 
                    userId: Number(userId), 
                    storeId: Number(storeId) 
                })
            });
        } catch (error) {
            console.error('Toggle favorite error:', error);
            throw error;
        }
    },

    /**
     * Update cart UI elements (badge, dropdown)
     */
    updateCartUI() {
        if (!window.Cart) return;

        // Try to get storeId from URL or context
        const urlParams = new URLSearchParams(window.location.search);
        const currentStoreId = urlParams.get('id') || urlParams.get('storeId');
        
        const activeCarts = window.Cart.getActiveCartsList();
        const badge = document.getElementById('cart-badge');
        const countText = document.getElementById('cart-count-text');
        const itemsContainer = document.getElementById('cart-items-container');
        const totalPriceEl = document.getElementById('cart-total-price');

        // Badge shows total global count across all stores
        const globalCount = window.Cart.getTotalGlobalCount();

        if (badge) {
            badge.innerText = globalCount;
            if (globalCount > 0) {
                badge.classList.remove('hidden');
            } else {
                badge.classList.add('hidden');
            }
        }

        // Update items container with grouped items
        if (itemsContainer) {
            if (activeCarts.length === 0) {
                itemsContainer.innerHTML = '<p class="text-center text-gray-400 text-sm py-4">購物車是空的</p>';
                if (countText) countText.innerText = `共 0 項品項`;
                if (totalPriceEl) totalPriceEl.innerText = `$0`;
            } else {
                // Sort to put current store first
                activeCarts.sort((a, b) => {
                    if (a.storeId == currentStoreId) return -1;
                    if (b.storeId == currentStoreId) return 1;
                    return 0;
                });

                let html = '';
                let totalItems = 0;
                let displayTotal = 0;

                activeCarts.forEach(cart => {
                    const isCurrent = cart.storeId == currentStoreId;
                    totalItems += cart.items.reduce((sum, i) => sum + i.quantity, 0);
                    
                    // If we are on a specific store page, the dropdown total usually reflects that store
                    // But if we show all, maybe we show the global total? 
                    // Let's summarize the total of what we are showing in the dropdown.
                    const cartSubtotal = cart.items.reduce((sum, i) => sum + (i.price * i.quantity), 0);
                    displayTotal += cartSubtotal;

                    html += `
                        <div class="mb-4 last:mb-0">
                            <div class="flex items-center justify-between mb-2 pb-1 border-b border-gray-50">
                                <span class="text-[10px] font-bold text-gray-400 uppercase tracking-widest flex items-center gap-1">
                                    <i data-lucide="store" class="w-3 h-3"></i>
                                    ${cart.storeName || '店家'} 
                                    ${isCurrent ? '<span class="text-brand-orange ml-1">(當前)</span>' : ''}
                                </span>
                                <span class="text-[10px] font-bold text-slate-400">$${cartSubtotal}</span>
                            </div>
                            <div class="space-y-3">
                                ${cart.items.map((item, index) => `
                                    <div class="flex items-center gap-3">
                                        <div class="w-10 h-10 bg-gray-100 rounded-lg overflow-hidden flex-shrink-0 border border-gray-50">
                                            <img src="${item.imageUrl || 'images/logo.png'}" class="w-full h-full object-cover" onerror="this.src='images/logo.png'">
                                        </div>
                                        <div class="flex-grow min-w-0">
                                            <h4 class="text-xs font-bold text-gray-800 truncate">${item.productName}</h4>
                                            <p class="text-[10px] text-gray-400">數量: ${item.quantity}</p>
                                        </div>
                                        <div class="text-right">
                                            <p class="text-xs font-bold text-brand-orange">$${item.price * item.quantity}</p>
                                            <button onclick="event.preventDefault(); Cart.removeItem(${cart.storeId}, ${index})" class="text-[10px] text-gray-300 hover:text-red-500 transition-colors">移除</button>
                                        </div>
                                    </div>
                                `).join('')}
                            </div>
                        </div>
                    `;
                });

                itemsContainer.innerHTML = html;
                if (countText) countText.innerText = `共 ${totalItems} 項品項`;
                if (totalPriceEl) totalPriceEl.innerText = `$${displayTotal}`;
                
                // Refresh icons
                if (window.lucide) lucide.createIcons();
            }
        }
    },

    /**
     * 全域頁首搜尋框 — 非 search_result 頁面跳轉至搜尋結果頁
     * search_result.html 已有自己的 inline handler，此處直接跳過
     */
    initHeaderSearch() {
        const input = document.getElementById('header-search-input');
        if (!input) return;
        if (window.location.pathname.includes('search_result.html')) return;

        const goToSearch = (kw) => {
            const base = window.location.href.split('?')[0];
            const dir = base.substring(0, base.lastIndexOf('/') + 1);
            window.location.href = `${dir}search_result.html?q=${encodeURIComponent(kw)}`;
        };

        let timer = null;
        input.addEventListener('input', () => {
            clearTimeout(timer);
            const kw = input.value.trim();
            if (!kw) return;
            timer = setTimeout(() => goToSearch(kw), 500);
        });
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                clearTimeout(timer);
                const kw = input.value.trim();
                if (kw) goToSearch(kw);
            }
        });
    },

    // ─── Toast 工具函式 ────────────────────────────────────────────
    showToast(message, type = 'info') {
        let container = document.getElementById('nav-toast-container');
        if (!container) {
            container = document.createElement('div');
            container.id = 'nav-toast-container';
            container.style.cssText = 'position:fixed;top:80px;left:50%;transform:translateX(-50%);z-index:99999;display:flex;flex-direction:column;align-items:center;gap:8px;pointer-events:none;';
            document.body.appendChild(container);
        }
        const colors = { success: '#10b981', error: '#ef4444', warning: '#f97316', info: '#475569' };
        const icons  = { success: '✓', error: '✕', warning: '⚠', info: 'ℹ' };
        const toast = document.createElement('div');
        toast.style.cssText = `pointer-events:auto;display:flex;align-items:center;gap:10px;padding:12px 20px;border-radius:16px;background:${colors[type]||colors.info};color:#fff;font-size:14px;font-weight:600;box-shadow:0 8px 24px rgba(0,0,0,.18);transition:opacity .3s,transform .3s;max-width:320px;`;
        toast.innerHTML = `<span>${icons[type]||icons.info}</span><span>${message}</span>`;
        container.appendChild(toast);
        setTimeout(() => { toast.style.opacity='0'; toast.style.transform='translateY(-10px)'; setTimeout(()=>toast.remove(),350); }, 3000);
    }
};

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    NavAuth.checkAuth();
    NavAuth.initLocationModal();
    NavAuth.initHeaderSearch();

    // Initial cart UI update
    NavAuth.updateCartUI();

    // Listen for cart changes
    window.addEventListener('cartUpdated', () => {
        NavAuth.updateCartUI();
    });
});
