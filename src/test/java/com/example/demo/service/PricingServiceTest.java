package com.example.demo.service;

import com.example.demo.entity.Brand;
import com.example.demo.entity.BrandSpecSetting;
import com.example.demo.entity.BrandToppingSetting;
import com.example.demo.entity.ProductSpecRelation;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.ProductToppingRule;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.BrandRegionCategoryPricingRepository;
import com.example.demo.repository.BrandToppingSettingRepository;
import com.example.demo.repository.ProductSpecRelationRepository;
import com.example.demo.repository.ProductToppingRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {
    @Mock private BrandToppingSettingRepository brandToppingSettingRepository;
    @Mock private BrandRegionCategoryPricingRepository brandRegionCategoryPricingRepository;
    @Mock private ProductSpecRelationRepository productSpecRelationRepository;
    @Mock private ProductToppingRuleRepository productToppingRuleRepository;
    @InjectMocks private PricingService pricingService;

    @Test
    void usesSelectedSizeAndEnabledBrandToppingPrices() {
        ProductTemplate product = productWithSizeOptions();
        BrandToppingSetting topping = new BrandToppingSetting();
        topping.setCustomName("珍珠");
        topping.setId(81L);
        Brand brand = new Brand();
        brand.setId(7L);
        topping.setBrand(brand);
        topping.setBrandPrice(new BigDecimal("8.00"));
        topping.setIsEnabled(true);
        when(productToppingRuleRepository.findByIdProductId(12L)).thenReturn(List.of(ruleFor(topping)));

        BigDecimal price = pricingService.itemPrice(null, product, "大杯", List.of("珍珠"));

        assertEquals(new BigDecimal("58.00"), price);
    }

    @Test
    void rejectsSizeThatIsNotConfiguredForTheProduct() {
        ProductTemplate product = productWithSizeOptions();

        assertThrows(CustomException.class,
                () -> pricingService.resolveSizeName(product, "特大杯"));
    }

    @Test
    void rejectsToppingsThatAreNotEnabledForTheBrand() {
        when(brandToppingSettingRepository.findByBrandIdAndIsEnabledTrue(7L)).thenReturn(List.of());

        assertThrows(CustomException.class,
                () -> pricingService.toppingExtra(7L, List.of("未供應配料")));
    }

    @Test
    void rejectsBrandToppingsNotConfiguredForTheProduct() {
        ProductTemplate product = productWithSizeOptions();
        BrandToppingSetting brandTopping = topping(81L, "珍珠", 7L);
        when(productToppingRuleRepository.findByIdProductId(12L)).thenReturn(List.of());

        assertThrows(CustomException.class,
                () -> pricingService.itemPrice(null, product, "大杯", List.of("珍珠")));
    }

    @Test
    void enforcesMaximumToppingCountAndRejectsDuplicateSelections() {
        ProductTemplate product = productWithIdentity();
        product.setMaxToppings(1);
        BrandToppingSetting pearl = topping(81L, "珍珠", 7L);
        BrandToppingSetting coconut = topping(82L, "椰果", 7L);
        when(productToppingRuleRepository.findByIdProductId(12L))
                .thenReturn(List.of(ruleFor(pearl), ruleFor(coconut)));

        assertThrows(CustomException.class,
                () -> pricingService.resolveToppings(product, List.of("珍珠", "椰果")));
        product.setMaxToppings(3);
        assertThrows(CustomException.class,
                () -> pricingService.resolveToppings(product, List.of("珍珠", "珍珠")));
    }

    private ProductTemplate productWithSizeOptions() {
        ProductTemplate product = productWithIdentity();

        ProductSpecRelation medium = sizeRelation("中杯", "35.00");
        ProductSpecRelation large = sizeRelation("大杯", "50.00");
        when(productSpecRelationRepository.findSizePricingsByProductId(12L)).thenReturn(List.of(medium, large));
        return product;
    }

    private ProductTemplate productWithIdentity() {
        Brand brand = new Brand();
        brand.setId(7L);
        ProductTemplate product = new ProductTemplate();
        product.setId(12L);
        product.setBrand(brand);
        product.setBasePrice(new BigDecimal("35.00"));
        return product;
    }

    private ProductSpecRelation sizeRelation(String name, String price) {
        BrandSpecSetting spec = new BrandSpecSetting();
        spec.setSpecType("SIZE");
        spec.setCustomName(name);
        ProductSpecRelation relation = new ProductSpecRelation();
        relation.setBrandSpec(spec);
        relation.setPrice(new BigDecimal(price));
        return relation;
    }

    private BrandToppingSetting topping(Long id, String name, Long brandId) {
        BrandToppingSetting setting = new BrandToppingSetting();
        setting.setId(id);
        setting.setCustomName(name);
        setting.setBrandPrice(new BigDecimal("8.00"));
        setting.setIsEnabled(true);
        Brand brand = new Brand();
        brand.setId(brandId);
        setting.setBrand(brand);
        return setting;
    }

    private ProductToppingRule ruleFor(BrandToppingSetting setting) {
        ProductToppingRule rule = new ProductToppingRule();
        rule.setBrandTopping(setting);
        return rule;
    }
}
