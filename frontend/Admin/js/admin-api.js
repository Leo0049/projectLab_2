/**
 * 管理員後台的 API 呼叫（沿用 Brand/js/brand-api.js 的慣例）。
 *
 * 權杖只存在 localStorage 的 adminToken，與顧客（JOIN_TOKEN）、品牌（brandToken）、
 * 門市（store_token）分開。帶權杖的請求若回 401／403，一律清掉權杖並回到登入頁。
 * 登入請求本身不帶權杖，所以密碼錯誤只會顯示訊息，不會被導向別的頁面。
 */
const AdminAPI = (() => {
  const BASE = 'http://localhost:8082';
  const TOKEN_KEY = 'adminToken';
  const LOGIN_PAGE = 'login.html';
  const BRANDS_PAGE = 'brands.html';

  const getToken = () => localStorage.getItem(TOKEN_KEY) || '';
  const saveToken = (token) => localStorage.setItem(TOKEN_KEY, token);
  const clearToken = () => localStorage.removeItem(TOKEN_KEY);

  function redirectToLogin() {
    window.location.href = LOGIN_PAGE;
  }

  /** 失敗時拋出的錯誤：status 是 HTTP 狀態碼（連線失敗為 0）；auth 為 true 表示已被導向登入頁 */
  function failure(message, status, auth = false) {
    const err = new Error(message);
    err.status = status;
    err.auth = auth;
    return err;
  }

  async function request(path, { method = 'GET', body, auth = true } = {}) {
    const headers = { 'Content-Type': 'application/json' };
    if (auth) {
      const token = getToken();
      if (!token) {
        redirectToLogin();
        throw failure('尚未登入', 401, true);
      }
      headers.Authorization = `Bearer ${token}`;
    }

    let res;
    try {
      res = await fetch(BASE + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (e) {
      throw failure('無法連線到伺服器，請稍後再試', 0);
    }

    // 非 JSON 的回應（例如框架預設的 401 頁）視為沒有 body
    let json = null;
    try {
      json = await res.json();
    } catch (e) {
      json = null;
    }
    if (res.ok && json && String(json.code) === '200') return json.data;

    if (auth && (res.status === 401 || res.status === 403)) {
      clearToken();
      redirectToLogin();
      throw failure('登入已失效，請重新登入', res.status, true);
    }
    throw failure((json && json.msg) || `請求失敗（HTTP ${res.status}）`, res.status);
  }

  return {
    LOGIN_PAGE,
    BRANDS_PAGE,
    getToken,
    saveToken,
    clearToken,

    /** 沒有權杖就導向登入頁並回傳 false */
    requireLogin() {
      if (getToken()) return true;
      redirectToLogin();
      return false;
    },

    login(username, password) {
      return request('/api/admin/auth/login', { method: 'POST', body: { username, password }, auth: false });
    },

    pendingBrands(page, size) {
      const query = new URLSearchParams({ page: String(page), size: String(size) });
      return request(`/api/admin/brands/pending?${query}`);
    },

    approveBrand(brandId) {
      return request(`/api/admin/brands/${encodeURIComponent(brandId)}/approve`, { method: 'POST' });
    },

    rejectBrand(brandId) {
      return request(`/api/admin/brands/${encodeURIComponent(brandId)}/reject`, { method: 'POST' });
    },

    logout() {
      clearToken();
      redirectToLogin();
    },
  };
})();
