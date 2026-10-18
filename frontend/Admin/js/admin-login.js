/**
 * 管理員登入頁：送出帳密、存下權杖、導向待審清單。
 * 錯誤訊息一律以 textContent 顯示（伺服器回傳的文字不當成 HTML）。
 */
(() => {
  const form = document.getElementById('admin-login-form');
  const usernameInput = document.getElementById('admin-username');
  const passwordInput = document.getElementById('admin-password');
  const submitBtn = document.getElementById('admin-login-submit');
  const errorBox = document.getElementById('admin-login-error');

  function showError(message) {
    errorBox.textContent = message;
    errorBox.classList.remove('hidden');
  }

  function clearError() {
    errorBox.textContent = '';
    errorBox.classList.add('hidden');
  }

  function setBusy(busy) {
    submitBtn.disabled = busy;
    submitBtn.textContent = busy ? '登入中…' : '登入';
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearError();

    const username = usernameInput.value.trim();
    const password = passwordInput.value;
    if (!username || !password) {
      showError('請輸入帳號與密碼');
      return;
    }

    setBusy(true);
    try {
      const data = await AdminAPI.login(username, password);
      if (!data || !data.token) throw new Error('登入失敗，請稍後再試');
      AdminAPI.saveToken(data.token);
      window.location.href = AdminAPI.BRANDS_PAGE;
    } catch (err) {
      showError(err.message || '登入失敗，請稍後再試');
      passwordInput.value = '';
      passwordInput.focus();
      setBusy(false);
    }
  });
})();
