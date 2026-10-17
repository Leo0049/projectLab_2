/**
 * UI 驗證的失敗登記簿。
 *
 * 各套件（page-sweep、flow-*、check-output-escaping）每發現一條失敗就呼叫 fail()，
 * run-all.js 在套件跑完後呼叫 take() 取出逐項結果，用來標註 GitHub 註解與 step summary。
 * 取代原本攔截 console.log 再解析 "  ✗ …" 的做法（docs/IMPROVEMENT_ASSESSMENT.md 第 9 項）。
 *
 * 不依賴 playwright：run-all.js 只需要這個模組，不必載入瀏覽器。
 */
const recorded = [];

/**
 * 登記一條失敗。message 的第一行是檢查名稱，其餘行是細節（例如逐條列出的錯誤）。
 */
function fail(message) {
  const text = String(message);
  const cut = text.indexOf('\n');
  const check = (cut < 0 ? text : text.slice(0, cut)).trim();
  const detail = cut < 0
    ? ''
    : text.slice(cut + 1).split('\n').map((line) => line.trim()).filter(Boolean).join('\n');
  recorded.push({ check, detail });
}

/** 取出目前登記的所有失敗並清空；run-all.js 每跑完一個套件呼叫一次。 */
function take() {
  return recorded.splice(0);
}

module.exports = { fail, take };
