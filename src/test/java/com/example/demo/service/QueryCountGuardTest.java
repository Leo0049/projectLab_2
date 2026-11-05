package com.example.demo.service;

import com.example.demo.common.BrandApproval;
import com.example.demo.controller.OrderController;
import com.example.demo.controller.UserFavoriteController;
import com.example.demo.dto.BrandSummary;
import com.example.demo.dto.GroupOrderDTO;
import com.example.demo.entity.Brand;
import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.MenuCategory;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.OrderRating;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Store;
import com.example.demo.entity.StoreProductStatus;
import com.example.demo.entity.StoreProductStatusId;
import com.example.demo.entity.User;
import com.example.demo.entity.UserFavorite;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.MenuCategoryRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRatingRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.StoreProductStatusRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserFavoriteRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N+1 守衛（stage 23，IMPROVEMENT_ASSESSMENT 第 8 項；stage 27、28 擴充）。
 *
 * <p>以 Hibernate 統計量測 JDBC 陳述式數：同一個端點在資料量少與多時，陳述式數必須相等。
 * 守的是「查詢數不隨列數成長」，不是絕對次數，因此新增欄位或關聯時不需要改數字。
 *
 * <p>生產設定的 {@code default_batch_fetch_size: 50} 會把懶載入的關聯依 50 筆一批載入，
 * 所以守衛量的是生產實際的陳述式數：批次載入不會被誤判，逐列各查一次的寫法一定會被抓到。
 *
 * <p>目前涵蓋：分店菜單、公開品牌列表、附近店家（定位與未定位）、搜尋（距離與名稱）、
 * 揪團詳情、訂單列表、收藏清單、品牌口碑（低分分店的評分分布）。新增讀取端點時，請在這裡加入對應案例。
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class QueryCountGuardTest {

    /** 台北 101 附近。所有守衛分店都建在這個座標上，距離為 0，排序與數量都可預期。 */
    private static final double LAT = 25.0330;
    private static final double LNG = 121.5654;

    /** 搜尋關鍵字。只會命中本測試建立的分店，名稱都含這段字，結束後全部清除。 */
    private static final String KEYWORD = "守衛測試分店";

    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private MenuService menuService;
    @Autowired private PublicService publicService;
    @Autowired private GroupOrderService groupOrderService;
    @Autowired private OrderController orderController;
    @Autowired private UserFavoriteController userFavoriteController;
    @Autowired private BrandRepository brandRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuCategoryRepository menuCategoryRepository;
    @Autowired private ProductTemplateRepository productTemplateRepository;
    @Autowired private StoreProductStatusRepository storeProductStatusRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupOrderRepository groupOrderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private UserFavoriteRepository userFavoriteRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BrandService brandService;
    @Autowired private OrderRatingRepository orderRatingRepository;

    /** 本測試建立的資料列，結束後依外鍵相反的順序清除（評分 → 收藏與品項 → 訂單 → 狀態列 → 商品 → 分類 → 分店 → 顧客 → 品牌）。 */
    private final List<UserFavorite> favorites = new ArrayList<>();
    private final List<OrderItem> orderItems = new ArrayList<>();
    private final List<GroupOrder> groupOrders = new ArrayList<>();
    private final List<StoreProductStatus> statuses = new ArrayList<>();
    private final List<ProductTemplate> products = new ArrayList<>();
    private final List<MenuCategory> categories = new ArrayList<>();
    private final List<Store> stores = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private final List<Brand> brands = new ArrayList<>();
    /** 品牌口碑案例建立的評分，以及每間評分分店實際的評分筆數（用來核對評分分布的加總）。 */
    private final List<OrderRating> ratings = new ArrayList<>();
    private final Map<Long, Integer> ratingCountByStore = new HashMap<>();

    private record Measured<T>(long statements, T result) {}

    @AfterEach
    void removeCreatedRows() {
        orderRatingRepository.deleteAll(ratings);
        userFavoriteRepository.deleteAll(favorites);
        orderItemRepository.deleteAll(orderItems);
        groupOrderRepository.deleteAll(groupOrders);
        storeProductStatusRepository.deleteAll(statuses);
        productTemplateRepository.deleteAll(products);
        menuCategoryRepository.deleteAll(categories);
        storeRepository.deleteAll(stores);
        userRepository.deleteAll(users);
        brandRepository.deleteAll(brands);
        favorites.clear();
        orderItems.clear();
        groupOrders.clear();
        statuses.clear();
        products.clear();
        categories.clear();
        stores.clear();
        users.clear();
        brands.clear();
        ratings.clear();
        ratingCountByStore.clear();
    }

    @Test
    @DisplayName("分店菜單：SQL 次數不隨商品數成長，售罄旗標與售罄列一致")
    void menuQueryCountDoesNotGrowWithProducts() {
        Brand brand = newBrand();
        Store store = newStore(brand);
        MenuCategory category = newCategory(brand);
        newProducts(brand, category, 2);
        Measured<List<Map<String, Object>>> few = measure(() -> menuService.getStoreMenu(store.getId()));

        List<ProductTemplate> more = newProducts(brand, category, 10);
        for (int i = 0; i < 3; i++) {
            newStatus(store, more.get(i), false);
        }
        Measured<List<Map<String, Object>>> many = measure(() -> menuService.getStoreMenu(store.getId()));

        assertEquals(few.statements(), many.statements(),
                "商品從 2 個增加到 12 個，分店菜單的 SQL 次數不應改變；逐商品各查一次就是 N+1");
        assertEquals(12, countItems(many.result()), "量到的必須是真的有商品的菜單");
        assertEquals(3, countSoldOut(many.result()), "售罄旗標必須與 store_product_settings 的列一致");
    }

    @Test
    @DisplayName("公開品牌列表：SQL 次數不隨品牌數成長")
    void publicBrandListQueryCountDoesNotGrowWithBrands() {
        Measured<List<BrandSummary>> before = measure(publicService::getBrands);
        for (int i = 0; i < 5; i++) {
            newBrand();
        }
        Measured<List<BrandSummary>> after = measure(publicService::getBrands);

        assertEquals(before.statements(), after.statements(),
                "新增 5 個品牌後，公開品牌列表的 SQL 次數不應改變");
        assertEquals(before.result().size() + 5, after.result().size(),
                "新增的品牌必須出現在列表中（確認量到的是真實的查詢）");
    }

    @Test
    @DisplayName("附近店家（定位）：SQL 次數不隨分店數成長，回傳的分店包含剛建立的全部分店")
    void nearbyWithLocationQueryCountDoesNotGrowWithStores() {
        Brand brand = newBrand();
        List<Store> created = newStoresAt(brand, 2);
        Measured<Map<String, Object>> few =
                measure(() -> publicService.getNearbyStoresPage(LAT, LNG, null, null, 1, 50, null));

        created.addAll(newStoresAt(brand, 10));
        Measured<Map<String, Object>> many =
                measure(() -> publicService.getNearbyStoresPage(LAT, LNG, null, null, 1, 50, null));

        assertEquals(few.statements(), many.statements(),
                "分店從 2 間增加到 12 間，附近店家（定位）的 SQL 次數不應改變；逐店各查一次就是 N+1");
        assertTrue(storeIdsIn(cards(many.result(), "stores")).containsAll(idsOf(created)),
                "量到的必須包含剛建立的全部分店（確認量到的是真實的查詢）");
    }

    @Test
    @DisplayName("附近店家（未定位，依品牌篩選）：SQL 次數不隨分店數成長，數量與建立的一致")
    void nearbyWithoutLocationQueryCountDoesNotGrowWithStores() {
        Brand brand = newBrand();
        newStoresAt(brand, 2);
        Measured<Map<String, Object>> few =
                measure(() -> publicService.getNearbyStoresPage(null, null, brand.getId(), null, 1, 50, null));

        newStoresAt(brand, 10);
        Measured<Map<String, Object>> many =
                measure(() -> publicService.getNearbyStoresPage(null, null, brand.getId(), null, 1, 50, null));

        assertEquals(few.statements(), many.statements(),
                "分店從 2 間增加到 12 間，附近店家（未定位）的 SQL 次數不應改變");
        assertEquals(12, cards(many.result(), "stores").size(), "量到的必須是該品牌的全部分店");
    }

    @Test
    @DisplayName("搜尋（定位，距離排序）：SQL 次數不隨分店數成長，結果就是剛建立的分店")
    void searchWithLocationQueryCountDoesNotGrowWithStores() {
        Brand brand = newBrand();
        List<Store> created = newStoresAt(brand, 2);
        Measured<Map<String, Object>> few =
                measure(() -> publicService.searchStores(KEYWORD, LAT, LNG, 1, 50, null));

        created.addAll(newStoresAt(brand, 10));
        Measured<Map<String, Object>> many =
                measure(() -> publicService.searchStores(KEYWORD, LAT, LNG, 1, 50, null));

        assertEquals(few.statements(), many.statements(),
                "分店從 2 間增加到 12 間，搜尋（距離）的 SQL 次數不應改變；逐店各查一次就是 N+1");
        assertEquals(idsOf(created), storeIdsIn(cards(many.result(), "stores")),
                "搜尋結果必須恰好是剛建立的分店（關鍵字只命中本測試的資料）");
    }

    @Test
    @DisplayName("搜尋（未定位，名稱路徑）：SQL 次數不隨分店數成長，結果就是剛建立的分店")
    void searchWithoutLocationQueryCountDoesNotGrowWithStores() {
        Brand brand = newBrand();
        List<Store> created = newStoresAt(brand, 2);
        Measured<Map<String, Object>> few =
                measure(() -> publicService.searchStores(KEYWORD, null, null, 1, 50, null));

        created.addAll(newStoresAt(brand, 10));
        Measured<Map<String, Object>> many =
                measure(() -> publicService.searchStores(KEYWORD, null, null, 1, 50, null));

        assertEquals(few.statements(), many.statements(),
                "分店從 2 間增加到 12 間，搜尋（名稱）的 SQL 次數不應改變");
        assertEquals(idsOf(created), storeIdsIn(cards(many.result(), "stores")),
                "搜尋結果必須恰好是剛建立的分店（關鍵字只命中本測試的資料）");
    }

    @Test
    @DisplayName("揪團詳情（團長視角）：SQL 次數不隨品項數成長，品項數與建立的一致")
    void groupOrderDetailQueryCountDoesNotGrowWithItems() {
        Brand brand = newBrand();
        Store store = newStore(brand);
        ProductTemplate product = newProducts(brand, newCategory(brand), 1).get(0);
        User host = newUser();
        GroupOrder order = newGroupOrder(store, host);
        newMemberItems(order, product, 2);
        Measured<GroupOrderDTO> few =
                measure(() -> groupOrderService.getGroupOrderDTOByToken(order.getShareToken(), host.getId()));

        newMemberItems(order, product, 10);
        Measured<GroupOrderDTO> many =
                measure(() -> groupOrderService.getGroupOrderDTOByToken(order.getShareToken(), host.getId()));

        assertEquals(few.statements(), many.statements(),
                "團員從 2 人增加到 12 人，揪團詳情的 SQL 次數不應改變；逐品項各查一次就是 N+1");
        assertEquals(12, many.result().getItems().size(), "量到的必須是團內全部的品項");
    }

    @Test
    @DisplayName("訂單列表（分頁卡片）：SQL 次數不隨訂單數成長，卡片數與建立的一致")
    void orderCardsQueryCountDoesNotGrowWithOrders() {
        Brand brand = newBrand();
        Store store = newStore(brand);
        ProductTemplate product = newProducts(brand, newCategory(brand), 1).get(0);
        User host = newUser();
        for (int i = 0; i < 2; i++) {
            newHostedOrderWithItem(store, host, product);
        }
        Measured<Object> few =
                measure(() -> orderController.getUserOrderCards(host.getId(), 0, 20, null, host.getId()).getBody());

        for (int i = 0; i < 10; i++) {
            newHostedOrderWithItem(store, host, product);
        }
        Measured<Object> many =
                measure(() -> orderController.getUserOrderCards(host.getId(), 0, 20, null, host.getId()).getBody());

        assertEquals(few.statements(), many.statements(),
                "訂單從 2 張增加到 12 張，訂單列表的 SQL 次數不應改變；逐張訂單各查一次就是 N+1");
        assertEquals(12, orderCardsOf(many.result()).size(), "量到的必須是全部的訂單");
    }

    @Test
    @DisplayName("收藏清單：SQL 次數不隨收藏數成長，列出的收藏與建立的一致")
    void favoritesQueryCountDoesNotGrowWithFavorites() {
        Brand brand = newBrand();
        User user = newUser();
        for (int i = 0; i < 2; i++) {
            newFavoriteStore(brand, user);
        }
        Measured<Object> few =
                measure(() -> userFavoriteController.getUserFavorites(user.getId(), user.getId()).getBody());

        for (int i = 0; i < 10; i++) {
            newFavoriteStore(brand, user);
        }
        Measured<Object> many =
                measure(() -> userFavoriteController.getUserFavorites(user.getId(), user.getId()).getBody());

        assertEquals(few.statements(), many.statements(),
                "收藏從 2 間增加到 12 間，收藏清單的 SQL 次數不應改變；逐店各查一次就是 N+1");
        assertEquals(12, ((List<?>) many.result()).size(), "量到的必須是全部的收藏");
    }

    @Test
    @DisplayName("品牌口碑（低分分店的評分分布）：SQL 次數不隨分店數成長，每間低分分店的分布與建立的評分一致")
    void brandReputationQueryCountDoesNotGrowWithStores() {
        Brand brand = newBrand();
        newRatedStores(brand, 2);
        Measured<Map<String, Object>> few = measure(() -> brandService.getBrandReputation(brand.getId()));

        newRatedStores(brand, 10);
        Measured<Map<String, Object>> many = measure(() -> brandService.getBrandReputation(brand.getId()));

        assertEquals(few.statements(), many.statements(),
                "分店從 2 間增加到 12 間，品牌口碑的 SQL 次數不應改變；逐間低分分店各查一次就是 N+1");

        List<?> lowRated = (List<?>) many.result().get("lowRatedStores");
        assertEquals(5, lowRated.size(), "低分分店清單上限為 5 間，量到的必須是排序後取前 5 間");
        for (Object entry : lowRated) {
            Map<?, ?> low = (Map<?, ?>) entry;
            long storeId = ((Number) low.get("storeId")).longValue();
            long expected = ratingCountByStore.get(storeId);
            assertTrue(expected <= 5, "低分分店必須是平均分數最低的 5 間");
            assertEquals(expected, sumOf(low.get("ratingDistribution")),
                    "每間低分分店的評分分布加總，必須等於它實際的評分筆數（確認批次查詢依分店分組）");
        }
        assertEquals(totalRatings(), ((Number) many.result().get("totalReviewCount")).longValue(),
                "全品牌的評分總數必須與建立的評分一致");
    }

    /** 執行一次呼叫，回傳其間的 JDBC 陳述式數與結果。 */
    private <T> Measured<T> measure(Supplier<T> call) {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        T result = call.get();
        return new Measured<>(stats.getPrepareStatementCount(), result);
    }

    private static int countItems(List<Map<String, Object>> menu) {
        int total = 0;
        for (Map<String, Object> category : menu) {
            total += ((List<?>) category.get("items")).size();
        }
        return total;
    }

    private static int countSoldOut(List<Map<String, Object>> menu) {
        int total = 0;
        for (Map<String, Object> category : menu) {
            for (Object item : (List<?>) category.get("items")) {
                if (Boolean.TRUE.equals(((Map<?, ?>) item).get("isOutOfStock"))) {
                    total++;
                }
            }
        }
        return total;
    }

    /** 從分頁結果取出卡片清單（附近店家與搜尋的 "stores"）。 */
    private static List<?> cards(Map<String, Object> page, String key) {
        return (List<?>) page.get(key);
    }

    private static Set<Long> storeIdsIn(List<?> cards) {
        return cards.stream()
                .map(c -> ((Number) ((Map<?, ?>) c).get("storeId")).longValue())
                .collect(Collectors.toSet());
    }

    private static Set<Long> idsOf(List<Store> list) {
        return list.stream().map(Store::getId).collect(Collectors.toSet());
    }

    /** 訂單列表的回應是 {content: [...], ...}。 */
    private static List<?> orderCardsOf(Object body) {
        return (List<?>) ((Map<?, ?>) body).get("content");
    }

    private Brand newBrand() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Brand brand = new Brand();
        brand.setName("守衛測試品牌-" + tag);
        brand.setAccount("guard-brand-" + tag);
        brand.setPasswordHash(passwordEncoder.encode("pw"));
        brand.setRole("BRAND");
        brand.setApprovalStatus(BrandApproval.APPROVED);
        Brand saved = brandRepository.save(brand);
        brands.add(saved);
        return saved;
    }

    private Store newStore(Brand brand) {
        return newStore(brand, null, null);
    }

    private Store newStore(Brand brand, BigDecimal latitude, BigDecimal longitude) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Store store = new Store();
        store.setBrand(brand);
        store.setStoreName(KEYWORD + "-" + tag);
        store.setAccount("guard-store-" + tag);
        store.setPasswordHash(passwordEncoder.encode("pw"));
        store.setRole("STORE");
        store.setStatus("active");
        store.setLatitude(latitude);
        store.setLongitude(longitude);
        Store saved = storeRepository.save(store);
        stores.add(saved);
        return saved;
    }

    /** 建在同一個座標上的分店（距離為 0），結果的數量與內容都可預期。 */
    private List<Store> newStoresAt(Brand brand, int count) {
        List<Store> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            created.add(newStore(brand, BigDecimal.valueOf(LAT), BigDecimal.valueOf(LNG)));
        }
        return created;
    }

    private Store newFavoriteStore(Brand brand, User user) {
        Store store = newStore(brand);
        UserFavorite favorite = new UserFavorite();
        favorite.setUser(user);
        favorite.setStore(store);
        favorites.add(userFavoriteRepository.save(favorite));
        return store;
    }

    private User newUser() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setPhone("09" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)));
        user.setName("守衛測試顧客-" + tag);
        user.setRole("CUSTOMER");
        User saved = userRepository.save(user);
        users.add(saved);
        return saved;
    }

    private GroupOrder newGroupOrder(Store store, User initiator) {
        GroupOrder order = new GroupOrder();
        order.setStore(store);
        order.setInitiator(initiator);
        order.setType("GROUP");
        order.setStatus("OPEN");
        order.setShareToken(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        GroupOrder saved = groupOrderRepository.save(order);
        groupOrders.add(saved);
        return saved;
    }

    /** 團長發起的一張揪團，團長本人也點了一杯。 */
    private void newHostedOrderWithItem(Store store, User host, ProductTemplate product) {
        GroupOrder order = newGroupOrder(store, host);
        newItem(order, host, product);
    }

    /** 每位團員各點一杯，且是不同的顧客（品項的顧客與商品會走批次載入）。 */
    private void newMemberItems(GroupOrder order, ProductTemplate product, int count) {
        for (int i = 0; i < count; i++) {
            newItem(order, newUser(), product);
        }
    }

    private void newItem(GroupOrder order, User user, ProductTemplate product) {
        OrderItem item = new OrderItem();
        item.setGroupOrder(order);
        item.setUser(user);
        item.setProduct(product);
        item.setProductNameSnapshot(product.getName());
        item.setUnitPriceSnapshot(BigDecimal.valueOf(30));
        item.setFinalPrice(BigDecimal.valueOf(30));
        orderItems.add(orderItemRepository.save(item));
    }

    private MenuCategory newCategory(Brand brand) {
        MenuCategory category = new MenuCategory();
        category.setBrand(brand);
        category.setName("守衛測試分類");
        category.setSortOrder(0);
        MenuCategory saved = menuCategoryRepository.save(category);
        categories.add(saved);
        return saved;
    }

    private List<ProductTemplate> newProducts(Brand brand, MenuCategory category, int count) {
        List<ProductTemplate> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ProductTemplate product = new ProductTemplate();
            product.setBrand(brand);
            product.setCategory(category);
            product.setName("守衛測試品項-" + UUID.randomUUID().toString().substring(0, 8));
            product.setBasePrice(BigDecimal.valueOf(30));
            product.setSortOrder(i);
            ProductTemplate saved = productTemplateRepository.save(product);
            products.add(saved);
            created.add(saved);
        }
        return created;
    }

    /** 建立分店對商品的供應狀態列（與 AnalyticsService 相同的複合鍵寫法）。 */
    private void newStatus(Store store, ProductTemplate product, boolean enabled) {
        StoreProductStatusId id = new StoreProductStatusId();
        id.setStoreId(store.getId());
        id.setProductId(product.getId());
        StoreProductStatus status = new StoreProductStatus();
        status.setId(id);
        status.setStore(store);
        status.setProduct(product);
        status.setIsEnabled(enabled);
        statuses.add(storeProductStatusRepository.save(status));
    }

    /**
     * 建立有評分的分店，平均分數與評分筆數都依建立順序遞增：第 k 間（從 0 起算）平均分數為 1.0 + 0.1 × k，
     * 有 k+1 筆評分（星級 1 到 5 循環）。因此低分分店的 5 間就是最先建立的 5 間，且每間的分布都不同，
     * 批次查詢若把評分分到別的分店，加總就會對不上。
     */
    private void newRatedStores(Brand brand, int count) {
        User host = newUser();
        for (int i = 0; i < count; i++) {
            int index = ratingCountByStore.size();
            int ratingCount = index + 1;
            Store store = newStore(brand);
            store.setAvgRating(BigDecimal.valueOf(10L + index, 1));
            store.setReviewCount(ratingCount);
            Store saved = storeRepository.save(store);
            GroupOrder order = newGroupOrder(saved, host);
            for (int j = 0; j < ratingCount; j++) {
                OrderRating rating = new OrderRating();
                rating.setOrder(order);
                rating.setUser(newUser());
                rating.setStore(saved);
                rating.setRating((j % 5) + 1);
                ratings.add(orderRatingRepository.save(rating));
            }
            ratingCountByStore.put(saved.getId(), ratingCount);
        }
    }

    private static long sumOf(Object distribution) {
        return ((Map<?, ?>) distribution).values().stream().mapToLong(v -> ((Number) v).longValue()).sum();
    }

    private long totalRatings() {
        return ratingCountByStore.values().stream().mapToLong(Integer::longValue).sum();
    }
}
