package com.example.demo.service;

import com.example.demo.entity.Brand;
import com.example.demo.entity.BrandRegionCategoryPricing;
import com.example.demo.entity.BrandSpecSetting;
import com.example.demo.entity.BrandToppingSetting;
import com.example.demo.entity.MenuCategory;
import com.example.demo.entity.ProductSpecRelation;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.ProductToppingRule;
import com.example.demo.entity.Region;
import com.example.demo.entity.SpecMaster;
import com.example.demo.entity.Store;
import com.example.demo.entity.ToppingMaster;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.BrandRegionCategoryPricingRepository;
import com.example.demo.repository.BrandToppingSettingRepository;
import com.example.demo.repository.ProductSpecRelationRepository;
import com.example.demo.repository.ProductToppingRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;

/**
 * 建單定價的黃金測試（stage 31，第一個 commit）。
 *
 * <p>期望值取自改寫前的現行程式，涵蓋尺寸、區域加價、配料、自訂配料、主檔為 null、停用、
 * 其他品牌的配料、重複選擇、數量上限與價格回退。這一組測試在重構前必須是綠的，
 * 才能證明期望值就是現行行為，而不是我的推測；重構後的批次路徑必須通過同一組期望值。
 *
 * <p>固定夾具：品牌 7、地區 3、分類 5（有加價 5.00）與分類 6（沒有加價）、商品 12、底價 35.00。
 */
@ExtendWith(MockitoExtension.class)
class PricingGoldenTest {

    private static final long BRAND = 7L;
    private static final long REGION = 3L;
    private static final long CATEGORY = 5L;
    private static final long OTHER_CATEGORY = 6L;
    private static final long PRODUCT = 12L;

    @Mock private BrandToppingSettingRepository brandToppingSettingRepository;
    @Mock private BrandRegionCategoryPricingRepository brandRegionCategoryPricingRepository;
    @Mock private ProductSpecRelationRepository productSpecRelationRepository;
    @Mock private ProductToppingRuleRepository productToppingRuleRepository;
    @InjectMocks private PricingService pricingService;

    // ─── 尺寸 ───────────────────────────────────────────

    @Test
    void singleSizeOptionIsChosenWhateverTheCustomerRequested() {
        sizes(sizeRelation("大杯", "50.00"));
        regionOffset("5.00");
        ProductTemplate product = product(CATEGORY);

        assertEquals("大杯", pricingService.resolveSizeName(product, "中杯"));
        assertEquals(new BigDecimal("55.00"), pricingService.unitPrice(storeInRegion(), product, "中杯"));
    }

    @Test
    void requestedSizeIsMatchedWhenSeveralOptionsExist() {
        sizes(sizeRelation("中杯", "35.00"), sizeRelation("大杯", "50.00"));
        regionOffset("5.00");
        ProductTemplate product = product(CATEGORY);

        assertEquals("大杯", pricingService.resolveSizeName(product, "大杯"));
        assertEquals(new BigDecimal("55.00"), pricingService.unitPrice(storeInRegion(), product, "大杯"));
    }

    @Test
    void blankOrMissingSizeFallsBackToTheCheapestOption() {
        sizes(sizeRelation("中杯", "35.00"), sizeRelation("大杯", "50.00"));
        regionOffset("5.00");
        ProductTemplate product = product(CATEGORY);

        assertEquals("中杯", pricingService.resolveSizeName(product, ""));
        assertEquals("中杯", pricingService.resolveSizeName(product, null));
        assertEquals(new BigDecimal("40.00"), pricingService.unitPrice(storeInRegion(), product, ""));
        assertEquals(new BigDecimal("40.00"), pricingService.unitPrice(storeInRegion(), product, null));
    }

    @Test
    void unknownSizeIsRejected() {
        sizes(sizeRelation("中杯", "35.00"), sizeRelation("大杯", "50.00"));
        ProductTemplate product = product(CATEGORY);

        assertRejected("所選容量不適用於此商品", () -> pricingService.resolveSizeName(product, "特大杯"));
    }

