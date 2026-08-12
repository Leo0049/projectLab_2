package com.example.demo.common;

import com.example.demo.entity.Brand;
import com.example.demo.entity.BrandSpecSetting;
import com.example.demo.entity.BrandToppingSetting;
import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.MenuCategory;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Region;
import com.example.demo.entity.SpecMaster;
import com.example.demo.entity.Store;
import com.example.demo.entity.ToppingMaster;
import com.example.demo.entity.User;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.BrandSpecSettingRepository;
import com.example.demo.repository.BrandToppingSettingRepository;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.MenuCategoryRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.RegionRepository;
import com.example.demo.repository.SpecMasterRepository;
import com.example.demo.repository.ToppingMasterRepository;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final BrandSpecSettingRepository brandSpecSettingRepository;
    private final BrandToppingSettingRepository brandToppingSettingRepository;
    private final SpecMasterRepository specMasterRepository;
    private final ToppingMasterRepository toppingMasterRepository;
    private final StoreRepository storeRepository;
    private final RegionRepository regionRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final ProductTemplateRepository productTemplateRepository;
    private final UserRepository userRepository;
    private final GroupOrderRepository groupOrderRepository;
    private final OrderItemRepository orderItemRepository;
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
        List<ProductTemplate> teaProducts = productTemplateRepository.saveAll(List.of(
                product(tea, classic, "四季春青茶", "35", "清爽回甘的高山青茶"),
                product(tea, classic, "文山包種", "40", "花香明顯，冷熱皆宜"),
                product(tea, milk, "珍珠鮮奶茶", "65", "招牌珍珠搭配純鮮乳"),
                product(tea, milk, "黑糖鮮奶", "70", "手炒黑糖，微苦不膩")
        ));
        List<Store> teaStores = storeRepository.saveAll(List.of(
                store(tea, north, "春日茶事 — 大安店", "demo_store",
                        "台北市大安區復興南路一段 100 號", "25.03360", "121.54350",
                        "02-2711-0100", "4.7", 128, "50", "3.0"),
                store(tea, north, "春日茶事 — 信義店", "demo_store2",
                        "台北市信義區松壽路 12 號", "25.03600", "121.56780",
                        "02-2722-0120", "4.5", 86, "60", "2.5")
        ));

        // ── 品牌 B ────────────────────────────────────────────
        Brand fruit = brandRepository.save(brand("果日鮮飲", "demo_brand2"));
        MenuCategory juice = menuCategoryRepository.save(category(fruit, "鮮果飲", 0));
        productTemplateRepository.saveAll(List.of(
                product(fruit, juice, "檸檬冬瓜", "50", "每日現壓檸檬"),
                product(fruit, juice, "西瓜汁", "60", "當季西瓜，不加水")
        ));
        storeRepository.save(store(fruit, north, "果日鮮飲 — 中山店", "demo_store3",
                "台北市中山區南京東路二段 50 號", "25.05200", "121.53300",
                "02-2531-0250", "4.8", 203, "45", "3.5"));

        // ── 啟用兩個品牌的規格與配料 ────────────────────────────
        // 沒有這一步，品牌後台會擋在「請先完成規格設定」，顧客端也選不了甜度/冰量/加料
        enableSpecsAndToppings(tea);
        enableSpecsAndToppings(fruit);

        // ── 示範顧客 ──────────────────────────────────────────
        User customer = new User();
        customer.setName("示範顧客");
        customer.setPhone("0912000000");
        customer.setRole("CUSTOMER");
        customer.setBalance(new BigDecimal("500.00"));
        customer.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        userRepository.save(customer);

        // ── 示範訂單 ──────────────────────────────────────────
        // 沒有這一步，門市後台的訂單管理是六個空區塊，看起來像功能還沒做完
        seedOrders(teaStores.get(0), customer, teaProducts);

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

    /**
     * 建立一批橫跨各種狀態的示範訂單，讓門市後台的訂單管理、首頁活動單與財務報表
     * 一開啟就有內容。
     *
     * ⚠️ 待處理（SUBMITTED）的 createdAt 一律用「現在」：前端對待處理單有 10 分鐘倒數，
     * 逾時會自動送出取消請求。若把它們的時間往前塞，一開畫面就會被整批自動取消。
     * 其餘狀態才可以回填過去的時間。
     */
    private void seedOrders(Store store, User customer, List<ProductTemplate> products) {
        ProductTemplate green = products.get(0);   // 四季春青茶 35
        ProductTemplate pouchong = products.get(1); // 文山包種 40
        ProductTemplate pearl = products.get(2);   // 珍珠鮮奶茶 65
        ProductTemplate brownSugar = products.get(3); // 黑糖鮮奶 70

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Taipei"));

        // 待處理 — 自取
        order(store, customer, "SOLO", "SUBMITTED", null, "少冰謝謝", now, now,
                List.of(item(pearl, "微糖", "少冰", "大杯", 2),
                        item(green, "半糖", "去冰", "中杯", 1)));

        // 待處理 — 揪團外送
        order(store, customer, "GROUP", "SUBMITTED", "台北市大安區忠孝東路四段 45 號 8 樓",
                "麻煩放櫃檯", now, now,
                List.of(item(brownSugar, "正常", "微冰", "大杯", 1),
                        item(pouchong, "無糖", "熱飲", "中杯", 2),
                        item(green, "微糖", "少冰", "中杯", 1)));

        // 製作中
        order(store, customer, "SOLO", "PREPARING", null, "", now.minusMinutes(6), now.minusMinutes(6),
                List.of(item(pearl, "半糖", "正常冰", "大杯", 1)));

        // 待取餐 — 自取
        order(store, customer, "SOLO", "READY", null, "", now.minusMinutes(22), now.minusMinutes(22),
                List.of(item(pouchong, "無糖", "熱飲", "大杯", 1),
                        item(brownSugar, "正常", "少冰", "中杯", 1)));

        // 配送中 — 有地址即視為外送
        order(store, customer, "GROUP", "READY", "台北市大安區信義路三段 12 號", "",
                now.minusMinutes(15), now.minusMinutes(15),
                List.of(item(green, "微糖", "少冰", "大杯", 3)));

        // 已完成
        order(store, customer, "SOLO", "COMPLETED", null, "", now.minusHours(2), now.minusHours(2),
                List.of(item(brownSugar, "半糖", "去冰", "大杯", 1)));
        order(store, customer, "GROUP", "COMPLETED", null, "", now.minusHours(26), now.minusHours(26),
                List.of(item(pearl, "正常", "正常冰", "大杯", 2),
                        item(pouchong, "微糖", "少冰", "中杯", 1)));
    }

    /** 品項規格：只帶建立 OrderItem 需要的欄位，價格一律取商品當下售價作為快照 */
    private record ItemSpec(ProductTemplate product, String sugar, String ice, String size, int qty) {}

    private ItemSpec item(ProductTemplate product, String sugar, String ice, String size, int qty) {
        return new ItemSpec(product, sugar, ice, size, qty);
    }

    private void order(Store store, User customer, String type, String status,
                       String address, String note,
                       LocalDateTime createdAt, LocalDateTime submittedAt,
                       List<ItemSpec> specs) {
        GroupOrder o = new GroupOrder();
        o.setStore(store);
        o.setInitiator(customer);
        o.setType(type);
        o.setStatus(status);
        o.setAddress(address == null ? "" : address);
        o.setNote(note == null ? "" : note);
        o.setCreatedAt(createdAt);
        o.setSubmittedAt(submittedAt);
        if ("PREPARING".equals(status) || "READY".equals(status) || "COMPLETED".equals(status))
            o.setPreparingAt(submittedAt.plusMinutes(2));
        if ("READY".equals(status) || "COMPLETED".equals(status))
            o.setReadyAt(submittedAt.plusMinutes(8));
        if ("COMPLETED".equals(status))
            o.setCompletedAt(submittedAt.plusMinutes(20));

        BigDecimal total = BigDecimal.ZERO;
        for (ItemSpec s : specs) {
            total = total.add(s.product().getBasePrice().multiply(BigDecimal.valueOf(s.qty())));
        }
        o.setTotalAmount(total);
        o.setEscrowAmount("COMPLETED".equals(status) ? BigDecimal.ZERO : total);
        GroupOrder saved = groupOrderRepository.save(o);

        boolean paid = "COMPLETED".equals(status);
        for (ItemSpec s : specs) {
            OrderItem i = new OrderItem();
            i.setGroupOrder(saved);
            i.setUser(customer);
            i.setProduct(s.product());
            i.setQty(s.qty());
            i.setProductNameSnapshot(s.product().getName());
            i.setUnitPriceSnapshot(s.product().getBasePrice());
            i.setFinalPrice(s.product().getBasePrice().multiply(BigDecimal.valueOf(s.qty())));
            i.setSugarSnapshot(s.sugar());
            i.setIceSnapshot(s.ice());
            i.setSizeSnapshot(s.size());
            i.setPaymentType("WALLET");
            i.setPaymentStatus(paid ? "PAID" : "ESCROWED");
            orderItemRepository.save(i);
        }
    }

    /** 把平台主檔的規格與配料，全部啟用給該品牌 */
    private void enableSpecsAndToppings(Brand brand) {
        int sort = 0;
        for (SpecMaster master : specMasterRepository.findAll()) {
            BrandSpecSetting s = new BrandSpecSetting();
            s.setBrand(brand);
            s.setMaster(master);
            s.setSpecType(master.getType());
            s.setIsEnabled(true);
            s.setSortOrder(sort++);
            brandSpecSettingRepository.save(s);
        }
        for (ToppingMaster master : toppingMasterRepository.findAll()) {
            BrandToppingSetting t = new BrandToppingSetting();
            t.setBrand(brand);
            t.setMasterTopping(master);
            t.setBrandPrice(master.getDefaultPrice());
            t.setIsEnabled(true);
            brandToppingSettingRepository.save(t);
        }
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

    /** 每週營業時間，週一公休（前端 getTodayHours 讀這個 JSON） */
    private static final String OPENING_HOURS = """
            {"mon":{"closed":true},\
            "tue":{"open":"10:00","close":"21:00"},\
            "wed":{"open":"10:00","close":"21:00"},\
            "thu":{"open":"10:00","close":"21:00"},\
            "fri":{"open":"10:00","close":"22:00"},\
            "sat":{"open":"11:00","close":"22:00"},\
            "sun":{"open":"11:00","close":"20:00"}}""";

    private Store store(Brand brand, Region region, String storeName, String account,
                        String address, String lat, String lng,
                        String phone, String rating, int reviewCount,
                        String minDeliveryAmount, String maxDeliveryKm) {
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
        // 以下欄位若留空，前端卡片會滿是「--」與「未設定」，示範時看起來像壞掉
        s.setStorePhone(phone);
        s.setAvgRating(new BigDecimal(rating));
        s.setReviewCount(reviewCount);
        s.setMinDeliveryAmount(new BigDecimal(minDeliveryAmount));
        s.setMaxDeliveryKm(new BigDecimal(maxDeliveryKm));
        s.setIsDeliveryAvailable(true);
        s.setDeliveryStartTime("10:00");
        s.setDeliveryEndTime("21:00");
        s.setOpeningHours(OPENING_HOURS);
        s.setIsAcceptingOrders(true);
        s.setStatus("active");
        return s;
    }
}
