// Fill these values with your Firebase Web App config.
// You can find them in Firebase Console -> Project settings -> General -> Your apps.
window.JOIN_FIREBASE_CONFIG = {
    apiKey: "REPLACE_ME_API_KEY",
    authDomain: "REPLACE_ME_AUTH_DOMAIN",
    projectId: "REPLACE_ME_PROJECT_ID",
    storageBucket: "REPLACE_ME_STORAGE_BUCKET",
    messagingSenderId: "REPLACE_ME_MESSAGING_SENDER_ID",
    appId: "REPLACE_ME_APP_ID",
    authLanguageCode: "zh-TW"
};

// ⚠️ 金鑰安全政策（2026-08 稽核後）：
// 舊版曾把真實 apiKey 硬編碼在 auth/ 下五個檔案並推上公開 repo——
// 該金鑰現已撤銷。此後設定單一事實來源就是本檔；填入新 key 前，
// 所有頁面會在 console 收到以下明確提示。
if (String(window.JOIN_FIREBASE_CONFIG.apiKey).startsWith('REPLACE_ME')) {
    console.warn(
        '[JOIN] Firebase 尚未設定：請編輯 frontend/Customer/js/firebase-config.js ' +
        '填入專案設定。取得方式：Firebase Console → 專案設定 → 一般 → 你的應用程式。'
    );
}