    @Test
    void productWithoutSizePricingKeepsTheRequestedSizeAndBasePrice() {
        sizes();
        regionOffset("5.00");
        ProductTemplate product = product(CATEGORY);

        assertEquals("中杯", pricingService.resolveSizeName(product, "中杯"));
        assertNull(pricingService.resolveSizeName(product, null));
        assertEquals(new BigDecimal("40.00"), pricingService.unitPrice(storeInRegion(), product, "中杯"));
    }

    @Test
    void nonSizeSpecificationsAreIgnoredWhenPricingSizes() {
        sizes(specRelation("ICE", "去冰", "50.00"));
        regionOffset("5.00");
        ProductTemplate product = product(CATEGORY);

        assertEquals("中杯", pricingService.resolveSizeName(product, "中杯"));
        assertEquals(new BigDecimal("40.00"), pricingService.unitPrice(storeInRegion(), product, "中杯"));
    }

    @Test
    void sizeNameFallsBackToTheMasterWhenThereIsNoCustomName() {
        sizes(masterSizeRelation("大杯", "50.00"));
        regionOffset("5.00");
        ProductTemplate product = product(CATEGORY);

        assertEquals("大杯", pricingService.resolveSizeName(product, "中杯"));
        assertEquals(new BigDecimal("55.00"), pricingService.unitPrice(storeInRegion(), product, "中杯"));
    }

    // ─── 區域加價 ───────────────────────────────────────

    @Test
    void regionOffsetIsZeroWithoutRegionOrCategory() {
        sizes(sizeRelation("大杯", "50.00"));

        assertEquals(new BigDecimal("50.00"),
                pricingService.unitPrice(storeWithoutRegion(), product(CATEGORY), "大杯"));
        assertEquals(new BigDecimal("50.00"),
                pricingService.unitPrice(storeInRegion(), product(null), "大杯"));
    }

    @Test
    void categoryWithoutAnOffsetRowAddsNothing() {
        sizes(sizeRelation("大杯", "50.00"));

        assertEquals(new BigDecimal("50.00"),
                pricingService.unitPrice(storeInRegion(), product(OTHER_CATEGORY), "大杯"));
    }

    @Test
    void offsetRowWithoutAPriceAddsNothing() {
        sizes(sizeRelation("大杯", "50.00"));
        regionOffset(null);

        assertEquals(new BigDecimal("50.00"),
                pricingService.unitPrice(storeInRegion(), product(CATEGORY), "大杯"));
    }

    // ─── 配料 ───────────────────────────────────────────

    @Test
    void noSelectedToppingsCostNothing() {
        toppingRules();
        sizes(sizeRelation("大杯", "50.00"));
        regionOffset("5.00");
        ProductTemplate product = product(CATEGORY);

        assertEquals(List.of(), pricingService.resolveToppings(product, List.of()));
        assertEquals(new BigDecimal("55.00"), pricingService.itemPrice(storeInRegion(), product, "大杯", List.of()));
    }

    @Test
    void masterToppingWithoutBrandPriceUsesTheMasterDefault() {
        toppingRules(masterTopping(81L, "珍珠", "10.00", null));
        ProductTemplate product = product(CATEGORY);

        assertEquals(List.of(new PricingService.ToppingPrice("珍珠", new BigDecimal("10.00"))),
                pricingService.resolveToppings(product, List.of("珍珠")));
    }

    @Test
    void brandPriceOverridesTheMasterDefault() {
        toppingRules(masterTopping(83L, "椰果", "12.00", "8.00"));
        ProductTemplate product = product(CATEGORY);

        assertEquals(List.of(new PricingService.ToppingPrice("椰果", new BigDecimal("8.00"))),
                pricingService.resolveToppings(product, List.of("椰果")));
    }

