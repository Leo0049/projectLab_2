// ─── verify-phone.js ─────────────────────────────────────────────────────────
// ✅ 完全自給自足：自己負責載入並初始化 Firebase，不依賴 HTML 頁面的初始化順序
// type=signup  → Firebase OTP → idToken → /api/auth/register
// type=reset   → Firebase OTP → idToken → localStorage → reset-password.html
// type=merge   → Firebase OTP → idToken → /api/auth/merge/bind-social

// ─── 模擬模式開關 ─────────────────────────────────────────────────────────────
// ✅ 設為 true 時：不發真實簡訊，輸入任意 6 位數字即可通過驗證
//    後端同步接受 MOCK_TOKEN，不打 Firebase Admin SDK
//    上線前改為 false 即可
const SMS_MOCK = true;

// ─── API BASE ────────────────────────────────────────────────────────────────
const API_BASE = (function resolveApiBase() {
	if (window.JOIN_API_BASE && typeof window.JOIN_API_BASE === 'string')
		return window.JOIN_API_BASE.replace(/\/$/, '');
	const isLocalhost = location.hostname === 'localhost' || location.hostname === '127.0.0.1';
	if (location.protocol === 'file:' || (isLocalhost && location.port !== '8082'))
		return 'http://localhost:8082';
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

// ─── URL 參數 ────────────────────────────────────────────────────────────────
const params         = new URLSearchParams(location.search);
const type           = params.get('type');
const phoneFromQuery = params.get('phone') || '';

// ─── 顯示遮罩手機號 ──────────────────────────────────────────────────────────
const displayPhone = document.getElementById('display-phone');
if (displayPhone && phoneFromQuery) {
	const masked = phoneFromQuery.length >= 10
		? `${phoneFromQuery.slice(0, 4)} *** ${phoneFromQuery.slice(-3)}`
		: phoneFromQuery;
	displayPhone.textContent = masked;
}

// ─── 狀態訊息 ────────────────────────────────────────────────────────────────
const statusEl = document.getElementById('verify-status');
function setStatus(msg, isError = false) {
	if (!statusEl) return;
	statusEl.textContent = msg;
	statusEl.style.color = isError ? '#e53e3e' : '#38a169';
}

// ─── OTP 自動跳格 ────────────────────────────────────────────────────────────
const otpInputs = document.querySelectorAll('.otp');
otpInputs.forEach((input, index) => {
	input.addEventListener('input', (e) => {
		e.target.value = e.target.value.replace(/\D/g, '');
		if (e.target.value.length === 1 && index < otpInputs.length - 1)
			otpInputs[index + 1].focus();
	});
	input.addEventListener('keydown', (e) => {
		if (e.key === 'Backspace' && input.value === '' && index > 0)
			otpInputs[index - 1].focus();
	});
});

function getOtpCode() {
	return Array.from(otpInputs).map(i => i.value).join('');
}
function clearOtp() {
	otpInputs.forEach(i => { i.value = ''; });
	otpInputs[0]?.focus();
}

// ─── 倒數計時 ────────────────────────────────────────────────────────────────
let time = 120;
const minutesEl = document.getElementById('minutes');
const secondsEl = document.getElementById('seconds');
let timerInterval = null;

function startTimer() {
	clearInterval(timerInterval);
	time = 120;
	timerInterval = setInterval(() => {
		const m = Math.floor(time / 60);
		const s = time % 60;
		if (minutesEl) minutesEl.textContent = String(m).padStart(2, '0');
		if (secondsEl) secondsEl.textContent = String(s).padStart(2, '0');
		time--;
		if (time < 0) {
			clearInterval(timerInterval);
			setStatus('驗證碼已過期，請重新發送', true);
		}
	}, 1000);
}
startTimer();

// ─── Firebase 自載入與初始化 ──────────────────────────────────────────────────
// ✅ 核心修正：JS 自己載入 Firebase，完全不依賴 HTML 頁面的 <script> 順序
//    這樣不管 HTML 版本新舊，verify-phone.js 都能正常運作

let confirmationResult = null;

function loadScriptPromise(src) {
	return new Promise((resolve, reject) => {
		// 若已載入過相同 src，直接 resolve
		if (document.querySelector(`script[src="${src}"]`)) { resolve(); return; }
		const s = document.createElement('script');
		s.src = src; s.async = true;
		s.onload = () => resolve();
		s.onerror = () => reject(new Error(`無法載入 ${src}`));
		document.head.appendChild(s);
	});
}

async function ensureFirebase() {
	// 已初始化過，直接返回
	if (window.firebase && firebase.apps && firebase.apps.length > 0) return;

	// 動態載入 Firebase SDK（若 HTML 已載入則 loadScriptPromise 會直接 resolve）
	const pairs = [
		[
			'https://www.gstatic.com/firebasejs/10.14.0/firebase-app-compat.js',
			'https://www.gstatic.com/firebasejs/10.14.0/firebase-auth-compat.js'
		],
		[
			'https://cdn.jsdelivr.net/npm/firebase@10.14.0/compat/app.js',
			'https://cdn.jsdelivr.net/npm/firebase@10.14.0/compat/auth.js'
		],
		[
			'https://cdnjs.cloudflare.com/ajax/libs/firebase/10.14.0/firebase-app-compat.min.js',
			'https://cdnjs.cloudflare.com/ajax/libs/firebase/10.14.0/firebase-auth-compat.min.js'
		]
	];

	let loaded = false;
	for (const [appUrl, authUrl] of pairs) {
		try {
			await loadScriptPromise(appUrl);
			await loadScriptPromise(authUrl);
			loaded = true;
			break;
		} catch { /* 試下一組 */ }
	}

	if (!loaded || !window.firebase) throw new Error('Firebase SDK 載入失敗');

	// 初始化（若 HTML 已初始化過則跳過）
	if (firebase.apps.length === 0) {
		const cfg = window.JOIN_FIREBASE_CONFIG || {
			apiKey:            'AIzaSyCFQlAen8XIY0VTxzl0usNvz-FxO-gsRZM',
			authDomain:        'project-b5e05.firebaseapp.com',
			projectId:         'project-b5e05',
			storageBucket:     'project-b5e05.firebasestorage.app',
			messagingSenderId: '302990497638',
			appId:             '1:302990497638:web:4ad06a28fce00b5e5d5aa7'
		};
		firebase.initializeApp(cfg);
	}
}

// reCAPTCHA（invisible）
function initRecaptcha() {
	if (window._recaptchaVerifier) return window._recaptchaVerifier;
	// 確保容器存在（舊版 HTML 可能沒有 recaptcha-container）
	let container = document.getElementById('recaptcha-container');
	if (!container) {
		container = document.createElement('div');
		container.id = 'recaptcha-container';
		document.body.appendChild(container);
	}
	window._recaptchaVerifier = new firebase.auth.RecaptchaVerifier('recaptcha-container', {
		size: 'invisible',
		callback: () => {}
	});
	return window._recaptchaVerifier;
}

async function sendOtp(phone) {
	await ensureFirebase();
	const auth = firebase.auth();
	const e164 = phone.startsWith('+') ? phone : '+886' + phone.replace(/^0/, '');
	const verifier = initRecaptcha();
	confirmationResult = await auth.signInWithPhoneNumber(e164, verifier);
}

// ─── 頁面就緒後自動送 OTP ────────────────────────────────────────────────────
async function autoSendOtp() {
	if (!['signup', 'reset', 'social-signup'].includes(type) || !phoneFromQuery) return;

	// mock 模式：不發簡訊，直接提示使用者輸入任意數字
	if (SMS_MOCK) {
		//setStatus('【測試模式】請輸入任意 6 位數字完成驗證');
		return;
	}

	try {
		setStatus('正在發送驗證碼…');
		await sendOtp(phoneFromQuery);
		setStatus('驗證碼已發送，請查看簡訊');
	} catch (err) {
		console.error('sendOtp 失敗', err);
		setStatus('驗證碼發送失敗：' + (err.message || '請稍後重試'), true);
	}
}

// 等 DOM 完全就緒後才執行（處理 JS 在 <head> 或 inline 的情況）
if (document.readyState === 'loading') {
	document.addEventListener('DOMContentLoaded', autoSendOtp);
} else {
	autoSendOtp();
}

// ─── 重新發送 ────────────────────────────────────────────────────────────────
document.getElementById('resend-btn')?.addEventListener('click', async (e) => {
	e.preventDefault();

	if (SMS_MOCK) {
		clearOtp();
		startTimer();
		//setStatus('【測試模式】請輸入任意 6 位數字完成驗證');
		return;
	}

	// 重置 reCAPTCHA
	if (window._recaptchaVerifier) {
		try { window._recaptchaVerifier.clear(); } catch {}
		window._recaptchaVerifier = null;
	}

	if (!phoneFromQuery) { alert('找不到手機號碼，請返回重試'); return; }

	try {
		setStatus('正在重新發送驗證碼…');
		await sendOtp(phoneFromQuery);
		clearOtp();
		startTimer();
		setStatus('驗證碼已重新發送');
	} catch (err) {
		console.error('resend 失敗', err);
		setStatus('重新發送失敗：' + (err.message || '請稍後重試'), true);
	}
});

// ─── 完成驗證按鈕 ────────────────────────────────────────────────────────────
const verifyBtn = document.querySelector('.verify-btn');
if (verifyBtn) {
	verifyBtn.addEventListener('click', async function () {
		const code = getOtpCode();
		if (code.length !== 6) {
			setStatus('請輸入完整的 6 位驗證碼', true);
			return;
		}

		verifyBtn.disabled  = true;
		verifyBtn.innerHTML = '驗證中… <i class="fa-solid fa-spinner fa-spin"></i>';
		setStatus('');

		try {
			// ── mock 模式：任意 6 位數字通過，用 MOCK_TOKEN ──────────────────
			if (SMS_MOCK) {
				await handleVerified('MOCK_TOKEN');
				return;
			}

			// ── 真實 Firebase OTP ────────────────────────────────────────────
			if (!confirmationResult) {
				setStatus('尚未收到驗證碼，請點「重新發送」後再試', true);
				return;
			}

			const credential = await confirmationResult.confirm(code);
			const idToken    = await credential.user.getIdToken();

			if (!idToken || idToken.split('.').length !== 3) {
				throw new Error('取得的驗證 Token 格式異常，請重新操作');
			}

			await handleVerified(idToken);

		} catch (err) {
			console.error('verify error', err);
			if (err.code === 'auth/invalid-verification-code') {
				setStatus('驗證碼錯誤，請重新輸入', true);
				clearOtp();
			} else if (err.code === 'auth/code-expired') {
				setStatus('驗證碼已過期，請重新發送', true);
			} else {
				setStatus(err.message || '驗證失敗，請稍後再試', true);
			}
		} finally {
			verifyBtn.disabled  = false;
			verifyBtn.innerHTML = '完成驗證 <i class="fa-solid fa-circle-check"></i>';
		}
	});
}

// ─── 驗證通過後的統一處理 ────────────────────────────────────────────────────
async function handleVerified(idToken) {

	// ── signup：一般註冊 ──────────────────────────────────────────────────────
	if (type === 'signup') {
		const pending = JSON.parse(localStorage.getItem('JOIN_REGISTER_PENDING') || '{}');
		if (!pending.phone || !pending.password || !pending.name) {
			setStatus('註冊資料遺失，請返回重新填寫', true);
			setTimeout(() => { location.href = 'register.html'; }, 1500);
			return;
		}
		try {
			const result = await postJson('/api/auth/register', {
				phone: pending.phone, password: pending.password,
				name: pending.name, idToken
			});
			if (result?.code === '200') {
				localStorage.removeItem('JOIN_REGISTER_PENDING');
				location.href = 'register-success.html';
				return;
			}
			setStatus(result?.msg || '註冊失敗，請稍後再試', true);
		} catch (err) {
			setStatus(err.message || '註冊失敗', true);
		}
		return;
	}

	// ── social-signup：第三方帳號手機驗證 ────────────────────────────────────
	// 帳號已由 account-integrate.html 呼叫 social-login 建立完成
	// 此步驟只是確認手機號碼屬於本人，通過後直接到成功頁
	if (type === 'social-signup') {
		setStatus('手機驗證完成！');
		setTimeout(() => { location.href = 'register-success.html'; }, 500);
		return;
	}

	// ── reset：重設密碼 ───────────────────────────────────────────────────────
	if (type === 'reset') {
		if (!phoneFromQuery) {
			setStatus('手機號碼遺失，請返回重試', true);
			return;
		}
		// ✅ 把 idToken 寫進 localStorage，reset-password.html 讀取後呼叫 API
		localStorage.setItem('JOIN_RESET_TOKEN', JSON.stringify({
			idToken,
			phone: phoneFromQuery
		}));
		setStatus('驗證成功，導向重設頁面…');
		setTimeout(() => {
			location.href = `reset-password.html?phone=${encodeURIComponent(phoneFromQuery)}`;
		}, 500);
		return;
	}

	// ── merge：帳號整合 ───────────────────────────────────────────────────────
	if (type === 'merge') {
		const mergeData = JSON.parse(localStorage.getItem('JOIN_MERGE') || '{}');
		if (!mergeData?.providerUid || !mergeData?.provider) {
			setStatus('整合資料遺失，請重新嘗試社群登入', true);
			return;
		}
		try {
			const data = await postJson('/api/auth/merge/bind-social', {
				idToken, phone: phoneFromQuery,
				providerUid: mergeData.providerUid,
				provider:    mergeData.provider
			});
			if (data?.code === '200') {
				if (data?.data?.token) localStorage.setItem('JOIN_TOKEN', data.data.token);
				localStorage.removeItem('JOIN_MERGE');
				alert('整合成功，請重新登入');
				location.href = 'login.html';
				return;
			}
			setStatus(data?.msg || '整合失敗', true);
		} catch (err) {
			setStatus(err.message || '整合失敗', true);
		}
		return;
	}

	// 預設：導向成功頁
	location.href = 'register-success.html';
}