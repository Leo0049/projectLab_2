package com.example.demo.service;

import com.example.demo.common.BrandApproval;
import com.example.demo.dto.OrderItemRequest;
import com.example.demo.dto.PlaceOrderRequest;
import com.example.demo.entity.Brand;
import com.example.demo.entity.BrandRegionCategoryPricing;
import com.example.demo.entity.BrandSpecSetting;
import com.example.demo.entity.BrandToppingSetting;
import com.example.demo.entity.MenuCategory;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.OrderItemTopping;
import com.example.demo.entity.OrderItemToppingId;
import com.example.demo.entity.ProductSpecRelation;
import com.example.demo.entity.ProductSpecRelationId;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.ProductToppingRule;
import com.example.demo.entity.ProductToppingRuleId;
import com.example.demo.entity.Region;
import com.example.demo.entity.Store;
import com.example.demo.entity.ToppingMaster;
import com.example.demo.entity.User;
import com.example.demo.repository.BrandRegionCategoryPricingRepository;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.BrandSpecSettingRepository;
import com.example.demo.repository.BrandToppingSettingRepository;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.MenuCategoryRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderItemToppingRepository;
import com.example.demo.repository.ProductSpecRelationRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.ProductToppingRuleRepository;
import com.example.demo.repository.RegionRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.ToppingMasterRepository;
import com.example.demo.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建單定價的 N+1 守衛（stage 31）。
 *
 * <p>整張訂單的定價資料由 {@code PricingService.forOrder} 一次撈回，逐品項計算不再查資料庫。
 * 這裡以 Hibernate 的 JPQL 執行次數（{@code getQueryExecutionCount()}）量測：同一組商品下，
 * 品項從 3 行增加到 6 行，次數必須相等。
 *
 * <p>為什麼用 JPQL 次數而不是 JDBC 陳述式數：INSERT 會隨品項數成長，JDBC 陳述式數分不出「寫入多」與「查詢多」。
 * 代價是 JPQL 次數看不到 {@code em.find} 與 merge 造成的單列讀取（例如 {@code orderItemToppingRepository.saveAll}
 * 對配料列做的 merge），那些不在這個守衛的範圍內。
 *
 * <p>資料內容也同時斷言：總額、每行的成交價，以及配料列的名稱（個人訂單另外斷言價格快照）。
 * 配料包含一個品牌自訂配料（master 為 null），用來守住 {@code findByIdProductIdIn} 必須用
 * LEFT JOIN FETCH 的規則：改成 JOIN FETCH 會把它丟掉，下單就會回 400。
 *
 * <p>兩個入口都量：{@code placeOrder}（個人訂單，伺服器寫入配料價格快照）與 {@code createOrder}（結帳，
 * 與 OrderController.createOrderFromRequest 相同的組裝方式）。付款方式一律用現金，避免錢包扣款的查詢干擾計數。
 *
 * <p>期望值（分店在地區 R，分類甲有加價 5.00，分類乙沒有）：
 * 商品甲底價 35.00，中杯 35.00、大杯 50.00；商品乙、丙底價 35.00；珍珠（平台主檔）預設價 10.00；芋圓（品牌自訂）15.00。
 * 3 行：甲大杯＋珍珠＋芋圓 = 55 + 25 = 80；乙＋芋圓 = 40 + 15 = 55；丙 = 35，合計 170.00。
 * 6 行再加：甲中杯＋珍珠 = 40 + 10 = 50；乙 = 40；丙 = 35，合計 295.00。
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class OrderPricingQueryCountGuardTest {

    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private OrderService orderService;
    @Autowired private BrandRepository brandRepository;
    @Autowired private RegionRepository regionRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MenuCategoryRepository menuCategoryRepository;
    @Autowired private ProductTemplateRepository productTemplateRepository;
    @Autowired private BrandSpecSettingRepository brandSpecSettingRepository;
    @Autowired private ProductSpecRelationRepository productSpecRelationRepository;
    @Autowired private ToppingMasterRepository toppingMasterRepository;
    @Autowired private BrandToppingSettingRepository brandToppingSettingRepository;
    @Autowired private ProductToppingRuleRepository productToppingRuleRepository;
    @Autowired private BrandRegionCategoryPricingRepository brandRegionCategoryPricingRepository;
    @Autowired private GroupOrderRepository groupOrderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OrderItemToppingRepository orderItemToppingRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /** 本測試建立的資料列；依外鍵相反的順序清除（訂單 → 規則與規格關聯 → 價格列 → 商品 → 分類 → 配料 → 分店 → 顧客 → 地區 → 品牌）。 */
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Brand> brands = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private final List<Store> stores = new ArrayList<>();
    private final List<MenuCategory> categories = new ArrayList<>();
    private final List<BrandRegionCategoryPricing> regionRows = new ArrayList<>();
    private final List<BrandSpecSetting> specSettings = new ArrayList<>();
    private final List<ProductTemplate> products = new ArrayList<>();
    private final List<ProductSpecRelation> specRelations = new ArrayList<>();
    private final List<ToppingMaster> toppingMasters = new ArrayList<>();
    private final List<BrandToppingSetting> toppingSettings = new ArrayList<>();
    private final List<ProductToppingRule> toppingRules = new ArrayList<>();

    /** 同一呼叫量測的次數，取最小值（見 measureLeast）。 */
    private static final int REPEATS = 3;

    private record Measured<T>(long queries, T result) {}

    /** 一行訂單：商品、容量（可為 null）與配料名稱 */
    private record Line(ProductTemplate product, String size, List<String> toppings) {}

    /** 一組分店、顧客與商品。p1 有容量與珍珠、芋圓；p2 只有芋圓；p3 位於沒有加價的分類。 */
    private record Fixture(Store store, User user, ProductTemplate p1, ProductTemplate p2, ProductTemplate p3,
            String pearl, String taro) {}

    @AfterEach
    void removeCreatedRows() {
        for (Long orderId : orderIds) {
            List<OrderItem> items = orderItemRepository.findByGroupOrderId(orderId);
            List<Long> itemIds = items.stream().map(OrderItem::getId).toList();
            if (!itemIds.isEmpty()) {
                orderItemToppingRepository.deleteAll(orderItemToppingRepository.findByOrderItemIdIn(itemIds));
            }
            orderItemRepository.deleteAll(items);
            groupOrderRepository.deleteById(orderId);
        }
        productToppingRuleRepository.deleteAll(toppingRules);
        productSpecRelationRepository.deleteAll(specRelations);
        brandRegionCategoryPricingRepository.deleteAll(regionRows);
        productTemplateRepository.deleteAll(products);
        menuCategoryRepository.deleteAll(categories);
        brandToppingSettingRepository.deleteAll(toppingSettings);
        toppingMasterRepository.deleteAll(toppingMasters);
        brandSpecSettingRepository.deleteAll(specSettings);
        storeRepository.deleteAll(stores);
        userRepository.deleteAll(users);
        regionRepository.deleteAll(regions);
        brandRepository.deleteAll(brands);
    }

    @Test
    @DisplayName("個人訂單（placeOrder）：JPQL 次數不隨品項數成長；總額、行售價與配料價格快照都由伺服器算出")
    void placeOrderQueryCountDoesNotGrowWithItems() {
        Fixture f = newFixture();
        place(f, threeLines(f));   // 預熱：第一次執行不計入量測
        Measured<Map<String, Object>> three = measureLeast(() -> place(f, threeLines(f)));
        Measured<Map<String, Object>> six = measureLeast(() -> place(f, sixLines(f)));

        assertTrue(three.queries() > 0, "統計未啟用時次數為 0，守衛就形同虛設");
        assertEquals(three.queries(), six.queries(),
                "品項從 3 行增加到 6 行（商品集合不變），JPQL 次數不應改變；逐品項各查一次就是 N+1");

        Long threeId = (Long) three.result().get("orderId");
        Long sixId = (Long) six.result().get("orderId");
        assertMoney("170.00", three.result().get("totalAmount"));
        assertMoney("295.00", six.result().get("totalAmount"));
        assertFinalPrices(threeId, "35.00", "55.00", "80.00");
        assertFinalPrices(sixId, "35.00", "35.00", "40.00", "50.00", "55.00", "80.00");
        assertToppingRows(f, threeId, 1, 2);
        assertToppingRows(f, sixId, 2, 2);

        for (OrderItemTopping row : toppingRowsOf(sixId)) {
            BigDecimal expected = f.taro().equals(row.getId().getToppingNameSnapshot())
                    ? new BigDecimal("15.00") : new BigDecimal("10.00");
            assertEquals(0, expected.compareTo(row.getToppingPriceSnapshot()),
                    "配料價格快照必須是伺服器算出的價格：" + row.getId().getToppingNameSnapshot());
        }
    }

    @Test
    @DisplayName("結帳（createOrder）：JPQL 次數不隨品項數成長；用戶端送來的 1 元不採信，總額與行售價由伺服器重算")
    void checkoutQueryCountDoesNotGrowWithItems() {
        Fixture f = newFixture();
        checkout(f, threeLines(f));   // 預熱：第一次執行不計入量測
        Measured<Long> three = measureLeast(() -> checkout(f, threeLines(f)));
        Measured<Long> six = measureLeast(() -> checkout(f, sixLines(f)));

        assertTrue(three.queries() > 0, "統計未啟用時次數為 0，守衛就形同虛設");
        assertEquals(three.queries(), six.queries(),
                "品項從 3 行增加到 6 行（商品集合不變），JPQL 次數不應改變；逐品項各查一次就是 N+1");

        assertMoney("170.00", totalOf(three.result()));
        assertMoney("295.00", totalOf(six.result()));
        assertFinalPrices(three.result(), "35.00", "55.00", "80.00");
        assertFinalPrices(six.result(), "35.00", "35.00", "40.00", "50.00", "55.00", "80.00");
        assertToppingRows(f, three.result(), 1, 2);
        assertToppingRows(f, six.result(), 2, 2);
    }

    // ── 量測與斷言 ───────────────────────────────────────────

    /** 執行一次呼叫，回傳其間的 JPQL 執行次數與結果。 */
    private <T> Measured<T> measure(Supplier<T> call) {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        T result = call.get();
        return new Measured<>(stats.getQueryExecutionCount(), result);
    }

    /**
     * 同一呼叫量測 {@link #REPEATS} 次，取最小的次數；結果是最後一次呼叫的結果（內容斷言用它）。
     *
     * <p>{@code @EnableScheduling} 會讓 OrderAutoRejectJob 每 30 秒執行一次 JPQL 查詢。若它剛好落在量測窗口內，
     * 次數會多 1，單次比對會偶發失敗。背景工作只會讓次數變大、不會變小，因此取最小值能排除這類干擾；
     * 真正的 N+1 會讓每一次量測都隨品項增加，2N 的最小值仍會比 N 大。
     */
    private <T> Measured<T> measureLeast(Supplier<T> call) {
        long least = Long.MAX_VALUE;
        T last = null;
        for (int i = 0; i < REPEATS; i++) {
            Measured<T> sample = measure(call);
            least = Math.min(least, sample.queries());
            last = sample.result();
        }
        return new Measured<>(least, last);
    }

    private static void assertMoney(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual),
                "應為 " + expected + "，實際 " + actual);
    }

    /** 品項的成交價（排序後逐一比對）。 */
    private void assertFinalPrices(Long orderId, String... expected) {
        List<BigDecimal> actual = orderItemRepository.findByGroupOrderId(orderId).stream()
                .map(OrderItem::getFinalPrice)
                .sorted()
                .toList();
        List<BigDecimal> wanted = Arrays.stream(expected).map(BigDecimal::new).sorted().toList();
        assertEquals(wanted.size(), actual.size(), "品項數");
        for (int i = 0; i < wanted.size(); i++) {
            assertEquals(0, wanted.get(i).compareTo(actual.get(i)), "排序後第 " + i + " 行的成交價");
        }
    }

    /** 配料列的數量：珍珠（平台主檔）與芋圓（品牌自訂，master 為 null）各有幾列。 */
    private void assertToppingRows(Fixture f, Long orderId, long pearlRows, long taroRows) {
        List<OrderItemTopping> rows = toppingRowsOf(orderId);
        assertEquals(pearlRows, countNamed(rows, f.pearl()), "珍珠的配料列數");
        assertEquals(taroRows, countNamed(rows, f.taro()), "品牌自訂的芋圓（master 為 null）的配料列數");
    }

    private List<OrderItemTopping> toppingRowsOf(Long orderId) {
        List<Long> itemIds = orderItemRepository.findByGroupOrderId(orderId).stream()
                .map(OrderItem::getId)
                .toList();
        if (itemIds.isEmpty()) {
            return List.<OrderItemTopping>of();
        }
        return orderItemToppingRepository.findByOrderItemIdIn(itemIds);
    }

    private static long countNamed(List<OrderItemTopping> rows, String name) {
        return rows.stream().filter(row -> name.equals(row.getId().getToppingNameSnapshot())).count();
    }

    private BigDecimal totalOf(Long orderId) {
        return groupOrderRepository.findById(orderId).orElseThrow().getTotalAmount();
    }

    // ── 下單 ──────────────────────────────────────────────────

    /** 個人訂單：呼叫 placeOrder，並記下訂單 id 供清除。 */
    private Map<String, Object> place(Fixture f, List<Line> lines) {
        Map<String, Object> result = orderService.placeOrder(f.user().getId(), placeRequest(f, lines));
        orderIds.add((Long) result.get("orderId"));
        return result;
    }

    private PlaceOrderRequest placeRequest(Fixture f, List<Line> lines) {
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setStoreId(f.store().getId());
        request.setNote("守衛測試");
        List<OrderItemRequest> items = new ArrayList<>();
        for (Line line : lines) {
            OrderItemRequest item = new OrderItemRequest();
            item.setProductId(line.product().getId());
            item.setSizeSnapshot(line.size());
            item.setToppingNames(line.toppings());
            item.setPaymentType("CASH");
            items.add(item);
        }
        request.setItems(items);
        return request;
    }

    /** 結帳：呼叫 createOrder（用戶端總額送 1 元），並記下訂單 id 供清除。 */
    private Long checkout(Fixture f, List<Line> lines) {
        Long orderId = orderService.createOrder(f.user().getId(), f.store().getId(), BigDecimal.ONE, null,
                checkoutItems(f, lines), "CASH", null, "守衛測試");
        orderIds.add(orderId);
        return orderId;
    }

    /**
     * 與 OrderController.createOrderFromRequest 相同的組裝方式：商品只帶 id，配料價格快照寫 0（該處的簡化寫法），
     * 用戶端送來的單價與成交價都是 1，由 repriceItems 覆寫。
     */
    private List<OrderItem> checkoutItems(Fixture f, List<Line> lines) {
        List<OrderItem> items = new ArrayList<>();
        for (Line line : lines) {
            ProductTemplate shell = new ProductTemplate();
            shell.setId(line.product().getId());
            User owner = new User();
            owner.setId(f.user().getId());

            OrderItem item = new OrderItem();
            item.setProduct(shell);
            item.setUser(owner);
            item.setProductNameSnapshot("用戶端偽造的品名");
            item.setUnitPriceSnapshot(BigDecimal.ONE);
            item.setFinalPrice(BigDecimal.ONE);
            item.setSizeSnapshot(line.size());

            List<OrderItemTopping> toppings = new ArrayList<>();
            for (String name : line.toppings()) {
                OrderItemToppingId id = new OrderItemToppingId();
                id.setToppingNameSnapshot(name);
                OrderItemTopping topping = new OrderItemTopping();
                topping.setId(id);
                topping.setOrderItem(item);
                topping.setToppingPriceSnapshot(BigDecimal.ZERO);
                toppings.add(topping);
            }
            item.setToppings(toppings);
            items.add(item);
        }
        return items;
    }

    // ── 訂單行 ────────────────────────────────────────────────

    private static List<Line> threeLines(Fixture f) {
        return List.of(
                new Line(f.p1(), "大杯", List.of(f.pearl(), f.taro())),
                new Line(f.p2(), null, List.of(f.taro())),
                new Line(f.p3(), null, List.of()));
    }

    /** 同一組商品再加三行（容量與配料不同），商品集合不變。 */
    private static List<Line> sixLines(Fixture f) {
        List<Line> lines = new ArrayList<>(threeLines(f));
        lines.add(new Line(f.p1(), "中杯", List.of(f.pearl())));
        lines.add(new Line(f.p2(), null, List.of()));
        lines.add(new Line(f.p3(), null, List.of()));
        return lines;
    }

    // ── 夾具 ──────────────────────────────────────────────────

    private Fixture newFixture() {
        String tag = UUID.randomUUID().toString().substring(0, 8);

        Brand brand = new Brand();
        brand.setName("守衛測試品牌-" + tag);
        brand.setAccount("guard-pricing-brand-" + tag);
        brand.setPasswordHash(passwordEncoder.encode("pw"));
        brand.setRole("BRAND");
        brand.setApprovalStatus(BrandApproval.APPROVED);
        brand = brandRepository.save(brand);
        brands.add(brand);

        Region region = new Region();
        region.setName("守衛地區-" + tag);
        region = regionRepository.save(region);
        regions.add(region);

        Store store = new Store();
        store.setBrand(brand);
        store.setRegion(region);
        store.setStoreName("守衛測試分店-" + tag);
        store.setAccount("guard-pricing-store-" + tag);
        store.setPasswordHash(passwordEncoder.encode("pw"));
        store.setRole("STORE");
        store.setStatus("active");
        store = storeRepository.save(store);
        stores.add(store);

        User user = new User();
        user.setPhone("09" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)));
        user.setName("守衛測試顧客-" + tag);
        user.setRole("CUSTOMER");
        user = userRepository.save(user);
        users.add(user);

        MenuCategory withOffset = newCategory(brand, "守衛分類甲-" + tag);
        MenuCategory withoutOffset = newCategory(brand, "守衛分類乙-" + tag);

        BrandRegionCategoryPricing offset = new BrandRegionCategoryPricing();
        offset.setBrand(brand);
        offset.setRegion(region);
        offset.setCategory(withOffset);
        offset.setPriceOffset(new BigDecimal("5.00"));
        regionRows.add(brandRegionCategoryPricingRepository.save(offset));

        ProductTemplate p1 = newProduct(brand, withOffset, "守衛測試品項甲-" + tag);
        ProductTemplate p2 = newProduct(brand, withOffset, "守衛測試品項乙-" + tag);
        ProductTemplate p3 = newProduct(brand, withoutOffset, "守衛測試品項丙-" + tag);

        BrandSpecSetting small = newSizeSpec(brand, "中杯");
        BrandSpecSetting large = newSizeSpec(brand, "大杯");
        newSizeRelation(p1, small, "35.00");
        newSizeRelation(p1, large, "50.00");

        // 平台主檔配料：價格沿用主檔的預設價
        ToppingMaster pearlMaster = new ToppingMaster();
        pearlMaster.setName("守衛珍珠-" + tag);
        pearlMaster.setDefaultPrice(new BigDecimal("10.00"));
        pearlMaster = toppingMasterRepository.save(pearlMaster);
        toppingMasters.add(pearlMaster);

        BrandToppingSetting pearl = new BrandToppingSetting();
        pearl.setBrand(brand);
        pearl.setMasterTopping(pearlMaster);
        pearl.setIsEnabled(true);
        pearl = brandToppingSettingRepository.save(pearl);
        toppingSettings.add(pearl);

        // 品牌自訂配料：master 為 null，價格由品牌設定
        BrandToppingSetting taro = new BrandToppingSetting();
        taro.setBrand(brand);
        taro.setCustomName("守衛芋圓-" + tag);
        taro.setBrandPrice(new BigDecimal("15.00"));
        taro.setIsEnabled(true);
        taro = brandToppingSettingRepository.save(taro);
        toppingSettings.add(taro);

        newToppingRule(p1, pearl);
        newToppingRule(p1, taro);
        newToppingRule(p2, taro);

        return new Fixture(store, user, p1, p2, p3, pearlMaster.getName(), taro.getCustomName());
    }

    private MenuCategory newCategory(Brand brand, String name) {
        MenuCategory category = new MenuCategory();
        category.setBrand(brand);
        category.setName(name);
        category.setSortOrder(0);
        MenuCategory saved = menuCategoryRepository.save(category);
        categories.add(saved);
        return saved;
    }

    private ProductTemplate newProduct(Brand brand, MenuCategory category, String name) {
        ProductTemplate product = new ProductTemplate();
        product.setBrand(brand);
        product.setCategory(category);
        product.setName(name);
        product.setBasePrice(new BigDecimal("35.00"));
        product.setSortOrder(0);
        ProductTemplate saved = productTemplateRepository.save(product);
        products.add(saved);
        return saved;
    }

    private BrandSpecSetting newSizeSpec(Brand brand, String name) {
        BrandSpecSetting spec = new BrandSpecSetting();
        spec.setBrand(brand);
        spec.setSpecType("SIZE");
        spec.setCustomName(name);
        spec.setIsEnabled(true);
        spec.setSortOrder(0);
        BrandSpecSetting saved = brandSpecSettingRepository.save(spec);
        specSettings.add(saved);
        return saved;
    }

    private void newSizeRelation(ProductTemplate product, BrandSpecSetting spec, String price) {
        ProductSpecRelationId id = new ProductSpecRelationId();
        id.setProductId(product.getId());
        id.setBrandSpecId(spec.getId());
        ProductSpecRelation relation = new ProductSpecRelation();
        relation.setId(id);
        relation.setProduct(product);
        relation.setBrandSpec(spec);
        relation.setPrice(new BigDecimal(price));
        specRelations.add(productSpecRelationRepository.save(relation));
    }

    private void newToppingRule(ProductTemplate product, BrandToppingSetting setting) {
        ProductToppingRuleId id = new ProductToppingRuleId();
        id.setProductId(product.getId());
        id.setBrandToppingId(setting.getId());
        ProductToppingRule rule = new ProductToppingRule();
        rule.setId(id);
        rule.setProduct(product);
        rule.setBrandTopping(setting);
        toppingRules.add(productToppingRuleRepository.save(rule));
    }
}