    @Test
    void customToppingUsesItsOwnPrice() {
        toppingRules(customTopping(82L, "芋圓", "15.00"));
        ProductTemplate product = product(CATEGORY);

        assertEquals(List.of(new PricingService.ToppingPrice("芋圓", new BigDecimal("15.00"))),
                pricingService.resolveToppings(product, List.of("芋圓")));
    }

    @Test
    void customToppingWithoutPriceIsFree() {
        toppingRules(customTopping(84L, "芋圓", null));
        ProductTemplate product = product(CATEGORY);

        assertEquals(List.of(new PricingService.ToppingPrice("芋圓", BigDecimal.ZERO)),
                pricingService.resolveToppings(product, List.of("芋圓")));
    }

    @Test
    void itemPriceAddsSizeRegionAndToppings() {
        toppingRules(masterTopping(81L, "珍珠", "10.00", null), customTopping(82L, "芋圓", "15.00"));
        sizes(sizeRelation("大杯", "50.00"));
        regionOffset("5.00");
        ProductTemplate product = product(CATEGORY);

        assertEquals(new BigDecimal("80.00"),
                pricingService.itemPrice(storeInRegion(), product, "大杯", List.of("珍珠", "芋圓")));
    }

    @Test
    void disabledToppingIsNotOffered() {
        BrandToppingSetting disabled = customTopping(85L, "布丁", "10.00");
        disabled.setIsEnabled(false);
        toppingRules(disabled);
        ProductTemplate product = product(CATEGORY);

        assertRejected("此商品未供應所選配料", () -> pricingService.resolveToppings(product, List.of("布丁")));
    }

    @Test
    void toppingOfAnotherBrandIsNotOffered() {
        BrandToppingSetting foreign = customTopping(86L, "布丁", "10.00");
        foreign.setBrand(brand(99L));
        toppingRules(foreign);
        ProductTemplate product = product(CATEGORY);

        assertRejected("此商品未供應所選配料", () -> pricingService.resolveToppings(product, List.of("布丁")));
    }

    @Test
    void duplicateToppingIsRejected() {
        toppingRules(masterTopping(81L, "珍珠", "10.00", null));
        ProductTemplate product = product(CATEGORY);

        assertRejected("配料不可重複選擇",
                () -> pricingService.resolveToppings(product, List.of("珍珠", "珍珠")));
    }

    @Test
    void toppingLimitOfTheProductIsEnforced() {
        toppingRules(masterTopping(81L, "珍珠", "10.00", null), customTopping(82L, "芋圓", "15.00"));
        ProductTemplate product = product(CATEGORY);
        product.setMaxToppings(1);

        assertRejected("該商品最多只能選擇 1 種配料",
                () -> pricingService.resolveToppings(product, List.of("珍珠", "芋圓")));
    }

    @Test
    void missingToppingLimitDefaultsToThree() {
        toppingRules(masterTopping(81L, "珍珠", "10.00", null), customTopping(82L, "芋圓", "15.00"),
                masterTopping(83L, "椰果", "12.00", "8.00"));
        ProductTemplate product = product(CATEGORY);
        product.setMaxToppings(null);

        assertRejected("該商品最多只能選擇 3 種配料",
                () -> pricingService.resolveToppings(product, List.of("珍珠", "芋圓", "椰果", "珍珠")));
    }

    // ─── 夾具與樁 ───────────────────────────────────────

    private void sizes(ProductSpecRelation... relations) {
        lenient().when(productSpecRelationRepository.findSizePricingsByProductId(PRODUCT))
                .thenReturn(List.of(relations));
    }

    /** 品牌 7、地區 3、分類 5 的加價列；{@code offset} 為 null 時模擬價格欄位為空的列 */
    private void regionOffset(String offset) {
        BrandRegionCategoryPricing row = new BrandRegionCategoryPricing();
        row.setPriceOffset(offset == null ? null : new BigDecimal(offset));
        lenient().when(brandRegionCategoryPricingRepository
                .findByBrandIdAndRegionIdAndCategoryId(BRAND, REGION, CATEGORY))
                .thenReturn(Optional.of(row));
    }

