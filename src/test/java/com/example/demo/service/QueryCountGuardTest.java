package com.example.demo.service;

import com.example.demo.common.BrandApproval;
import com.example.demo.dto.BrandSummary;
import com.example.demo.entity.Brand;
import com.example.demo.entity.MenuCategory;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Store;
import com.example.demo.entity.StoreProductStatus;
import com.example.demo.entity.StoreProductStatusId;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.MenuCategoryRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.StoreProductStatusRepository;
import com.example.demo.repository.StoreRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * N+1 守衛（stage 23，IMPROVEMENT_ASSESSMENT 第 8 項）。
 *
 * <p>以 Hibernate 統計量測 JDBC 陳述式數：同一個端點在資料量少與多時，陳述式數必須相等。
 * 守的是「查詢數不隨列數成長」，不是絕對次數，因此新增欄位或關聯時不需要改數字。
 *
 * <p>生產設定的 {@code default_batch_fetch_size: 50} 會把懶載入的關聯依 50 筆一批載入，
 * 所以守衛量的是生產實際的陳述式數：批次載入不會被誤判，逐列各查一次的寫法一定會被抓到。
 *
 * <p>只涵蓋已確認位於請求路徑上的讀取端點。新增讀取端點時，請在這裡加入對應案例。
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class QueryCountGuardTest {

    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private MenuService menuService;
    @Autowired private PublicService publicService;
    @Autowired private BrandRepository brandRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuCategoryRepository menuCategoryRepository;
    @Autowired private ProductTemplateRepository productTemplateRepository;
    @Autowired private StoreProductStatusRepository storeProductStatusRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /** 本測試建立的資料列，結束後依外鍵相反的順序清除（狀態列 → 商品 → 分類 → 分店 → 品牌）。 */
    private final List<StoreProductStatus> statuses = new ArrayList<>();
    private final List<ProductTemplate> products = new ArrayList<>();
    private final List<MenuCategory> categories = new ArrayList<>();
    private final List<Store> stores = new ArrayList<>();
    private final List<Brand> brands = new ArrayList<>();

    private record Measured<T>(long statements, T result) {}

    @AfterEach
    void removeCreatedRows() {
        storeProductStatusRepository.deleteAll(statuses);
        productTemplateRepository.deleteAll(products);
        menuCategoryRepository.deleteAll(categories);
        storeRepository.deleteAll(stores);
        brandRepository.deleteAll(brands);
        statuses.clear();
        products.clear();
        categories.clear();
        stores.clear();
        brands.clear();
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
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Store store = new Store();
        store.setBrand(brand);
        store.setStoreName("守衛測試分店-" + tag);
        store.setAccount("guard-store-" + tag);
        store.setPasswordHash(passwordEncoder.encode("pw"));
        store.setRole("STORE");
        store.setStatus("active");
        Store saved = storeRepository.save(store);
        stores.add(saved);
        return saved;
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
}
