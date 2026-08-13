package com.example.demo.service;

import com.example.demo.entity.BrandRegionCategoryPricing;
import com.example.demo.entity.BrandToppingSetting;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Store;
import com.example.demo.repository.BrandRegionCategoryPricingRepository;
import com.example.demo.repository.BrandToppingSettingRepository;
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
        if (brandId == null || names == null || names.isEmpty())
            return BigDecimal.ZERO;
        List<BrandToppingSetting> settings = brandToppingSettingRepository.findByBrandId(brandId);
        BigDecimal total = BigDecimal.ZERO;
        for (String name : names) {
            for (BrandToppingSetting s : settings) {
                String displayName = s.getCustomName() != null ? s.getCustomName()
                        : s.getMasterTopping().getName();
                if (displayName.equals(name)) {
                    BigDecimal price = s.getBrandPrice() != null ? s.getBrandPrice()
                            : s.getMasterTopping().getDefaultPrice();
                    total = total.add(price != null ? price : BigDecimal.ZERO);
                    break;
                }
            }
        }
        return total;
    }

    /** 不含配料的單價：底價 + 區域加價 */
    public BigDecimal unitPrice(Store store, ProductTemplate product) {
        BigDecimal base = (product != null && product.getBasePrice() != null) ? product.getBasePrice()
                : BigDecimal.ZERO;
        return base.add(regionOffset(store, product));
    }

    /** 一杯的成交價：單價 + 配料加價 */
    public BigDecimal itemPrice(Store store, ProductTemplate product, List<String> toppingNames) {
        Long brandId = (store != null && store.getBrand() != null) ? store.getBrand().getId()
                : (product != null && product.getBrand() != null ? product.getBrand().getId() : null);
        return unitPrice(store, product).add(toppingExtra(brandId, toppingNames));
    }
}
