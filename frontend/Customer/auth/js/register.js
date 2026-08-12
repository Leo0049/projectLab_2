// 註冊表單驗證、暫存資料、導向手機驗證

document.addEventListener('DOMContentLoaded', function () {

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

    const form          = document.querySelector('.register-form');
    const phoneInput    = document.getElementById('reg-phone');
    const passwordInput = document.getElementById('reg-password');
    const confirmInput  = document.getElementById('reg-password-confirm');
    const nameInput     = document.getElementById('reg-name');
    const agreeCheckbox = form.querySelector('.agree input[type="checkbox"]');
    const registerBtn   = form.querySelector('.register-btn');

    // ─── 按鈕啟用檢查 ──────────────────────────────────────
    function validate() {
        const allFilled = [phoneInput, passwordInput, confirmInput, nameInput]
            .every(i => i.value.trim() !== '');
        const agreed = agreeCheckbox.checked;
        registerBtn.disabled = !(allFilled && agreed);
        registerBtn.classList.toggle('disabled', registerBtn.disabled);
    }

    [phoneInput, passwordInput, confirmInput, nameInput].forEach(i =>
        i.addEventListener('input', validate)
    );
    agreeCheckbox.addEventListener('change', validate);
    validate();

    // ─── Modal 工具 ─────────────────────────────────────────
    function openModal(id)  { const m = document.getElementById(id); if (m) { m.setAttribute('aria-hidden','false'); m.classList.add('open'); } }
    function closeModal(id) { const m = document.getElementById(id); if (m) { m.setAttribute('aria-hidden','true');  m.classList.remove('open'); } }

    document.getElementById('modal-error-ok')?.addEventListener('click', () => closeModal('error-modal'));

    // ─── 點擊「註冊並驗證手機」───────────────────────────────
    registerBtn.addEventListener('click', async function () {
        if (registerBtn.disabled) return;

        const phone    = phoneInput.value.trim();
        const password = passwordInput.value;
        const confirm  = confirmInput.value;
        const name     = nameInput.value.trim();

        // 密碼一致性檢查
        if (password !== confirm) {
            openModal('error-modal');
            return;
        }

        // 手機格式檢查
        if (!/^09\d{8}$/.test(phone)) {
            alert('手機號碼格式不正確，請輸入 09 開頭的 10 位數字');
            return;
        }

        // 密碼長度檢查
        if (password.length < 8 || password.length > 16) {
            alert('密碼請設定 8～16 位英數組合');
            return;
        }

        // 先呼叫後端確認手機是否已被註冊
        registerBtn.disabled = true;
        registerBtn.innerHTML = '確認中… <i class="fa-solid fa-spinner fa-spin"></i>';

        try {
            const checkRes = await fetch(`${API_BASE}/api/auth/check-phone?phone=${encodeURIComponent(phone)}`);
            const checkData = await checkRes.json();
            if (checkData?.data === true) {
                alert('此手機號碼已被註冊，請直接登入或使用忘記密碼功能');
                return;
            }
        } catch (err) {
            console.warn('check-phone 失敗，跳過檢查繼續', err);
        } finally {
            registerBtn.disabled = false;
            registerBtn.innerHTML = '註冊並驗證手機 <i class="fa-solid fa-arrow-right"></i>';
        }

        // 把表單資料暫存到 localStorage，verify-phone.js 拿到 idToken 後一起打 register API
        localStorage.setItem('JOIN_REGISTER_PENDING', JSON.stringify({ phone, password, name }));

        // 導向手機驗證頁，帶上手機號碼顯示用
        location.href = `verify-phone.html?type=signup&phone=${encodeURIComponent(phone)}`;
    });

    // ─── 眼睛切換顯示密碼 ───────────────────────────────────
    function setupPasswordPeek(icon, input) {
        if (!icon || !input) return;
        const show = () => { input.type = 'text';     icon.classList.replace('fa-eye', 'fa-eye-slash'); };
        const hide = () => { input.type = 'password'; icon.classList.replace('fa-eye-slash', 'fa-eye'); };
        icon.addEventListener('mousedown', show);
        icon.addEventListener('mouseup', hide);
        icon.addEventListener('mouseleave', hide);
        icon.addEventListener('touchstart', (e) => { e.preventDefault(); show(); });
        icon.addEventListener('touchend', hide);
    }

    document.querySelectorAll('.toggle-password').forEach(icon => {
        const input = icon.closest('.input-box')?.querySelector('input[type="password"]');
        if (input) setupPasswordPeek(icon, input);
    });

    // ─── 第三方社群登入／註冊（流程與登入頁完全一致）───────────
    async function socialAuth(provider) {
        if (!window.firebase || !firebase.auth) {
            alert('Firebase 尚未載入');
            return;
        }
        const auth = firebase.auth();
        const firebaseProvider = provider === 'FACEBOOK'
            ? new firebase.auth.FacebookAuthProvider()
            : new firebase.auth.GoogleAuthProvider();

        // Google 才需要額外要求 email scope
        if (provider !== 'FACEBOOK') {
            try { firebaseProvider.addScope('email'); } catch {}
        }

        try {
            const result  = await auth.signInWithPopup(firebaseProvider);
            const idToken = await result.user.getIdToken();

            // Step 1：確認 Firebase token 合法
            const verifyResult = await postJson('/api/auth/firebase-verify', { idToken });
            if (verifyResult.code !== '200') {
                throw new Error(verifyResult.msg || '後端驗證失敗');
            }

            // 暫存社群資料
            const payload = {
                idToken,
                phone:       result.user.phoneNumber || '',
                providerUid: result.user.uid || '',
                name:        result.user.displayName || '',
                provider
            };
            localStorage.setItem('JOIN_SOCIAL_PENDING', JSON.stringify(payload));

            // Step 2：嘗試直接登入（不帶 phone）
            // - code 200 → 已整合過，直接進首頁
            // - code 201 → 第一次，需要整合流程
            const socialResult = await postJson('/api/auth/social-login', {
                idToken,
                phone:    '',
                name:     payload.name,
                provider
            });

            if (socialResult?.code === '200') {
                const phone = socialResult?.data?.phone || '';

                if (!phone) {
                    // 已整合但沒有手機號碼 → 手機綁定流程
                    const token = socialResult?.data?.token;
                    if (token) localStorage.setItem('JOIN_TOKEN', token);
                    localStorage.setItem('JOIN_USER_NAME', socialResult?.data?.name || '');
                    localStorage.setItem('JOIN_USER_ID',   String(socialResult?.data?.userId || ''));
                    const providerParam = encodeURIComponent(provider);
                    location.href = `account-integrate.html?source=register&provider=${providerParam}&phone=`;
                    return;
                }

                // 已整合且有手機號碼 → 直接進首頁
                const token = socialResult?.data?.token;
                if (token) localStorage.setItem('JOIN_TOKEN', token);
                localStorage.setItem('JOIN_USER_NAME', socialResult?.data?.name || '');
                localStorage.setItem('JOIN_USER_ID',   String(socialResult?.data?.userId || ''));
                localStorage.removeItem('JOIN_SOCIAL_PENDING');
                location.href = '../pages/index/index.html';
                return;
            }

            // code 201 或其他 → 需要整合流程
            const phoneParam    = encodeURIComponent(payload.phone || '');
            const providerParam = encodeURIComponent(provider);
            location.href = `account-integrate.html?source=register&provider=${providerParam}&phone=${phoneParam}`;

        } catch (err) {
            console.error('社群登入／註冊錯誤', err);

            if (err.code === 'auth/popup-closed-by-user' ||
                err.code === 'auth/cancelled-popup-request') {
                return;
            }
            if (err.code === 'auth/operation-not-allowed') {
                alert('請在 Firebase 控制台開啟此登入方式（Google/Facebook）');
                return;
            }
            if (err.code === 'auth/account-exists-with-different-credential') {
                const other = provider === 'GOOGLE' ? 'Facebook' : 'Google';
                alert(`此帳號已透過 ${other} 登入過，請改用 ${other} 登入。`);
                return;
            }
            alert(err.message || '社群登入發生錯誤');
        }
    }

    document.querySelectorAll('.social-btn[data-provider]').forEach(btn => {
        btn.addEventListener('click', () => socialAuth(btn.dataset.provider || 'GOOGLE'));
    });
});