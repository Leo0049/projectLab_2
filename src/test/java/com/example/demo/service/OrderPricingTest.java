package com.example.demo.service;

import com.example.demo.entity.Brand;
import com.example.demo.entity.BrandRegionCategoryPricing;
import com.example.demo.entity.BrandSpecSetting;
import com.example.demo.entity.BrandToppingSetting;
import com.example.demo.entity.MenuCategory;
import com.example.demo.entity.ProductSpecRelation;
import com.example.demo.entity.ProductSpecRelationId;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.ProductToppingRule;
import com.example.demo.entity.ProductToppingRuleId;
import com.example.demo.entity.Region;
import com.example.demo.entity.Store;
import com.example.demo.entity.ToppingMaster;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 建單的整張訂單定價（{@link PricingService#forOrder}）與逐品項定價必須得到相同結果（stage 31）。
 *
 * <p>兩條路徑共用 {@code PricingRules}，但資料來源不同（批次 IN 查詢 vs 逐品項查詢）。
 * 這裡用同一組資料分別跑兩條路徑，比對結果的字串（含小數位與錯誤碼），
 * 並確認批次路徑的查詢次數與品項數無關、各商品的規格與配料不會互相滲透。
 */
@ExtendWith(MockitoExtension.class)
class OrderPricingTest {

    private static final long BRAND = 7L;
    private static final long REGION = 3L;
    private static final long CATEGORY = 5L;
    private static final long PRODUCT = 12L;
    private static final long OTHER = 13L;

    @Mock private BrandToppingSettingRepository brandToppingSettingRepository;
    @Mock private BrandRegionCategoryPricingRepository brandRegionCategoryPricingRepository;
    @Mock private ProductSpecRelationRepository productSpecRelationRepository;
    @Mock private ProductToppingRuleRepository productToppingRuleRepository;
    @InjectMocks private PricingService pricingService;

    @Test
    void batchAndPerItemPricingAgreeForEveryCombination() {
        ProductTemplate product = product(PRODUCT, CATEGORY, "35.00");
        Store store = storeInRegion();
        List<ProductSpecRelation> sizes = List.of(
                sizeRelation(PRODUCT, 1L, "中杯", "35.00"),
                sizeRelation(PRODUCT, 2L, "大杯", "50.00"));
        List<ProductToppingRule> rules = List.of(
                rule(PRODUCT, masterTopping(81L, "珍珠", "10.00", null)),
                rule(PRODUCT, customTopping(82L, "芋圓", "15.00")));
        BrandRegionCategoryPricing offset = regionRow(CATEGORY, "5.00");

        lenient().when(productSpecRelationRepository.findSizePricingsByProductId(PRODUCT)).thenReturn(sizes);
        lenient().when(productSpecRelationRepository.findSizePricingsByProductIdIn(anyList())).thenReturn(sizes);
        lenient().when(productToppingRuleRepository.findByIdProductId(PRODUCT)).thenReturn(rules);
        lenient().when(productToppingRuleRepository.findByIdProductIdIn(anyList())).thenReturn(rules);
        lenient().when(brandRegionCategoryPricingRepository
                .findByBrandIdAndRegionIdAndCategoryId(BRAND, REGION, CATEGORY)).thenReturn(Optional.of(offset));
        lenient().when(brandRegionCategoryPricingRepository.findByBrandIdAndRegionId(BRAND, REGION))
                .thenReturn(List.of(offset));

        PricingService.OrderPricing order = pricingService.forOrder(store, List.of(product));

        List<String> sizeChoices = Arrays.<String>asList(null, "", " 大杯 ", "中杯", "大杯", "特大杯");
        List<List<String>> toppingChoices = List.of(
                List.<String>of(),
                List.of("珍珠"),
                List.of("芋圓"),
                List.of("珍珠", "芋圓"),
                List.of("珍珠", "珍珠"),
                List.of("布丁"),
                Arrays.<String>asList((String) null));

        for (String size : sizeChoices) {
            assertEquals(outcome(() -> pricingService.resolveSizeName(product, size)),
                    outcome(() -> order.resolveSizeName(product, size)), "resolveSizeName size=" + size);
            assertEquals(outcome(() -> pricingService.unitPrice(store, product, size)),
                    outcome(() -> order.unitPrice(product, size)), "unitPrice size=" + size);
            for (List<String> names : toppingChoices) {
                assertEquals(outcome(() -> pricingService.itemPrice(store, product, size, names)),
                        outcome(() -> order.itemPrice(product, size, names)),
                        "itemPrice size=" + size + " names=" + names);
            }
        }
        for (List<String> names : toppingChoices) {
            assertEquals(outcome(() -> pricingService.resolveToppings(product, names)),
                    outcome(() -> order.resolveToppings(product, names)), "resolveToppings names=" + names);
        }
    }

    @Test
    void batchKeepsEachProductsSizesAndToppingsSeparate() {
        ProductTemplate a = product(PRODUCT, null, "35.00");
        ProductTemplate b = product(OTHER, null, "30.00");
        lenient().when(productSpecRelationRepository.findSizePricingsByProductIdIn(anyList())).thenReturn(List.of(
                sizeRelation(PRODUCT, 1L, "大杯", "50.00"),
                sizeRelation(OTHER, 2L, "中杯", "30.00")));
        lenient().when(productToppingRuleRepository.findByIdProductIdIn(anyList())).thenReturn(List.of(
                rule(PRODUCT, masterTopping(81L, "珍珠", "10.00", null)),
                rule(OTHER, masterTopping(83L, "椰果", "12.00", null))));

        PricingService.OrderPricing order = pricingService.forOrder(storeWithoutRegion(), List.of(a, b));

        assertEquals("大杯", order.resolveSizeName(a, ""));
        assertEquals("中杯", order.resolveSizeName(b, "大杯"));
        assertEquals(new BigDecimal("30.00"), order.unitPrice(b, "中杯"));
        assertEquals(List.of(new PricingService.ToppingPrice("珍珠", new BigDecimal("10.00"))),
                order.resolveToppings(a, List.of("珍珠")));
        assertEquals(List.of(new PricingService.ToppingPrice("椰果", new BigDecimal("12.00"))),
                order.resolveToppings(b, List.of("椰果")));
        CustomException rejected = assertThrows(CustomException.class,
                () -> order.resolveToppings(a, List.of("椰果")));
        assertEquals("此商品未供應所選配料", rejected.getMsg());
    }

    @Test
    void regionOffsetComesFromTheStoresRegionPerCategoryAndMissingOrNullIsZero() {
        lenient().when(brandRegionCategoryPricingRepository.findByBrandIdAndRegionId(BRAND, REGION))
                .thenReturn(List.of(regionRow(5L, "5.00"), regionRow(6L, null)));
        ProductTemplate inCategory5 = product(PRODUCT, 5L, "35.00");
        ProductTemplate inCategory6 = product(OTHER, 6L, "35.00");
        ProductTemplate inCategory9 = product(14L, 9L, "35.00");
        ProductTemplate noCategory = product(15L, null, "35.00");

        PricingService.OrderPricing order = pricingService.forOrder(storeInRegion(),
                List.of(inCategory5, inCategory6, inCategory9, noCategory));

        assertEquals(new BigDecimal("40.00"), order.unitPrice(inCategory5, null));
        assertEquals(new BigDecimal("35.00"), order.unitPrice(inCategory6, null));
        assertEquals(new BigDecimal("35.00"), order.unitPrice(inCategory9, null));
        assertEquals(new BigDecimal("35.00"), order.unitPrice(noCategory, null));
    }

    @Test
    void storeWithoutRegionSkipsTheRegionQueryAndAddsNothing() {
        ProductTemplate product = product(PRODUCT, CATEGORY, "35.00");

        PricingService.OrderPricing order = pricingService.forOrder(storeWithoutRegion(), List.of(product));

        assertEquals(new BigDecimal("35.00"), order.unitPrice(product, null));
        verify(brandRegionCategoryPricingRepository, never()).findByBrandIdAndRegionId(anyLong(), anyLong());
    }

    @Test
    void emptyProductListRunsNoProductQueries() {
        pricingService.forOrder(storeInRegion(), List.of());

        verify(productSpecRelationRepository, never()).findSizePricingsByProductIdIn(anyList());
        verify(productToppingRuleRepository, never()).findByIdProductIdIn(anyList());
    }

    @Test
    void batchQueriesRunOncePerOrderNotPerItem() {
        ProductTemplate product = product(PRODUCT, CATEGORY, "35.00");
        PricingService.OrderPricing order = pricingService.forOrder(storeInRegion(), List.of(product));

        for (int i = 0; i < 10; i++) {
            order.resolveSizeName(product, "大杯");
            order.itemPrice(product, "大杯", List.of());
        }

        verify(productSpecRelationRepository, times(1)).findSizePricingsByProductIdIn(anyList());
        verify(productToppingRuleRepository, times(1)).findByIdProductIdIn(anyList());
        verify(brandRegionCategoryPricingRepository, times(1)).findByBrandIdAndRegionId(anyLong(), anyLong());
        verify(productSpecRelationRepository, never()).findSizePricingsByProductId(anyLong());
        verify(productToppingRuleRepository, never()).findByIdProductId(anyLong());
        verify(brandRegionCategoryPricingRepository, never())
                .findByBrandIdAndRegionIdAndCategoryId(anyLong(), anyLong(), anyLong());
    }

    @Test
    void batchRejectsWhatPerItemPricingRejects() {
        ProductTemplate limited = product(PRODUCT, CATEGORY, "35.00");
        limited.setMaxToppings(1);
        ProductTemplate unbranded = product(OTHER, CATEGORY, "35.00");
        unbranded.setBrand(null);
        lenient().when(productToppingRuleRepository.findByIdProductId(PRODUCT)).thenReturn(List.of(
                rule(PRODUCT, masterTopping(81L, "珍珠", "10.00", null)),
                rule(PRODUCT, customTopping(82L, "芋圓", "15.00"))));
        lenient().when(productToppingRuleRepository.findByIdProductIdIn(anyList())).thenReturn(List.of(
                rule(PRODUCT, masterTopping(81L, "珍珠", "10.00", null)),
                rule(PRODUCT, customTopping(82L, "芋圓", "15.00"))));

        PricingService.OrderPricing order = pricingService.forOrder(storeInRegion(), List.of(limited, unbranded));
        List<String> names = List.of("珍珠", "芋圓");

        assertEquals("ERR 400 該商品最多只能選擇 1 種配料", outcome(() -> order.resolveToppings(limited, names)));
        assertEquals(outcome(() -> pricingService.resolveToppings(limited, names)),
                outcome(() -> order.resolveToppings(limited, names)));
        assertEquals("ERR 400 商品沒有有效的品牌配料設定",
                outcome(() -> order.resolveToppings(unbranded, List.of("珍珠"))));
        assertEquals(outcome(() -> pricingService.resolveToppings(unbranded, List.of("珍珠"))),
                outcome(() -> order.resolveToppings(unbranded, List.of("珍珠"))));
    }

    /** 結果字串（含小數位與錯誤碼），兩條路徑必須逐字相同 */
    private static String outcome(Supplier<?> call) {
        try {
            return String.valueOf(call.get());
        } catch (CustomException e) {
            return "ERR " + e.getCode() + " " + e.getMsg();
        }
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

    private static ProductTemplate product(long id, Long categoryId, String basePrice) {
        ProductTemplate product = new ProductTemplate();
        product.setId(id);
        product.setBrand(brand(BRAND));
        product.setBasePrice(new BigDecimal(basePrice));
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

    /** 容量列：規格類型為 SIZE，屬於 productId；批次查詢依 productId 分組，因此必須設定 */
    private static ProductSpecRelation sizeRelation(long productId, long brandSpecId, String name, String price) {
        ProductSpecRelationId id = new ProductSpecRelationId();
        id.setProductId(productId);
        id.setBrandSpecId(brandSpecId);
        BrandSpecSetting spec = new BrandSpecSetting();
        spec.setSpecType("SIZE");
        spec.setCustomName(name);
        ProductSpecRelation relation = new ProductSpecRelation();
        relation.setId(id);
        relation.setBrandSpec(spec);
        relation.setPrice(new BigDecimal(price));
        return relation;
    }

    /** 配料規則：屬於 productId；批次查詢依 productId 分組，因此必須設定 */
    private static ProductToppingRule rule(long productId, BrandToppingSetting setting) {
        ProductToppingRuleId id = new ProductToppingRuleId();
        id.setProductId(productId);
        id.setBrandToppingId(setting.getId());
        ProductToppingRule rule = new ProductToppingRule();
        rule.setId(id);
        rule.setBrandTopping(setting);
        return rule;
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

    /** 品牌自訂配料：master 為 null */
    private static BrandToppingSetting customTopping(long id, String name, String brandPrice) {
        BrandToppingSetting setting = topping(id, brandPrice);
        setting.setCustomName(name);
        return setting;
    }

    private static BrandToppingSetting topping(long id, String brandPrice) {
        BrandToppingSetting setting = new BrandToppingSetting();
        setting.setId(id);
        setting.setBrand(brand(BRAND));
        setting.setIsEnabled(true);
        setting.setBrandPrice(brandPrice == null ? null : new BigDecimal(brandPrice));
        return setting;
    }

    private static BrandRegionCategoryPricing regionRow(long categoryId, String offset) {
        BrandRegionCategoryPricing row = new BrandRegionCategoryPricing();
        row.setCategory(category(categoryId));
        row.setPriceOffset(offset == null ? null : new BigDecimal(offset));
        return row;
    }
}
