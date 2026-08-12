package com.example.demo.common;

import com.example.demo.entity.Brand;
import com.example.demo.entity.MenuCategory;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Region;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.MenuCategoryRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.RegionRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 示範資料 — 讓 clone 下來的人 `docker compose up -d && mvn spring-boot:run` 之後
 * 就有東西可以看（首頁有店、地圖有標記、點進去有飲品），而不是一片空白。
 *
 * ⚠️ 這支會建立「密碼已知」的示範帳號，正式環境務必以
 *    DEMO_DATA_ENABLED=false 關閉（application.yml 的 app.demo-data.enabled）。
 *
 * 僅在資料表為空時植入，重複啟動不會產生重複資料，也不會覆蓋既有資料。
 */
@Slf4j
@Component
@Order(20) // 在 DataSeeder（規格/配料主檔）之後
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private static final String DEMO_PASSWORD = "demo1234";

    private final BrandRepository brandRepository;
    private final StoreRepository storeRepository;
    private final RegionRepository regionRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final ProductTemplateRepository productTemplateRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.demo-data.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("app.demo-data.enabled=false，略過示範資料");
            return;
        }
        if (brandRepository.count() > 0 || storeRepository.count() > 0) {
            return; // 已有資料，不動它
        }

        Region north = regionRepository.save(region("北部"));
        regionRepository.save(region("中部"));
        regionRepository.save(region("南部"));

        // ── 品牌 A ────────────────────────────────────────────
        Brand tea = brandRepository.save(brand("春日茶事", "demo_brand"));
        MenuCategory classic = menuCategoryRepository.save(category(tea, "經典茶飲", 0));
        MenuCategory milk = menuCategoryRepository.save(category(tea, "鮮奶茶", 1));
        productTemplateRepository.saveAll(List.of(
                product(tea, classic, "四季春青茶", "35", "清爽回甘的高山青茶"),
                product(tea, classic, "文山包種", "40", "花香明顯，冷熱皆宜"),
                product(tea, milk, "珍珠鮮奶茶", "65", "招牌珍珠搭配純鮮乳"),
                product(tea, milk, "黑糖鮮奶", "70", "手炒黑糖，微苦不膩")
        ));
        storeRepository.saveAll(List.of(
                store(tea, north, "春日茶事 — 大安店", "demo_store",
                        "台北市大安區復興南路一段 100 號", "25.03360", "121.54350"),
                store(tea, north, "春日茶事 — 信義店", "demo_store2",
                        "台北市信義區松壽路 12 號", "25.03600", "121.56780")
        ));

        // ── 品牌 B ────────────────────────────────────────────
        Brand fruit = brandRepository.save(brand("果日鮮飲", "demo_brand2"));
        MenuCategory juice = menuCategoryRepository.save(category(fruit, "鮮果飲", 0));
        productTemplateRepository.saveAll(List.of(
                product(fruit, juice, "檸檬冬瓜", "50", "每日現壓檸檬"),
                product(fruit, juice, "西瓜汁", "60", "當季西瓜，不加水")
        ));
        storeRepository.save(store(fruit, north, "果日鮮飲 — 中山店", "demo_store3",
                "台北市中山區南京東路二段 50 號", "25.05200", "121.53300"));

        // ── 示範顧客 ──────────────────────────────────────────
        User customer = new User();
        customer.setName("示範顧客");
        customer.setPhone("0912000000");
        customer.setRole("CUSTOMER");
        customer.setBalance(new BigDecimal("500.00"));
        customer.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        userRepository.save(customer);

        log.warn("""

                ┌─ 已植入示範資料（密碼皆為 {}）─────────────────────
                │  顧客   0912000000
                │  品牌   demo_brand / demo_brand2
                │  門市   demo_store / demo_store2 / demo_store3
                │
                │  正式環境請設定 DEMO_DATA_ENABLED=false
                └───────────────────────────────────────────────────
                """, DEMO_PASSWORD);
    }

    private Region region(String name) {
        Region r = new Region();
        r.setName(name);
        return r;
    }

    private Brand brand(String name, String account) {
        Brand b = new Brand();
        b.setName(name);
        b.setAccount(account);
        b.setRole("BRAND");
        b.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        return b;
    }

    private MenuCategory category(Brand brand, String name, int sort) {
        MenuCategory c = new MenuCategory();
        c.setBrand(brand);
        c.setName(name);
        c.setSortOrder(sort);
        c.setIsEnabled(true);
        return c;
    }

    private ProductTemplate product(Brand brand, MenuCategory category,
                                    String name, String price, String description) {
        ProductTemplate p = new ProductTemplate();
        p.setBrand(brand);
        p.setCategory(category);
        p.setName(name);
        p.setBasePrice(new BigDecimal(price));
        p.setDescription(description);
        p.setIsEnabled(true);
        p.setSortOrder(0);
        return p;
    }

    private Store store(Brand brand, Region region, String storeName, String account,
                        String address, String lat, String lng) {
        Store s = new Store();
        s.setBrand(brand);
        s.setRegion(region);
        s.setStoreName(storeName);
        s.setAccount(account);
        s.setRole("STORE");
        s.setAddress(address);
        s.setLatitude(new BigDecimal(lat));
        s.setLongitude(new BigDecimal(lng));
        s.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        return s;
    }
}
