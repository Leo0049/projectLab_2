(function () {
	document.addEventListener('DOMContentLoaded', function () {

		// ─── API BASE 設定 ────────────────────────────────────────────────────────
		const API_BASE = (function resolveApiBase() {
			if (window.JOIN_API_BASE && typeof window.JOIN_API_BASE === 'string') {
				return window.JOIN_API_BASE.replace(/\/$/, '');
			}
			const isLocalhost = location.hostname === 'localhost' || location.hostname === '127.0.0.1';
			if (location.protocol === 'file:' || (isLocalhost && location.port !== '8082')) {
				return 'http://localhost:8082';
			}
			return location.origin;
		})();

		function postJson(path, payload) {
			return fetch(`${API_BASE}${path}`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify(payload)
			}).then(async (res) => {
				const raw = await res.text();
				let data;
				try { data = raw ? JSON.parse(raw) : null; } catch { data = null; }
				if (!res.ok) {
					const msg = data?.msg || data?.message || raw || `HTTP ${res.status}`;
					throw new Error(msg);
				}
				return data;
			});
		}

		// ─── 解析 JWT（不驗簽，只讀 payload）────────────────────────────────────
		function parseJwtRole(token) {
			try {
				const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
				const json = decodeURIComponent(
					atob(base64).split('').map(c =>
						'%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2)
					).join('')
				);
				return JSON.parse(json).role || null;
			} catch {
				return null;
			}
		}

		// ─── Tab 切換 ─────────────────────────────────────────────────────────────
		const tabs = document.querySelectorAll('.login-tabs .tab');
		const personal = document.querySelector('.personal-section');
		const corporate = document.querySelector('.corporate-section');
		const card = document.querySelector('.login-card');
		const slider = document.querySelector('.login-tabs .tab-slider');

		function updateSlider() {
			if (!slider) return;
			const active = document.querySelector('.login-tabs .tab.active');
			if (!active) return;
			slider.style.width = `${active.offsetWidth}px`;
			slider.style.transform = `translateX(${active.offsetLeft}px)`;
		}

		function activate(target) {
			tabs.forEach(t => t.classList.toggle('active', t.dataset.target === target));
			if (target === 'personal') {
				personal.removeAttribute('hidden');
				corporate.setAttribute('hidden', '');
				if (card) card.classList.remove('corporate-mode');
			} else {
				corporate.removeAttribute('hidden');
				personal.setAttribute('hidden', '');
				if (card) card.classList.add('corporate-mode');
			}
			updateSlider();
		}

		tabs.forEach(t => t.addEventListener('click', () => activate(t.dataset.target)));

		const initial = document.querySelector('.login-tabs .tab.active');
		if (initial?.dataset.target) activate(initial.dataset.target);

		window.addEventListener('resize', updateSlider);

		// ─── 錯誤訊息顯示 ────────────────────────────────────────────────────────
		function showError(elId, msg) {
			const el = document.getElementById(elId);
			if (!el) return;
			el.textContent = msg;
			el.style.display = 'block';
		}
		function clearError(elId) {
			const el = document.getElementById(elId);
			if (el) el.style.display = 'none';
		}

		// ─── 個人登入 ─────────────────────────────────────────────────────────────
		const personalBtn = document.getElementById('personal-login-btn');
		if (personalBtn) {
			personalBtn.addEventListener('click', async function () {
				clearError('personal-error');

				const phone = document.getElementById('personal-phone')?.value.trim();
				const password = document.getElementById('personal-password')?.value;

				if (!phone || !password) {
					showError('personal-error', '請輸入手機號碼及密碼');
					return;
				}

				personalBtn.disabled = true;
						personalBtn.textContent = '登入中…';

				try {
					const result = await postJson('/api/auth/login', { phone, password });

					if (result?.code === '200') {
						const token = result.data?.token;
						const name  = result.data?.name || '';
						const userId = result.data?.userId || '';
						const userPhone = result.data?.phone || phone; // 使用回傳的或輸入的
						localStorage.setItem('JOIN_TOKEN', token);
						localStorage.setItem('JOIN_USER_NAME', name);
						localStorage.setItem('JOIN_USER_ID', String(userId));
						localStorage.setItem('JOIN_USER_PHONE', userPhone);
						localStorage.removeItem('userLat');
						localStorage.removeItem('userLng');
						localStorage.removeItem('userLocationName');
						localStorage.removeItem('userAddress');
						localStorage.removeItem('JOIN_LOCATION_MODAL_SHOWN');
						// 個人用戶導向客戶首頁
						location.href = '../index.html';
						return;
					}

					// code 202：此手機號碼已有帳號需要整合
					if (result?.code === '202') {
						localStorage.setItem('JOIN_MERGE', JSON.stringify({
							phone,
							action: result.data?.action || 'MERGE_SET_PASSWORD'
						}));
						location.href = `verify-phone.html?type=merge&phone=${encodeURIComponent(phone)}`;
						return;
					}

					showError('personal-error', result?.msg || '登入失敗，請稍後再試');
				} catch (err) {
					showError('personal-error', err.message || '登入失敗，請確認網路連線');
				} finally {
					personalBtn.disabled = false;
					personalBtn.textContent = '登入';
				}
			});
		}

		// ─── 企業登入 ─────────────────────────────────────────────────────────────
		// 企業帳號（STORE / BRAND）統一走 /api/auth/corporate-login
		// 後端回傳 JWT，前端解析 role 決定導向哪個後台
		const corporateBtn = document.getElementById('corporate-login-btn');
		if (corporateBtn) {
			corporateBtn.addEventListener('click', async function () {
				clearError('corporate-error');

				const account  = document.getElementById('corporate-account')?.value.trim();
				const password = document.getElementById('corporate-password')?.value;

				if (!account || !password) {
					showError('corporate-error', '請輸入帳號及密碼');
					return;
				}

				corporateBtn.disabled = true;
						corporateBtn.textContent = '登入中…';

				try {
					const result = await postJson('/api/auth/corporate-login', { account, password });

					if (result?.code === '200') {
						const token = result.data?.token;

						// 解析 JWT role 讓系統自動辨識是 BRAND 還是 STORE
						const role = parseJwtRole(token);

						if (role === 'BRAND') {
							// Brand 前端讀取 brandToken / brandId / brandName / brandLogoUrl
							localStorage.setItem('brandToken',   token);
							localStorage.setItem('brandId',      String(result.data?.brandId || ''));
							localStorage.setItem('brandName',    result.data?.name || '');
							localStorage.setItem('brandLogoUrl', result.data?.logoUrl || '');
							location.href = '../../Brand/hq-dashboard.html';

						} else if (role === 'STORE') {
							// Store 前端讀取 store_token / store_id
							localStorage.setItem('store_token', token);
							localStorage.setItem('store_id',    String(result.data?.storeId || ''));
							location.href = '../../store/home.html';

						} else {
							showError('corporate-error', '帳號身分無法識別，請聯繫管理員');
						}
						return;
					}

					showError('corporate-error', result?.msg || '登入失敗，請確認帳號及密碼');
				} catch (err) {
					showError('corporate-error', err.message || '登入失敗，請確認網路連線');
				} finally {
					corporateBtn.disabled = false;
					corporateBtn.textContent = '企業登入';
				}
			});
		}

		// ─── 按住顯示密碼 ────────────────────────────────────────────────────────
		function setupPasswordPeek(icon, input) {
			if (!icon || !input) return;
			const show = () => { input.type = 'text'; icon.classList.replace('fa-eye', 'fa-eye-slash'); };
			const hide = () => { input.type = 'password'; icon.classList.replace('fa-eye-slash', 'fa-eye'); };
			icon.addEventListener('mousedown', show);
			icon.addEventListener('mouseup', hide);
			icon.addEventListener('mouseleave', hide);
			icon.addEventListener('touchstart', (e) => { e.preventDefault(); show(); });
			icon.addEventListener('touchend', hide);
		}

		document.querySelectorAll('.toggle-password, .fa-eye').forEach(icon => {
			const input = icon.closest('.input-box')?.querySelector('input[type="password"]');
			if (input) setupPasswordPeek(icon, input);
		});

		// ─── 第三方登入（Google / Facebook）─────────────────────────────────────
		async function socialLogin(provider) {
			if (!window.firebase || !firebase.auth) {
				alert('Firebase 尚未載入');
				return;
			}

			const auth = firebase.auth();
			const firebaseProvider = provider === 'FACEBOOK'
				? new firebase.auth.FacebookAuthProvider()
				: new firebase.auth.GoogleAuthProvider();

			// Google 需要額外請求 email scope
			// Facebook 的 email 為預設授權，addScope('email') 會報 Invalid Scopes 錯誤
			if (provider !== 'FACEBOOK') {
				try { firebaseProvider.addScope('email'); } catch { }
			}

			try {
				const result  = await auth.signInWithPopup(firebaseProvider);
				const idToken = await result.user.getIdToken();

				// Step 1：確認 Firebase token 有效
				const verifyResult = await postJson('/api/auth/firebase-verify', { idToken });
				if (verifyResult.code !== '200') {
					throw new Error(verifyResult.msg || '後端驗證失敗');
				}

				// 暫存社群資料，供後續頁面使用
				const payload = {
					idToken,
					phone:       result.user.phoneNumber || '',
					providerUid: result.user.uid || '',
					name:        result.user.displayName || '',
					provider
				};
				localStorage.setItem('JOIN_SOCIAL_PENDING', JSON.stringify(payload));

				// Step 2：嘗試直接登入（不帶 phone 與 name）
				// - 後端找到 providerUid 則 code 200 → 已整合，直接登入
				// - 傳入空 name 避免覆蓋 DB 現有暱稱
				const socialResult = await postJson('/api/auth/social-login', {
					idToken,
					phone:    '',
					name:     '', 
					provider
				});

				if (socialResult?.code === '200') {
					const phone = socialResult?.data?.phone || '';
					const token = socialResult?.data?.token;

					if (!phone) {
						// 已整合第三方但手機號碼為空，導入帳號綁定流程
						if (token) localStorage.setItem('JOIN_TOKEN', token);
						localStorage.setItem('JOIN_USER_NAME', socialResult?.data?.name || payload.name || '');
						localStorage.setItem('JOIN_USER_ID',   String(socialResult?.data?.userId || ''));
						const providerParam = encodeURIComponent(provider);
						location.href = `account-integrate.html?source=login&provider=${providerParam}&phone=`;
						return;
					}

					// 已整合且有手機號，直接導向使用者首頁
					if (token) localStorage.setItem('JOIN_TOKEN', token);
					localStorage.setItem('JOIN_USER_NAME', socialResult?.data?.name || '');
					localStorage.setItem('JOIN_USER_ID',   String(socialResult?.data?.userId || ''));
					localStorage.setItem('JOIN_USER_PHONE', socialResult?.data?.phone || '');
					localStorage.removeItem('JOIN_SOCIAL_PENDING');
					localStorage.removeItem('userLat');
					localStorage.removeItem('userLng');
					localStorage.removeItem('userLocationName');
					localStorage.removeItem('userAddress');
					localStorage.removeItem('JOIN_LOCATION_MODAL_SHOWN');
					location.href = '../index.html';
					return;
				}

				// code 201 或其他 → 需要整合帳號
				const phoneParam    = encodeURIComponent(payload.phone || '');
				const providerParam = encodeURIComponent(provider);
				location.href = `account-integrate.html?source=login&provider=${providerParam}&phone=${phoneParam}`;

			} catch (err) {
				console.error('社群登入錯誤', err);

				if (err.code === 'auth/popup-closed-by-user' ||
					err.code === 'auth/cancelled-popup-request') {
					return; // 使用者自行關閉，不顯示錯誤
				}

				if (err.code === 'auth/operation-not-allowed') {
				alert('請在 Firebase 控制台開啟此登入方式（Google/Facebook）');
					return;
				}

				if (err.code === 'auth/account-exists-with-different-credential') {
					// Firebase 偵測到同一個 email 已用其他 provider 登入過
					// 因為資料庫沒有 email，用 provider 推斷提示使用者
					const other = provider === 'GOOGLE' ? 'Facebook' : 'Google';
					alert(`此帳號已用 ${other} 登入過，請改用 ${other} 登入。\n\n若要讓同一個 email 使用多種登入方式，請在 Firebase Console 的 Authentication > Settings，開啟「電子郵件地址一個帳號」設定。`);
					return;
				}

				alert(err.message || '社群登入發生錯誤');
			}
		}

		document.querySelectorAll('.social-btn[data-provider]').forEach(btn => {
			btn.addEventListener('click', () => socialLogin(btn.dataset.provider || 'GOOGLE'));
		});
	});
})();
