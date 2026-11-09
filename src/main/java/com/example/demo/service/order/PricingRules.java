package com.example.demo.service.order;

import com.example.demo.entity.BrandToppingSetting;
import com.example.demo.entity.ProductSpecRelation;
import com.example.demo.entity.ProductToppingRule;
import com.example.demo.exception.CustomException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 售價規則中「單一維度」的判斷（stage 31）。不查資料庫、不需要 Spring context。
 *
 * <p>容量的選擇、配料的比對與配料數量上限，逐品項的 {@code PricingService} 方法與建單的整張訂單定價
 * （{@code PricingService.OrderPricing}）都經過這裡，同一套判斷只寫一份。輸入是已經撈回的規格列與配料規則，
 * 查詢本身由呼叫端負責，所以兩條路徑的差別只在資料從哪裡來，不在判斷的內容。
 *
 * <p>售價的組合（{@code 底價 + 區域加價 + 配料加價}）仍在 {@code PricingService}。
 */
public final class PricingRules {

    /** 規格類型的值，對應 brand_spec_setting.spec_type */
    public static final String SIZE = "SIZE";

    /** 配料數量上限未設定時的預設值（與 ProductTemplate.maxToppings 的預設一致） */
    public static final int DEFAULT_MAX_TOPPINGS = 3;

    /** 選定的容量：名稱與單價（尚未加區域加價） */
    public record SizeChoice(String name, BigDecimal price) { }

    private PricingRules() { }

    /** 規格類型：關聯自己的類型，沒有才退回平台主檔的類型 */
    public static String specType(ProductSpecRelation relation) {
        if (relation.getBrandSpec().getSpecType() != null) return relation.getBrandSpec().getSpecType();
        return relation.getBrandSpec().getMaster() != null ? relation.getBrandSpec().getMaster().getType() : "";
    }

    public static boolean isSize(ProductSpecRelation relation) {
        return SIZE.equalsIgnoreCase(specType(relation));
    }

    /** 容量名稱：品牌自訂名稱優先，沒有才用平台主檔名稱 */
    public static String sizeName(ProductSpecRelation relation) {
        if (relation.getBrandSpec().getCustomName() != null) return relation.getBrandSpec().getCustomName();
        if (relation.getBrandSpec().getMaster() != null) return relation.getBrandSpec().getMaster().getName();
        return "";
    }

    /**
     * 依請求選出容量。
     *
     * <ul>
     *   <li>沒有任何容量列：沿用請求值與底價（商品不分容量）</li>
     *   <li>只有一個容量，或請求為空：取價格最低者（唯一選項的情況就是它，請求值被忽略）</li>
     *   <li>其餘：名稱必須與請求（去除前後空白）完全相符，否則拒絕</li>
     * </ul>
     *
     * @param base      商品底價，容量列的價格為 null 時回退使用
     * @param relations 該商品的規格關聯，可含非容量的規格（會被忽略）
     */
    public static SizeChoice chooseSize(BigDecimal base, List<ProductSpecRelation> relations, String requestedSize) {
        List<ProductSpecRelation> sizes = relations.stream().filter(PricingRules::isSize).toList();
        if (sizes.isEmpty()) return new SizeChoice(requestedSize, base);

        ProductSpecRelation selected;
        if (sizes.size() == 1 || requestedSize == null || requestedSize.isBlank()) {
            selected = sizes.stream()
                    .min(Comparator.comparing(relation -> relation.getPrice() != null ? relation.getPrice() : base))
                    .orElseThrow();
        } else {
            selected = sizes.stream()
                    .filter(relation -> sizeName(relation).equals(requestedSize.trim()))
                    .findFirst()
                    .orElseThrow(() -> new CustomException("400", "所選容量不適用於此商品"));
        }
        return new SizeChoice(sizeName(selected), selected.getPrice() != null ? selected.getPrice() : base);
    }

    /**
     * 允許選擇的配料：關聯的品牌配料必須啟用、有 id，且屬於商品所屬的品牌。
     * 跨品牌的配料一律不採用，即使商品的規則表裡有它。
     *
     * @param rules          該商品的配料規則（null 視為沒有）
     * @param productBrandId 商品所屬品牌的 id，呼叫端須先確認非 null
     */
    public static List<BrandToppingSetting> allowedToppings(List<ProductToppingRule> rules, Long productBrandId) {
        if (rules == null) return List.of();
        return rules.stream()
                .map(ProductToppingRule::getBrandTopping)
                .filter(setting -> setting != null && setting.getId() != null
                        && Boolean.TRUE.equals(setting.getIsEnabled()))
                .filter(setting -> setting.getBrand() != null
                        && productBrandId.equals(setting.getBrand().getId()))
                .toList();
    }

    /** 配料名稱：品牌自訂名稱優先，沒有才用平台主檔名稱 */
    public static String toppingName(BrandToppingSetting setting) {
        if (setting.getCustomName() != null) return setting.getCustomName();
        return setting.getMasterTopping() != null ? setting.getMasterTopping().getName() : null;
    }

    /** 配料單價：品牌價優先，沒有才用平台預設價，兩者皆無為 0 */
    public static BigDecimal toppingPrice(BrandToppingSetting setting) {
        BigDecimal price = setting.getBrandPrice();
        if (price == null && setting.getMasterTopping() != null) {
            price = setting.getMasterTopping().getDefaultPrice();
        }
        return price != null ? price : BigDecimal.ZERO;
    }

    /**
     * 配料數量上限：未設定時為 {@link #DEFAULT_MAX_TOPPINGS}；上限為負數，或選擇數超過上限，都拒絕。
     */
    public static void checkToppingCount(Integer configuredMax, int count) {
        int max = configuredMax != null ? configuredMax : DEFAULT_MAX_TOPPINGS;
        if (max < 0 || count > max) {
            throw new CustomException("400", "該商品最多只能選擇 " + Math.max(0, max) + " 種配料");
        }
    }

    /**
     * 依請求順序比對允許的配料（名稱先去除前後空白）。同一配料（id 或名稱）重複選擇即拒絕。
     * 回傳的是品牌配料本身，價格由 {@link #toppingPrice} 取得。
     */
    public static List<BrandToppingSetting> pickToppings(List<BrandToppingSetting> allowed, List<String> names) {
        List<BrandToppingSetting> picked = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        Set<String> selectedNames = new HashSet<>();
        for (String requestedName : names) {
            String name = requestedName == null ? "" : requestedName.trim();
            BrandToppingSetting match = allowed.stream()
                    .filter(setting -> name.equals(toppingName(setting)))
                    .findFirst()
                    .orElseThrow(() -> new CustomException("400", "此商品未供應所選配料"));
            if (match.getId() == null || !selectedIds.add(match.getId())) {
                throw new CustomException("400", "配料不可重複選擇");
            }
            if (!selectedNames.add(toppingName(match))) {
                throw new CustomException("400", "配料不可重複選擇");
            }
            picked.add(match);
        }
        return List.copyOf(picked);
    }
}
