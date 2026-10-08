package com.example.demo.service;

import com.example.demo.entity.BrandRegionCategoryPricing;
import com.example.demo.entity.BrandToppingSetting;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.ProductSpecRelation;
import com.example.demo.entity.ProductToppingRule;
import com.example.demo.entity.Store;
import com.example.demo.repository.BrandRegionCategoryPricingRepository;
import com.example.demo.repository.BrandToppingSettingRepository;
import com.example.demo.repository.ProductSpecRelationRepository;
import com.example.demo.repository.ProductToppingRuleRepository;
import com.example.demo.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

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
        return resolveToppings(brandId, names).stream()
                .map(ToppingPrice::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Resolve client-visible topping names to enabled brand settings and server prices. */
    public List<ToppingPrice> resolveToppings(Long brandId, List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        if (brandId == null) throw new CustomException("400", "商品沒有有效的品牌配料設定");

        List<BrandToppingSetting> settings = brandToppingSettingRepository.findByBrandIdAndIsEnabledTrue(brandId);
        List<ToppingPrice> resolved = new java.util.ArrayList<>();
        for (String requestedName : names) {
            String name = requestedName == null ? "" : requestedName.trim();
            BrandToppingSetting match = settings.stream()
                    .filter(setting -> name.equals(toppingName(setting)))
                    .findFirst()
                    .orElseThrow(() -> new CustomException("400", "包含此品牌未供應的配料"));
            BigDecimal price = match.getBrandPrice();
            if (price == null && match.getMasterTopping() != null) {
                price = match.getMasterTopping().getDefaultPrice();
            }
            resolved.add(new ToppingPrice(toppingName(match), price != null ? price : BigDecimal.ZERO));
        }
        return List.copyOf(resolved);
    }

    /** Resolve only enabled toppings configured for this product and enforce its selection limit. */
    public List<ToppingPrice> resolveToppings(ProductTemplate product, List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        List<BrandToppingSetting> allowed = productToppings(product);
        validateToppingCount(product, names.size());

        List<ToppingPrice> resolved = new java.util.ArrayList<>();
        java.util.Set<Long> selectedIds = new java.util.HashSet<>();
        java.util.Set<String> selectedNames = new java.util.HashSet<>();
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
            resolved.add(toToppingPrice(match));
        }
        return List.copyOf(resolved);
    }

    /** Resolve numeric topping IDs against this product's rules; accepts setting or master IDs. */
    public List<ToppingPrice> resolveToppingsByIds(ProductTemplate product, List<? extends Number> toppingIds) {
        if (toppingIds == null || toppingIds.isEmpty()) return List.of();
        List<BrandToppingSetting> allowed = productToppings(product);
        validateToppingCount(product, toppingIds.size());

        List<ToppingPrice> resolved = new java.util.ArrayList<>();
        java.util.Set<Long> selectedIds = new java.util.HashSet<>();
        java.util.Set<String> selectedNames = new java.util.HashSet<>();
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
            if (!selectedNames.add(toppingName(match))) {
                throw new CustomException("400", "配料不可重複選擇");
            }
            resolved.add(toToppingPrice(match));
        }
        return List.copyOf(resolved);
    }

    private List<BrandToppingSetting> productToppings(ProductTemplate product) {
        if (product == null || product.getId() == null || product.getBrand() == null
                || product.getBrand().getId() == null) {
            throw new CustomException("400", "商品沒有有效的品牌配料設定");
        }
        List<ProductToppingRule> rules = productToppingRuleRepository.findByIdProductId(product.getId());
        if (rules == null) return List.of();
        return rules.stream()
                .map(ProductToppingRule::getBrandTopping)
                .filter(setting -> setting != null && setting.getId() != null
                        && Boolean.TRUE.equals(setting.getIsEnabled()))
                .filter(setting -> setting.getBrand() != null
                        && product.getBrand().getId().equals(setting.getBrand().getId()))
                .toList();
    }

    private void validateToppingCount(ProductTemplate product, int count) {
        int max = product.getMaxToppings() != null ? product.getMaxToppings() : 3;
        if (max < 0 || count > max) {
            throw new CustomException("400", "該商品最多只能選擇 " + Math.max(0, max) + " 種配料");
        }
    }

    private String toppingName(BrandToppingSetting setting) {
        if (setting.getCustomName() != null) return setting.getCustomName();
        return setting.getMasterTopping() != null ? setting.getMasterTopping().getName() : null;
    }

    private ToppingPrice toToppingPrice(BrandToppingSetting setting) {
        BigDecimal price = setting.getBrandPrice();
        if (price == null && setting.getMasterTopping() != null) {
            price = setting.getMasterTopping().getDefaultPrice();
        }
        return new ToppingPrice(toppingName(setting), price != null ? price : BigDecimal.ZERO);
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

    private SizePrice resolveSize(ProductTemplate product, String requestedSize) {
        BigDecimal base = product != null && product.getBasePrice() != null ? product.getBasePrice() : BigDecimal.ZERO;
        if (product == null || product.getId() == null) return new SizePrice(requestedSize, base);

        List<ProductSpecRelation> sizePrices = productSpecRelationRepository
                .findSizePricingsByProductId(product.getId()).stream()
                .filter(relation -> "SIZE".equalsIgnoreCase(specType(relation)))
                .toList();
        if (sizePrices.isEmpty()) return new SizePrice(requestedSize, base);

        ProductSpecRelation selected;
        if (sizePrices.size() == 1 || requestedSize == null || requestedSize.isBlank()) {
            selected = sizePrices.stream()
                    .min(java.util.Comparator.comparing(relation ->
                            relation.getPrice() != null ? relation.getPrice() : base))
                    .orElseThrow();
        } else {
            selected = sizePrices.stream()
                    .filter(relation -> sizeName(relation).equals(requestedSize.trim()))
                    .findFirst()
                    .orElseThrow(() -> new CustomException("400", "所選容量不適用於此商品"));
        }
        return new SizePrice(sizeName(selected), selected.getPrice() != null ? selected.getPrice() : base);
    }

    private String specType(ProductSpecRelation relation) {
        if (relation.getBrandSpec().getSpecType() != null) return relation.getBrandSpec().getSpecType();
        return relation.getBrandSpec().getMaster() != null ? relation.getBrandSpec().getMaster().getType() : "";
    }

    private String sizeName(ProductSpecRelation relation) {
        if (relation.getBrandSpec().getCustomName() != null) return relation.getBrandSpec().getCustomName();
        if (relation.getBrandSpec().getMaster() != null) return relation.getBrandSpec().getMaster().getName();
        return "";
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
        return resolveToppings(product, names).stream()
                .map(ToppingPrice::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public record ToppingPrice(String name, BigDecimal price) { }

    private record SizePrice(String name, BigDecimal price) { }
}
