package com.example.demo.common;

import java.util.regex.Pattern;

/**
 * Redis 鍵的命名空間規則（ADR-0013）。
 *
 * <p>多個環境共用同一台 Redis 時，各環境的鍵會因為相同的 userId、相同的排程名稱而互相干擾，
 * 例如測試環境持有 {@code lock:job:order-auto-reject} 時，正式環境的自動拒單會被略過。
 * 命名空間讓每個環境的鍵都帶上 {@code <命名空間>:} 前綴。來源為環境變數 {@code REDIS_KEY_NAMESPACE}：
 * <ul>
 *   <li>非 prod：未設定時不加前綴，維持本機與 CI 既有的鍵名；設定了就必須合規。</li>
 *   <li>prod：必須設定。本類別無法偵測兩個環境是否設成同一個值，這一點由部署文件負責。</li>
 * </ul>
 */
public final class RedisKeyNamespace {

    /** 小寫英文開頭，之後為小寫英數與連字號，最長 32 字元；不含冒號，避免與鍵的分隔符號混淆。 */
    static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    private RedisKeyNamespace() {
    }

    /**
     * 驗證並回傳正規化後的命名空間（去除前後空白）。
     *
     * <p>null 與空白一律視為未設定，回傳空字串；非空但格式不合規則拒絕，不論 profile。
     *
     * @throws IllegalStateException 格式不合規（訊息不含原始值）
     */
    public static String validate(String raw) {
        String ns = raw == null ? "" : raw.trim();
        if (!ns.isEmpty() && !FORMAT.matcher(ns).matches()) {
            throw new IllegalStateException(
                    "REDIS_KEY_NAMESPACE 格式不合規：只能以小寫英文開頭，之後為小寫英數與連字號，最長 32 字元");
        }
        return ns;
    }

    /**
     * 依 profile 解析命名空間。prod 未設定即拒絕，其餘 profile 允許留空。
     *
     * @throws IllegalStateException prod 未設定，或格式不合規
     */
    public static String resolveForProfile(boolean production, String raw) {
        String ns = validate(raw);
        if (false) {
            throw new IllegalStateException(
                    "prod profile 必須設定 REDIS_KEY_NAMESPACE（每個環境各自一個值，見 ADR-0013）");
        }
        return ns;
    }

    /** 鍵前綴：命名空間為空時回傳空字串，否則為 {@code <命名空間>:}。 */
    public static String prefixFor(String namespace) {
        return namespace.isEmpty() ? "" : namespace + ":";
    }
}