    private void toppingRules(BrandToppingSetting... settings) {
        List<ProductToppingRule> rules = Arrays.stream(settings).map(PricingGoldenTest::ruleFor).toList();
        lenient().when(productToppingRuleRepository.findByIdProductId(PRODUCT)).thenReturn(rules);
    }

    private static ProductToppingRule ruleFor(BrandToppingSetting setting) {
        ProductToppingRule rule = new ProductToppingRule();
        rule.setBrandTopping(setting);
        return rule;
    }

    private static ProductSpecRelation sizeRelation(String name, String price) {
        return specRelation("SIZE", name, price);
    }

    private static ProductSpecRelation specRelation(String specType, String name, String price) {
        BrandSpecSetting spec = new BrandSpecSetting();
        spec.setSpecType(specType);
        spec.setCustomName(name);
        ProductSpecRelation relation = new ProductSpecRelation();
        relation.setBrandSpec(spec);
        relation.setPrice(new BigDecimal(price));
        return relation;
    }

    /** 規格類型與自訂名稱皆為空，改由平台主檔提供類型與名稱 */
    private static ProductSpecRelation masterSizeRelation(String masterName, String price) {
        SpecMaster master = new SpecMaster();
        master.setType("SIZE");
        master.setName(masterName);
        BrandSpecSetting spec = new BrandSpecSetting();
        spec.setMaster(master);
        ProductSpecRelation relation = new ProductSpecRelation();
        relation.setBrandSpec(spec);
        relation.setPrice(new BigDecimal(price));
        return relation;
    }

    private static BrandToppingSetting topping(long id, String brandPrice) {
        BrandToppingSetting setting = new BrandToppingSetting();
        setting.setId(id);
        setting.setBrand(brand(BRAND));
        setting.setIsEnabled(true);
        setting.setBrandPrice(brandPrice == null ? null : new BigDecimal(brandPrice));
        return setting;
    }

    /** 平台主檔配料：名稱與預設價來自主檔，品牌價可覆蓋 */
    private static BrandToppingSetting masterTopping(long id, String masterName, String defaultPrice,
            String brandPrice) {
        ToppingMaster master = new ToppingMaster();
        master.setName(masterName);
        master.setDefaultPrice(new BigDecimal(defaultPrice));
        BrandToppingSetting setting = topping(id, brandPrice);
        setting.setMasterTopping(master);
        return setting;
    }

    /** 品牌自訂配料：master 為 null，名稱與價格都由品牌設定 */
    private static BrandToppingSetting customTopping(long id, String name, String brandPrice) {
        BrandToppingSetting setting = topping(id, brandPrice);
        setting.setCustomName(name);
        return setting;
    }

    private static Brand brand(long id) {
        Brand brand = new Brand();
        brand.setId(id);
        return brand;
    }

    private static Region region(long id) {
        Region region = new Region();
        region.setId(id);
        return region;
    }

    private static MenuCategory category(long id) {
        MenuCategory category = new MenuCategory();
        category.setId(id);
        return category;
    }

    private static ProductTemplate product(Long categoryId) {
        ProductTemplate product = new ProductTemplate();
        product.setId(PRODUCT);
        product.setBrand(brand(BRAND));
        product.setBasePrice(new BigDecimal("35.00"));
        if (categoryId != null) {
            product.setCategory(category(categoryId));
        }
        return product;
    }

    private static Store storeInRegion() {
        Store store = new Store();
        store.setId(10L);
        store.setBrand(brand(BRAND));
        store.setRegion(region(REGION));
        return store;
    }

    private static Store storeWithoutRegion() {
        Store store = new Store();
        store.setId(11L);
        store.setBrand(brand(BRAND));
        return store;
    }

    private static void assertRejected(String message, Executable action) {
        CustomException ex = assertThrows(CustomException.class, action);
        assertEquals("400", ex.getCode());
        assertEquals(message, ex.getMsg());
    }
}
