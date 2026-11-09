package com.example.demo.service;

import com.example.demo.entity.BrandRegionCategoryPricing;
import com.example.demo.entity.BrandToppingSetting;
import com.example.demo.entity.ProductSpecRelation;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.ProductToppingRule;
import com.example.demo.entity.Store;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.BrandRegionCategoryPricingRepository;
import com.example.demo.repository.BrandToppingSettingRepository;
import com.example.demo.repository.ProductSpecRelationRepository;
import com.example.demo.repository.ProductToppingRuleRepository;
import com.example.demo.service.order.PricingRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 品項售價的唯一計算來源：{@code basePrice + 區域加價 + 配料加價}。
 *
 * <p>⚠️ 這條公式原本只存在於 {@code CartService.addItem} 裡，而
 * {@code POST /api/orders/checkout} 完全採信用戶端送來的 {@code finalPrice}／
 * {@code totalAmount}——實測帶 {@code finalPrice: 1} 就能用 $1 買走 $35 的飲料，
 * 錢包也只被扣 $1。
 *
 * <p>任何會產生金額的路徑都必須經過這裡。**不要在 Service 內再寫一份**：
 * 少算區域加價會讓同品牌不同地區的定價失效，少算配料加價則是直接漏收錢。
 *
 * <p>兩種用法（stage 31 起）：
 * <ul>
 *   <li>逐品項：購物車與揪團使用的方法，每次呼叫查詢該商品的規格與配料，查詢次數與品項數成正比。</li>
 *   <li>整張訂單：建單使用 {@link #forOrder}，整張單的容量列、配料規則與區域加價各查一次，之後逐品項計算不再查資料庫。</li>
 * </ul>
 * 兩種用法的判斷都在 {@link PricingRules}，{@code OrderPricingTest} 比對兩者結果相同。
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final BrandToppingSettingRepository brandToppingSettingRepository;
    private final BrandRegionCategoryPricingRepository brandRegionCategoryPricingRepository;
    private final ProductSpecRelationRepository productSpecRelationRepository;
    private final ProductToppingRuleRepository productToppingRuleRepository;

    /** 該分店所在地區對這個分類的加價（沒有設定就是 0） */
    public BigDecimal regionOffset(Store store, ProductTemplate product) {
        if (store == null || product == null || store.getRegion() == null || product.getCategory() == null
                || store.getBrand() == null)
            return BigDecimal.ZERO;
        return brandRegionCategoryPricingRepository
                .findByBrandIdAndRegionIdAndCategoryId(store.getBrand().getId(), store.getRegion().getId(),
                        product.getCategory().getId())
                .map(BrandRegionCategoryPricing::getPriceOffset)
                .orElse(BigDecimal.ZERO);
    }

    /** 配料加價總和。名稱比對品牌的自訂名稱，價格優先取品牌價、沒有才用總表預設價 */
    public BigDecimal toppingExtra(Long brandId, List<String> names) {
        return toppingSum(resolveToppings(brandId, names));
    }

    /** Resolve client-visible topping names to enabled brand settings and server prices. */
    public List<ToppingPrice> resolveToppings(Long brandId, List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        if (brandId == null) throw new CustomException("400", "商品沒有有效的品牌配料設定");

        List<BrandToppingSetting> settings = brandToppingSettingRepository.findByBrandIdAndIsEnabledTrue(brandId);
        List<ToppingPrice> resolved = new ArrayList<>();
        for (String requestedName : names) {
            String name = requestedName == null ? "" : requestedName.trim();
            BrandToppingSetting match = settings.stream()
                    .filter(setting -> name.equals(PricingRules.toppingName(setting)))
                    .findFirst()
                    .orElseThrow(() -> new CustomException("400", "包含此品牌未供應的配料"));
            resolved.add(toToppingPrice(match));
        }
        return List.copyOf(resolved);
    }

    /** Resolve only enabled toppings configured for this product and enforce its selection limit. */
    public List<ToppingPrice> resolveToppings(ProductTemplate product, List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        List<BrandToppingSetting> allowed = productToppings(product);
        PricingRules.checkToppingCount(product.getMaxToppings(), names.size());
        return toToppingPrices(PricingRules.pickToppings(allowed, names));
    }

    /** Resolve numeric topping IDs against this product's rules; accepts setting or master IDs. */
    public List<ToppingPrice> resolveToppingsByIds(ProductTemplate product, List<? extends Number> toppingIds) {
        if (toppingIds == null || toppingIds.isEmpty()) return List.of();
        List<BrandToppingSetting> allowed = productToppings(product);
        PricingRules.checkToppingCount(product.getMaxToppings(), toppingIds.size());

        List<ToppingPrice> resolved = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        Set<String> selectedNames = new HashSet<>();
        for (Number rawId : toppingIds) {
            long requestedId;
            try {
                requestedId = new BigDecimal(String.valueOf(rawId)).longValueExact();
            } catch (NumberFormatException | ArithmeticException | NullPointerException e) {
                throw new CustomException("400", "配料格式錯誤");
            }
            BrandToppingSetting match = allowed.stream()
                    .filter(setting -> setting.getId().equals(requestedId))
                    .findFirst()
                    .orElseGet(() -> allowed.stream()
                            .filter(setting -> setting.getMasterTopping() != null
                                    && setting.getMasterTopping().getId() != null
                                    && setting.getMasterTopping().getId().equals(requestedId))
                            .findFirst()
                            .orElseThrow(() -> new CustomException("400", "此商品未供應所選配料")));
            if (!selectedIds.add(match.getId())) {
                throw new CustomException("400", "配料不可重複選擇");
            }
            if (!selectedNames.add(PricingRules.toppingName(match))) {
                throw new CustomException("400", "配料不可重複選擇");
            }
            resolved.add(toToppingPrice(match));
        }
        return List.copyOf(resolved);
    }

    /** 不含配料的單價：底價 + 區域加價 */
    public BigDecimal unitPrice(Store store, ProductTemplate product) {
        BigDecimal base = (product != null && product.getBasePrice() != null) ? product.getBasePrice()
                : BigDecimal.ZERO;
        return base.add(regionOffset(store, product));
    }

    /** Resolve a selected size to its configured product price before adding region offset. */
    public BigDecimal unitPrice(Store store, ProductTemplate product, String requestedSize) {
        return resolveSize(product, requestedSize).price().add(regionOffset(store, product));
    }

    /** The canonical size label stored in an order/cart snapshot. */
    public String resolveSizeName(ProductTemplate product, String requestedSize) {
        return resolveSize(product, requestedSize).name();
    }

    /** 一杯的成交價：單價 + 配料加價 */
    public BigDecimal itemPrice(Store store, ProductTemplate product, List<String> toppingNames) {
        return unitPrice(store, product).add(toppingExtra(product, toppingNames));
    }

    public BigDecimal itemPrice(Store store, ProductTemplate product, String size,
            List<String> toppingNames) {
        return unitPrice(store, product, size).add(toppingExtra(product, toppingNames));
    }

    public BigDecimal toppingExtra(ProductTemplate product, List<String> names) {
        return toppingSum(resolveToppings(product, names));
    }

    /**
     * 為一張訂單撈回定價資料（建單用）。
     *
     * <p>容量列與配料規則各以一次 IN 查詢撈回整張單的商品，區域加價以一次查詢撈回本店所在地區的全部分類。
     * 查詢次數最多三次，與品項數無關；沒有商品或分店沒有地區時，不發出對應的查詢。
     */
    public OrderPricing forOrder(Store store, Collection<ProductTemplate> products) {
        List<Long> productIds = products.stream()
                .filter(Objects::nonNull)
                .map(ProductTemplate::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, List<ProductSpecRelation>> sizes = new HashMap<>();
        Map<Long, List<ProductToppingRule>> rules = new HashMap<>();
        if (!productIds.isEmpty()) {
            sizes.putAll(productSpecRelationRepository.findSizePricingsByProductIdIn(productIds).stream()
                    .collect(Collectors.groupingBy(relation -> relation.getId().getProductId())));
            rules.putAll(productToppingRuleRepository.findByIdProductIdIn(productIds).stream()
                    .collect(Collectors.groupingBy(rule -> rule.getId().getProductId())));
        }

        Map<Long, BigDecimal> offsets = new HashMap<>();
        if (store != null && store.getBrand() != null && store.getRegion() != null) {
            for (BrandRegionCategoryPricing row : brandRegionCategoryPricingRepository
                    .findByBrandIdAndRegionId(store.getBrand().getId(), store.getRegion().getId())) {
                if (row.getCategory() != null && row.getPriceOffset() != null) {
                    offsets.put(row.getCategory().getId(), row.getPriceOffset());
                }
            }
        }
        return new OrderPricing(offsets, sizes, rules);
    }

    /**
     * 配料加價的累加。逐品項與整張訂單共用這一個寫法，金額的小數位也由它決定。
     */
    static BigDecimal toppingSum(List<ToppingPrice> toppings) {
        return toppings.stream().map(ToppingPrice::price).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 逐品項：查詢該商品的容量列後交給 {@link PricingRules} 選擇（整張訂單的版本見 {@link OrderPricing}） */
    private PricingRules.SizeChoice resolveSize(ProductTemplate product, String requestedSize) {
        BigDecimal base = product != null && product.getBasePrice() != null ? product.getBasePrice() : BigDecimal.ZERO;
        if (product == null || product.getId() == null) return new PricingRules.SizeChoice(requestedSize, base);
        return PricingRules.chooseSize(base,
                productSpecRelationRepository.findSizePricingsByProductId(product.getId()), requestedSize);
    }

    /** 逐品項：查詢該商品允許的配料（守衛在查詢之前） */
    private List<BrandToppingSetting> productToppings(ProductTemplate product) {
        Long brandId = productBrandId(product);
        List<ProductToppingRule> rules = productToppingRuleRepository.findByIdProductId(product.getId());
        return PricingRules.allowedToppings(rules, brandId);
    }

    /** 商品所屬品牌的 id；商品或品牌資料不完整時拒絕 */
    private static Long productBrandId(ProductTemplate product) {
        if (product == null || product.getId() == null || product.getBrand() == null
                || product.getBrand().getId() == null) {
            throw new CustomException("400", "商品沒有有效的品牌配料設定");
        }
        return product.getBrand().getId();
    }

    private static List<ToppingPrice> toToppingPrices(List<BrandToppingSetting> settings) {
        return settings.stream().map(PricingService::toToppingPrice).toList();
    }

    private static ToppingPrice toToppingPrice(BrandToppingSetting setting) {
        return new ToppingPrice(PricingRules.toppingName(setting), PricingRules.toppingPrice(setting));
    }

    public record ToppingPrice(String name, BigDecimal price) { }

    /**
     * 一張訂單的定價資料（stage 31）。
     *
     * <p>由 {@link PricingService#forOrder} 一次撈回整張單的容量列、配料規則與本店地區加價，之後逐品項計算不再查資料庫。
     * 計算判斷與逐品項的方法共用 {@link PricingRules}，組合與累加共用 {@link PricingService#toppingSum}，
     * 兩條路徑對同一份資料必須得到相同結果（{@code OrderPricingTest} 逐項比對）。
     */
    public static final class OrderPricing {

        private final Map<Long, BigDecimal> offsetByCategory;
        private final Map<Long, List<ProductSpecRelation>> sizesByProduct;
        private final Map<Long, List<ProductToppingRule>> rulesByProduct;

        private OrderPricing(Map<Long, BigDecimal> offsetByCategory,
                Map<Long, List<ProductSpecRelation>> sizesByProduct,
                Map<Long, List<ProductToppingRule>> rulesByProduct) {
            this.offsetByCategory = offsetByCategory;
            this.sizesByProduct = sizesByProduct;
            this.rulesByProduct = rulesByProduct;
        }

        /** 同 {@link PricingService#resolveSizeName} */
        public String resolveSizeName(ProductTemplate product, String requestedSize) {
            return chooseSize(product, requestedSize).name();
        }

        /** 同 {@link PricingService#unitPrice(Store, ProductTemplate, String)}：容量單價 + 區域加價 */
        public BigDecimal unitPrice(ProductTemplate product, String size) {
            return chooseSize(product, size).price().add(regionOffset(product));
        }

        /** 同 {@link PricingService#resolveToppings(ProductTemplate, List)} */
        public List<ToppingPrice> resolveToppings(ProductTemplate product, List<String> names) {
            if (names == null || names.isEmpty()) return List.of();
            Long brandId = productBrandId(product);
            List<BrandToppingSetting> allowed = PricingRules.allowedToppings(
                    rulesByProduct.getOrDefault(product.getId(), List.of()), brandId);
            PricingRules.checkToppingCount(product.getMaxToppings(), names.size());
            return toToppingPrices(PricingRules.pickToppings(allowed, names));
        }

        /** 同 {@link PricingService#itemPrice(Store, ProductTemplate, String, List)} */
        public BigDecimal itemPrice(ProductTemplate product, String size, List<String> toppingNames) {
            return unitPrice(product, size).add(toppingSum(resolveToppings(product, toppingNames)));
        }

        private PricingRules.SizeChoice chooseSize(ProductTemplate product, String requestedSize) {
            if (product == null) return new PricingRules.SizeChoice(requestedSize, BigDecimal.ZERO);
            BigDecimal base = product.getBasePrice() != null ? product.getBasePrice() : BigDecimal.ZERO;
            return PricingRules.chooseSize(base,
                    sizesByProduct.getOrDefault(product.getId(), List.of()), requestedSize);
        }

        private BigDecimal regionOffset(ProductTemplate product) {
            if (product == null || product.getCategory() == null) return BigDecimal.ZERO;
            return offsetByCategory.getOrDefault(product.getCategory().getId(), BigDecimal.ZERO);
        }
    }
}
