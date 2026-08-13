package com.example.demo.service.order;

import com.example.demo.entity.OrderItemTopping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 訂單品項的識別碼：把「同一款、同規格、同配料、同優惠券」的品項歸成一組。
 *
 * <p>前端靠它把購物車與揪團清單裡完全相同的品項合併顯示成「x2」，
 * 套了優惠券的那一杯則因為 couponId 不同而被拆開單獨顯示。
 *
 * <p>抽出來的理由：原本在 {@code GroupOrderService} 內有 6 個呼叫點，
 * 而「配料要先排序再串接」這個細節散落在各處自己 join 一次——
 * 只要有一處忘記排序，同樣的兩杯就會算出不同的 hash 而不會合併。
 * 排序責任收到 {@link #toppingsKey} 裡，呼叫端就不會漏。
 */
public final class ItemHash {

    private ItemHash() {
    }

    /**
     * 配料的正規化字串：**一定要排序**，否則同樣的配料組合因為加入順序不同
     * 就會算出不同的 hash。
     */
    public static String toppingsKey(Collection<OrderItemTopping> toppings) {
        if (toppings == null || toppings.isEmpty()) return "";
        return toppings.stream()
                .filter(t -> t != null && t.getId() != null)
                .map(t -> t.getId().getToppingNameSnapshot())
                .filter(n -> n != null)
                .sorted()
                .collect(Collectors.joining(","));
    }

    public static String of(Long productId, String sugar, String ice, String size,
                            String toppingsKey, Long couponId) {
        String base = productId + "|" + sugar + "|" + ice + "|" + size + "|" + toppingsKey + "|"
                + (couponId != null ? couponId : "none");
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(base.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // 理論上 MD5 一定存在；真的取不到就退回字串雜湊，至少維持「相同輸入 → 相同輸出」
            return String.valueOf(base.hashCode());
        }
    }
}
