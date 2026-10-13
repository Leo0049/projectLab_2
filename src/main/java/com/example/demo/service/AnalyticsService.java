package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    @Autowired
    private GroupOrderRepository groupOrderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private StoreProductStatusRepository storeProductStatusRepository;
    @Autowired
    private ProductTemplateRepository productTemplateRepository;
    @Autowired
    private com.example.demo.service.GroupOrderService groupOrderService;

    @Autowired
    private MenuCategoryRepository menuCategoryRepository;

    @Autowired
    private OrderRatingRepository orderRatingRepository;

    @Autowired
    private OrderItemToppingRepository orderItemToppingRepository;

    // ═══════════════════════════════════════════════
    // 品牌 Dashboard
    // ═══════════════════════════════════════════════

    public Map<String, Object> getBrandDailyOverview(Long brandId) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);
        LocalDateTime yestStart = todayStart.minusDays(1);
        List<GroupOrder> todayOrders = groupOrderRepository.findByBrandIdAndStatusAndPeriod(brandId, "COMPLETED", todayStart, todayEnd);
        List<GroupOrder> yestOrders = groupOrderRepository.findByBrandIdAndStatusAndPeriod(brandId, "COMPLETED", yestStart, todayStart);
        BigDecimal todayRev = todayOrders.stream().filter(o -> o.getTotalAmount() != null).map(GroupOrder::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal yestRev = yestOrders.stream().filter(o -> o.getTotalAmount() != null).map(GroupOrder::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int todayCnt = todayOrders.size();
        int yestCnt = yestOrders.size();
        Map<String, Object> m = new HashMap<>();
        m.put("total_revenue", todayRev);
        m.put("revenue_trend", calcTrend(todayRev, yestRev));
        m.put("total_orders", todayCnt);
        m.put("order_trend", calcTrendInt(todayCnt, yestCnt));
        BigDecimal aov = todayCnt > 0 ? todayRev.divide(BigDecimal.valueOf(todayCnt), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal yestAov = yestCnt > 0 ? yestRev.divide(BigDecimal.valueOf(yestCnt), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        m.put("avg_order_value", aov);
        m.put("aov_trend", calcTrend(aov, yestAov));
        return m;
    }

    public Map<String, Object> getStoreStatus(Long brandId) {
        List<Store> stores = storeRepository.findByBrandId(brandId);
        long open = stores.stream().filter(s -> "active".equals(s.getStatus())).count();
        long closed = stores.stream().filter(s -> "closed".equals(s.getStatus())).count();
        long abnormal = stores.size() - open - closed;
        Map<String, Object> m = new HashMap<>();
        m.put("open", open);
        m.put("closed", closed);
        m.put("abnormal", abnormal);
        m.put("total", stores.size());
        return m;
    }

    public List<Map<String, Object>> getTop5StoreRevenue(Long brandId) {
        LocalDateTime weekStart = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime weekEnd = weekStart.plusWeeks(1);
        LocalDateTime lastWeekStart = weekStart.minusWeeks(1);
        List<Object[]> thisWeek = groupOrderRepository.findStoreRevenueRanking(brandId, weekStart, weekEnd);
        List<Object[]> lastWeek = groupOrderRepository.findStoreRevenueRanking(brandId, lastWeekStart, weekStart);
        Map<Long, BigDecimal> lastMap = new HashMap<>();
        for (Object[] r : lastWeek) { lastMap.put(toLong(r[0]), toBigDecimal(r[3])); }
        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (Object[] r : thisWeek) {
            Long storeId = toLong(r[0]);
            BigDecimal rev = toBigDecimal(r[3]);
            BigDecimal lastRev = lastMap.getOrDefault(storeId, BigDecimal.ZERO);
            String trend = rev.compareTo(lastRev) > 0 ? "UP" : rev.compareTo(lastRev) < 0 ? "DOWN" : "FLAT";
            Map<String, Object> m = new HashMap<>();
            m.put("rank", rank++);
            m.put("storeId", storeId);
            m.put("storeName", r[1]);
            m.put("revenue", rev);
            m.put("trend", trend);
            result.add(m);
        }
        return result;
    }

    public Map<String, Object> getBrandProductRanking(Long brandId, String period) {
        LocalDateTime[] range = getPeriodRange(period);
        LocalDateTime[] prevRange = getPreviousPeriodRange(period);
        List<Object[]> currentRows = groupOrderRepository.findBrandProductRanking(brandId, range[0], range[1]);
        List<Object[]> prevRows = groupOrderRepository.findBrandProductRanking(brandId, prevRange[0], prevRange[1]);
        Map<String, Long> prevMap = new HashMap<>();
        for (Object[] r : prevRows) { prevMap.put(r[0].toString(), toLong(r[1])); }
        List<Map<String, Object>> hot = new ArrayList<>();
        List<Map<String, Object>> slow = new ArrayList<>();
        int size = currentRows.size();
        for (int i = 0; i < size; i++) {
            Object[] r = currentRows.get(i);
            String name = r[0].toString();
            long count = toLong(r[1]);
            long prevCount = prevMap.getOrDefault(name, 0L);
            double trend = 0;
            if (prevCount > 0) { trend = ((double) (count - prevCount) / prevCount) * 100; }
            else if (count > 0) { trend = 100.0; }
            Map<String, Object> m = new HashMap<>();
            m.put("rank", i + 1);
            m.put("productName", name);
            m.put("count", count);
            m.put("revenue", toBigDecimal(r[2]));
            m.put("trend", trend);
            if (i < 10) hot.add(m);
            if (i >= size - 10) { Map<String, Object> s = new HashMap<>(m); s.put("rank", size - i); slow.add(0, s); }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("hot", hot);
        result.put("slow", slow);
        return result;
    }

    public Map<String, Object> getBrandFinanceSummaryFull(Long brandId, String period) {
        LocalDateTime[] range = getPeriodRange(period);
        List<GroupOrder> orders = groupOrderRepository.findByBrandIdAndStatusAndPeriod(brandId, "COMPLETED", range[0], range[1]);
        BigDecimal total = orders.stream().map(GroupOrder::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = orders.isEmpty() ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(orders.size()), 2, RoundingMode.HALF_UP);
        List<Object[]> storeRows = groupOrderRepository.findBrandStoreFinance(brandId, range[0], range[1]);
        List<Map<String, Object>> storeBreakdown = new ArrayList<>();
        for (Object[] r : storeRows) {
            Map<String, Object> m = new HashMap<>();
            m.put("storeId", toLong(r[0]));
            m.put("storeName", r[1]);
            m.put("orderCount", toLong(r[2]));
            m.put("revenue", toBigDecimal(r[3]));
            storeBreakdown.add(m);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("period", period);
        result.put("totalRevenue", total);
        result.put("orderCount", orders.size());
        result.put("avgOrderValue", avg);
        result.put("storeBreakdown", storeBreakdown);
        return result;
    }

    public Map<String, Object> getBrandFinanceOverview(Long brandId, String period) {
        LocalDateTime[] range = getPeriodRange(period);
        LocalDateTime[] prev = getPreviousPeriodRange(period);
        List<GroupOrder> orders = groupOrderRepository.findByBrandIdAndStatusAndPeriod(brandId, "COMPLETED", range[0], range[1]);
        List<GroupOrder> prevOrders = groupOrderRepository.findByBrandIdAndStatusAndPeriod(brandId, "COMPLETED", prev[0], prev[1]);
        BigDecimal total = orders.stream().map(GroupOrder::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal prevTotal = prevOrders.stream().map(GroupOrder::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rate = new BigDecimal("0.30");
        BigDecimal commission = total.multiply(rate).setScale(0, RoundingMode.HALF_UP);
        BigDecimal netIncome = total.subtract(commission);
        BigDecimal prevNet = prevTotal.subtract(prevTotal.multiply(rate).setScale(0, RoundingMode.HALF_UP));
        Map<String, Object> m = new HashMap<>();
        m.put("period", period);
        m.put("totalRevenue", total);
        m.put("commission", commission);
        m.put("netIncome", netIncome);
        m.put("revenueTrend", calcTrend(total, prevTotal));
        m.put("netIncomeTrend", calcTrend(netIncome, prevNet));
        return m;
    }

    public Map<String, Object> getBrandRevenueTrend(Long brandId, String period) {
        LocalDate today = LocalDate.now();
        List<String> labels = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        if ("week".equals(period)) {
            LocalDate currentWeekMon = today.with(DayOfWeek.MONDAY);
            LocalDate fromDate = currentWeekMon.minusWeeks(6);
            List<Object[]> rows = groupOrderRepository.findBrandDailyRevenue(brandId, fromDate.atStartOfDay(), currentWeekMon.atStartOfDay());
            Map<LocalDate, BigDecimal> daily = buildDailyMap(rows);
            java.time.format.DateTimeFormatter dfmt = java.time.format.DateTimeFormatter.ofPattern("MM/dd");
            for (int i = 5; i >= 0; i--) {
                LocalDate weekStart = currentWeekMon.minusWeeks(i + 1);
                labels.add(weekStart.format(dfmt));
                values.add(sumRange(daily, weekStart, weekStart.plusDays(7)));
            }
        } else if ("quarter".equals(period)) {
            // Custom seasons: Spring(3-5), Summer(6-8), Fall(9-11), Winter(12-2)
            // qIdx: 0=Winter, 1=Spring, 2=Summer, 3=Fall
            int currentMonth = today.getMonthValue();
            int qIdx = currentMonth >= 3 && currentMonth <= 5 ? 1
                     : currentMonth >= 6 && currentMonth <= 8 ? 2
                     : currentMonth >= 9 && currentMonth <= 11 ? 3 : 0;
            int qYear = today.getYear();
            List<LocalDate[]> qRanges = new ArrayList<>();
            List<String> qLabels = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                LocalDate qStart, qEnd;
                String label;
                if (qIdx == 0) {
                    qStart = LocalDate.of(qYear - 1, 12, 1);
                    qEnd   = LocalDate.of(qYear, 3, 1);
                    label  = "12-2月";
                } else if (qIdx == 1) {
                    qStart = LocalDate.of(qYear, 3, 1);
                    qEnd   = LocalDate.of(qYear, 6, 1);
                    label  = "3-5月";
                } else if (qIdx == 2) {
                    qStart = LocalDate.of(qYear, 6, 1);
                    qEnd   = LocalDate.of(qYear, 9, 1);
                    label  = "6-8月";
                } else {
                    qStart = LocalDate.of(qYear, 9, 1);
                    qEnd   = LocalDate.of(qYear, 12, 1);
                    label  = "9-11月";
                }
                qRanges.add(0, new LocalDate[]{qStart, qEnd});
                qLabels.add(0, label);
                if (--qIdx < 0) { qIdx = 3; qYear--; }
            }
            LocalDate fetchFrom = qRanges.get(0)[0];
            List<Object[]> rows = groupOrderRepository.findBrandDailyRevenue(brandId, fetchFrom.atStartOfDay(), today.plusDays(1).atStartOfDay());
            Map<LocalDate, BigDecimal> daily = buildDailyMap(rows);
            for (int i = 0; i < 4; i++) {
                labels.add(qLabels.get(i));
                LocalDate qEnd = qRanges.get(i)[1].isAfter(today) ? today.plusDays(1) : qRanges.get(i)[1];
                values.add(sumRange(daily, qRanges.get(i)[0], qEnd));
            }
        } else {
            LocalDate currentMonthStart = LocalDate.of(today.getYear(), today.getMonth(), 1);
            LocalDate fromDate = currentMonthStart.minusMonths(6);
            List<Object[]> rows = groupOrderRepository.findBrandDailyRevenue(brandId, fromDate.atStartOfDay(), currentMonthStart.atStartOfDay());
            Map<LocalDate, BigDecimal> daily = buildDailyMap(rows);
            for (int i = 5; i >= 0; i--) {
                LocalDate ms = currentMonthStart.minusMonths(i + 1);
                labels.add(ms.getMonthValue() + "月");
                values.add(sumRange(daily, ms, ms.plusMonths(1)));
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("labels", labels);
        result.put("values", values);
        return result;
    }

    public List<Map<String, Object>> getBrandRegionFinance(Long brandId, String period) {
        LocalDateTime[] range = getPeriodRange(period);
        List<Object[]> rows = groupOrderRepository.findBrandRegionFinance(brandId, range[0], range[1]);
        BigDecimal rate = new BigDecimal("0.30");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            BigDecimal revenue = toBigDecimal(row[2]);
            BigDecimal commission = revenue.multiply(rate).setScale(0, RoundingMode.HALF_UP);
            Map<String, Object> m = new HashMap<>();
            m.put("regionName", row[0] != null ? row[0].toString() : "未分類");
            m.put("storeCount", toLong(row[1]));
            m.put("totalRevenue", revenue);
            m.put("commission", commission);
            m.put("netIncome", revenue.subtract(commission));
            result.add(m);
        }
        return result;
    }

    // ═══════════════════════════════════════════════
    // 分店 訂單管理 (遵守 6 大狀態邏輯)
    // ═══════════════════════════════════════════════

    /** 單次查詢的訂單數上限，避免用戶端指定過大的 size 讓回應再度失控 */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * 分店訂單列表（分頁）。
     *
     * ⚠️ 這裡必須分頁。舊版一次撈出該門市全部歷史訂單，壓測實測 6,666 筆訂單
     *    的單一回應是 2.1 MB，50 併發下 p50 2.7 秒、p99 超過 10 秒，
     *    比其他端點慢 50~60 倍——查詢本身不慢，慢在序列化與傳輸整份歷史。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStoreOrders(Long storeId, String status, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        Page<GroupOrder> result = groupOrderRepository.findByStoreIdAndOptionalStatus(
                storeId, status, PageRequest.of(safePage, safeSize));

        Map<String, Object> body = new HashMap<>();
        body.put("orders", enrichOrdersWithItems(result.getContent()));
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("total", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        body.put("hasNext", result.hasNext());
        return body;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getStorePendingOrders(Long storeId) {
        // 待接單 = SUBMITTED
        List<GroupOrder> orders = groupOrderRepository.findByStoreIdAndStatus(storeId, "SUBMITTED");
        return enrichOrdersWithItems(orders);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getStoreActiveOrders(Long storeId) {
        // 首頁活動單：包含 SUBMITTED (待處理)、PREPARING (製作中) 與 READY (待領取/配送)
        List<String> activeStatuses = Arrays.asList("SUBMITTED", "PREPARING", "READY");
        return groupOrderRepository.findByStoreIdAndOptionalStatus(storeId, null).stream()
                .filter(o -> activeStatuses.contains(o.getStatus()))
                .map(this::orderToMapWithItems)
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> enrichOrdersWithItems(List<GroupOrder> orders) {
        if (orders.isEmpty()) return Collections.emptyList();
        List<Long> orderIds = orders.stream().map(GroupOrder::getId).collect(Collectors.toList());
        List<OrderItem> allItems = orderItemRepository.findByGroupOrderIdIn(orderIds);
        List<Long> itemIds = allItems.stream().map(OrderItem::getId).collect(Collectors.toList());
        
        // 批次抓取所有品項的配料
        Map<Long, List<OrderItemTopping>> toppingsByItem = new HashMap<>();
        if (!itemIds.isEmpty()) {
            toppingsByItem = orderItemToppingRepository.findByOrderItemIdIn(itemIds)
                .stream().collect(Collectors.groupingBy(t -> t.getOrderItem().getId()));
        }

        Map<Long, List<OrderItem>> itemsByOrder = allItems.stream().collect(Collectors.groupingBy(i -> i.getGroupOrder().getId()));
        
        final Map<Long, List<OrderItemTopping>> finalToppings = toppingsByItem;
        return orders.stream().map(o -> {
            List<OrderItem> orderItems = itemsByOrder.getOrDefault(o.getId(), Collections.emptyList());
            return orderToMapWithItemsList(o, orderItems, finalToppings);
        }).collect(Collectors.toList());
    }

    private Map<String, Object> orderToMapWithItemsList(GroupOrder o, List<OrderItem> items, Map<Long, List<OrderItemTopping>> toppingsMap) {
        Map<String, Object> m = orderToMap(o);
        Map<String, Map<String, Object>> groupedItems = new LinkedHashMap<>();
        BigDecimal totalSubtotal = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;

        if (items != null) {
            for (OrderItem i : items) {
                String name = i.getProductNameSnapshot() != null ? i.getProductNameSnapshot() : "未命名飲品";
                String sugar = i.getSugarSnapshot() != null ? i.getSugarSnapshot() : "正常糖";
                String ice = i.getIceSnapshot() != null ? i.getIceSnapshot() : "正常冰";
                String size = i.getSizeSnapshot() != null ? i.getSizeSnapshot() : "大杯";
                // 處理配料
                List<OrderItemTopping> tps = toppingsMap.getOrDefault(i.getId(), Collections.emptyList());
                String toppingsStr = tps.stream().map(t -> t.getId().getToppingNameSnapshot()).collect(Collectors.joining(","));

                BigDecimal price = i.getFinalPrice() != null ? i.getFinalPrice() : i.getUnitPriceSnapshot();
                // 分組 Key 加入尺寸與配料，確保不同規格不被合併
                String key = name + "|" + size + "|" + ice + "|" + sugar + "|" + (price != null ? price.toString() : "0") + "|" + toppingsStr;
                
                if (price != null) {
                    totalSubtotal = totalSubtotal.add(price.add(i.getDiscountAmountSnapshot() != null ? i.getDiscountAmountSnapshot() : BigDecimal.ZERO));
                }
                totalDiscount = totalDiscount.add(i.getDiscountAmountSnapshot() != null ? i.getDiscountAmountSnapshot() : BigDecimal.ZERO);

                if (groupedItems.containsKey(key)) {
                    Map<String, Object> itemMap = groupedItems.get(key);
                    int currentQty = ((Number) itemMap.get("qty")).intValue();
                    itemMap.put("qty", currentQty + 1);
                } else {
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("productNameSnapshot", name);
                    itemMap.put("sugar", sugar);
                    itemMap.put("ice", ice);
                    itemMap.put("size", size);
                    itemMap.put("toppings", toppingsStr.isEmpty() ? null : toppingsStr.split(","));
                    itemMap.put("qty", 1);
                    itemMap.put("price", price);
                    itemMap.put("unitPriceSnapshot", i.getUnitPriceSnapshot());
                    itemMap.put("sugarSnapshot", sugar);
                    itemMap.put("iceSnapshot", ice);
                    itemMap.put("sizeSnapshot", size);
                    itemMap.put("finalPrice", price);
                    itemMap.put("discountAmount", i.getDiscountAmountSnapshot());
                    itemMap.put("userName", i.getUser() != null ? i.getUser().getName() : "匿名");
                    groupedItems.put(key, itemMap);
                }
            }
        }
        // 同品名的品項鄰近排列，便於門市人員閱讀
        List<Map<String, Object>> sortedItems = new ArrayList<>(groupedItems.values());
        sortedItems.sort(Comparator.comparing(item -> String.valueOf(item.get("productNameSnapshot"))));
        m.put("items", sortedItems);
        m.put("subtotal", totalSubtotal);
        m.put("totalDiscount", totalDiscount);

        if (o.getInitiator() != null) {
            Map<String, Object> initiator = new HashMap<>();
            initiator.put("name", o.getInitiator().getName());
            initiator.put("phone", o.getInitiator().getPhone());
            m.put("initiator", initiator);
        }
        return m;
    }

    @Transactional
    public void acceptOrder(Long orderId, Long storeId) {
        GroupOrder order = getOrderForStore(orderId, storeId);
        String status = order.getStatus();
        if ("OPEN".equals(status) || "SUBMITTED".equals(status)) {
            order.setStatus("PREPARING");
            order.setPreparingAt(LocalDateTime.now(ZoneId.of("Asia/Taipei")));
        } else {
            throw new CustomException("400", "只有 OPEN 或 SUBMITTED 狀態可以接單");
        }
        groupOrderRepository.save(order);
    }

    @Transactional
    public void rejectOrder(Long orderId, Long storeId) {
        GroupOrder order = getOrderForStore(orderId, storeId);
        String rejectStatus = order.getStatus();
        if (!"SUBMITTED".equals(rejectStatus) && !"OPEN".equals(rejectStatus) && !"PREPARING".equals(rejectStatus))
            throw new CustomException("400", "只有 OPEN、SUBMITTED 或 PREPARING 狀態可以拒單");

        // 1. 執行集中式退款
        groupOrderService.handleGroupOrderCancellation(orderId);

        // 2. 修正狀態為 REJECTED (handleGroupOrderCancellation 預設為 CANCELLED)
        order.setStatus("REJECTED");
        order.setCancelledOrRejectedAt(LocalDateTime.now());
        order.setIsRejected(true);
        groupOrderRepository.save(order);
    }

    @Transactional
    public void completeProduction(Long orderId, Long storeId) {
        GroupOrder order = getOrderForStore(orderId, storeId);
        // 如果已經是 READY，則不重複執行
        if ("READY".equals(order.getStatus())) return;
        
        String s = order.getStatus();
        if (!"PREPARING".equals(s)) {
            throw new CustomException("400", "只有 PREPARING 狀態可以完成製作");
        }
        
        order.setStatus("READY");
        order.setReadyAt(LocalDateTime.now());
        
        groupOrderRepository.save(order);
    }

    @Transactional
    public void finalizeOrder(Long orderId, Long storeId) {
        GroupOrder order = getOrderForStore(orderId, storeId);
        if (!"READY".equals(order.getStatus())) throw new CustomException("400", "只有 READY 狀態可以結案");
        order.setStatus("COMPLETED");
        order.setCompletedAt(LocalDateTime.now());
        groupOrderRepository.save(order);
    }

    public Map<String, Object> toggleProductStock(Long storeId, Long productId) {
        StoreProductStatusId id = new StoreProductStatusId();
        id.setStoreId(storeId); id.setProductId(productId);
        Optional<StoreProductStatus> opt = storeProductStatusRepository.findById(id);
        StoreProductStatus sps;
        if (opt.isPresent()) { sps = opt.get(); sps.setIsEnabled(!sps.getIsEnabled()); }
        else {
            sps = new StoreProductStatus(); sps.getId().setStoreId(storeId); sps.getId().setProductId(productId);
            Store store = storeRepository.findById(storeId).orElseThrow(() -> new CustomException("404", "店家不存在"));
            ProductTemplate pt = productTemplateRepository.findById(productId).orElseThrow(() -> new CustomException("404", "商品不存在"));
            sps.setStore(store); sps.setProduct(pt); sps.setIsEnabled(false);
        }
        storeProductStatusRepository.save(sps);
        Map<String, Object> m = new HashMap<>(); m.put("productId", productId); m.put("isEnabled", sps.getIsEnabled());
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOrderDetail(Long orderId) {
        GroupOrder order = groupOrderRepository.findById(orderId).orElseThrow(() -> new CustomException("404", "訂單不存在"));
        return orderToMapWithItems(order);
    }

    private GroupOrder getOrderForStore(Long orderId, Long storeId) {
        GroupOrder order = groupOrderRepository.findById(orderId).orElseThrow(() -> new CustomException("404", "訂單不存在"));
        if (!order.getStore().getId().equals(storeId)) throw new CustomException("403", "無權限");
        return order;
    }

    private Map<String, Object> orderToMap(GroupOrder o) {
        Map<String, Object> m = new HashMap<>();
        m.put("orderId", o.getId());
        m.put("orderNo", o.getOrderNo());
        m.put("type", o.getType());
        m.put("status", o.getStatus());
        m.put("totalAmount", o.getTotalAmount());
        m.put("note", o.getNote());
        m.put("address", o.getAddress());
        m.put("createdAt", o.getCreatedAt());
        m.put("submittedAt", o.getSubmittedAt());
        m.put("readyAt", o.getReadyAt());
        m.put("completedAt", o.getCompletedAt());
        m.put("cancelledOrRejectedAt", o.getCancelledOrRejectedAt());
        return m;
    }

    private Map<String, Object> orderToMapWithItems(GroupOrder o) {
        List<OrderItem> dbItems = orderItemRepository.findByGroupOrderId(o.getId());
        List<Long> itemIds = dbItems.stream().map(OrderItem::getId).collect(Collectors.toList());
        Map<Long, List<OrderItemTopping>> toppingsMap = new HashMap<>();
        if (!itemIds.isEmpty()) {
            toppingsMap = orderItemToppingRepository.findByOrderItemIdIn(itemIds)
                .stream().collect(Collectors.groupingBy(t -> t.getOrderItem().getId()));
        }
        return orderToMapWithItemsList(o, dbItems, toppingsMap);
    }

    // 分店 飲品/庫存管理
    public List<Map<String, Object>> getStoreMenuDrinks(Long storeId) {
        Store store = storeRepository.findById(storeId).orElseThrow(() -> new CustomException("404", "店家不存在"));
        if (store.getBrand() == null) return Collections.emptyList();
        Map<Long, Boolean> statusMap = storeProductStatusRepository.findByStoreId(storeId).stream().collect(Collectors.toMap(s -> s.getId().getProductId(), StoreProductStatus::getIsEnabled));
        return productTemplateRepository.findByBrandId(store.getBrand().getId()).stream().map(pt -> {
            Map<String, Object> m = new HashMap<>(); m.put("drinkId", pt.getId()); m.put("drinkName", pt.getName()); m.put("categoryName", pt.getCategory() != null ? pt.getCategory().getName() : "未分類"); m.put("price", pt.getBasePrice()); m.put("imageUrl", pt.getLogoUrl()); m.put("isAvailable", statusMap.getOrDefault(pt.getId(), true));
            return m;
        }).collect(Collectors.toList());
    }

    public void toggleDrinkSupply(Long storeId, Long drinkId, Boolean isAvailable) {
        StoreProductStatusId id = new StoreProductStatusId(); id.setStoreId(storeId); id.setProductId(drinkId);
        StoreProductStatus sps = storeProductStatusRepository.findById(id).orElseGet(() -> {
            Store st = storeRepository.getReferenceById(storeId); ProductTemplate pt = productTemplateRepository.getReferenceById(drinkId);
            StoreProductStatus s = new StoreProductStatus(); s.setId(id); s.setStore(st); s.setProduct(pt);
            return s;
        });
        sps.setIsEnabled(isAvailable); storeProductStatusRepository.save(sps);
    }

    // 分店 報表與財務
    public Map<String, Object> getStoreTodaySummary(Long storeId) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay(); LocalDateTime todayEnd = todayStart.plusDays(1); LocalDateTime yestStart = todayStart.minusDays(1);
        List<Object[]> todayRows = groupOrderRepository.findStoreTodaySummary(storeId, todayStart, todayEnd);
        List<Object[]> yestRows  = groupOrderRepository.findStoreTodaySummary(storeId, yestStart, todayStart);
        long count = 0; BigDecimal revenue = BigDecimal.ZERO;
        if (!todayRows.isEmpty() && todayRows.get(0)[0] != null) { count = ((Number) todayRows.get(0)[0]).longValue(); revenue = new BigDecimal(todayRows.get(0)[1].toString()); }
        long yCount = 0; BigDecimal yRevenue = BigDecimal.ZERO;
        if (!yestRows.isEmpty() && yestRows.get(0)[0] != null) { yCount = ((Number) yestRows.get(0)[0]).longValue(); yRevenue = new BigDecimal(yestRows.get(0)[1].toString()); }
        BigDecimal avg = count > 0 ? revenue.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal yAvg = yCount > 0 ? yRevenue.divide(BigDecimal.valueOf(yCount), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        Map<String, Object> m = new HashMap<>();
        m.put("orderCount", count); m.put("totalRevenue", revenue); m.put("avgOrderValue", avg); m.put("revenueTrend", calcTrend(revenue, yRevenue)); m.put("orderTrend", calcTrendInt((int) count, (int) yCount)); m.put("aovTrend", calcTrend(avg, yAvg));
        return m;
    }

    public Map<String, Object> getStoreAnalytics(Long storeId) {
        LocalDateTime start = LocalDate.now().atStartOfDay(); LocalDateTime end = start.plusDays(1);
        List<Object[]> hourly = orderItemRepository.findHourlySales(storeId, start, end);
        List<Map<String, Object>> hourlySales = new ArrayList<>();
        for (Object[] r : hourly) { Map<String, Object> h = new HashMap<>(); h.put("hour", r[0]); h.put("count", r[1]); hourlySales.add(h); }
        List<Object[]> top = orderItemRepository.findTopProducts(storeId, start, end);
        List<Map<String, Object>> topProducts = new ArrayList<>();
        int rank = 1;
        for (Object[] r : top) { if (rank > 5) break; Map<String, Object> p = new HashMap<>(); p.put("rank", rank++); p.put("productName", r[0]); p.put("count", r[1]); p.put("revenue", r[2]); topProducts.add(p); }
        Map<String, Object> m = new HashMap<>(); m.put("hourlySales", hourlySales); m.put("topProducts", topProducts);
        return m;
    }

    public Map<String, Object> getStoreSalesTrend(Long storeId) {
        LocalDate today = LocalDate.now(); LocalDate thisWeekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); LocalDate lastWeekStart = thisWeekStart.minusWeeks(1);
        List<Object[]> thisWeekRows = groupOrderRepository.findStoreDailyRevenue(storeId, thisWeekStart.atStartOfDay(), thisWeekStart.plusDays(7).atStartOfDay());
        List<Object[]> lastWeekRows = groupOrderRepository.findStoreDailyRevenue(storeId, lastWeekStart.atStartOfDay(), thisWeekStart.atStartOfDay());
        Map<LocalDate, BigDecimal> thisWeekMap = buildDailyMap(thisWeekRows); Map<LocalDate, BigDecimal> lastWeekMap = buildDailyMap(lastWeekRows);
        String[] dayLabels = {"週一", "週二", "週三", "週四", "週五", "週六", "週日"};
        List<BigDecimal> thisWeekValues = new ArrayList<>(); List<BigDecimal> lastWeekValues = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate dThis = thisWeekStart.plusDays(i); LocalDate dLast = lastWeekStart.plusDays(i);
            if (dThis.isAfter(today)) { thisWeekValues.add(null); } else { BigDecimal val = thisWeekMap.getOrDefault(dThis, BigDecimal.ZERO); thisWeekValues.add(val); }
            lastWeekValues.add(lastWeekMap.getOrDefault(dLast, BigDecimal.ZERO));
        }
        Map<String, Object> result = new HashMap<>(); result.put("labels", dayLabels); result.put("thisWeek", thisWeekValues); result.put("lastWeek", lastWeekValues);
        return result;
    }

    public List<Map<String, Object>> getStoreRecentDailyReports(Long storeId) {
        LocalDate today = LocalDate.now(); LocalDate start = today.minusDays(10);
        List<Object[]> rows = groupOrderRepository.findStoreDailyStats(storeId, start.atStartOfDay(), today.plusDays(1).atStartOfDay());
        return rows.stream().map(r -> {
            LocalDate date = r[0] instanceof java.sql.Date ? ((java.sql.Date) r[0]).toLocalDate() : LocalDate.parse(r[0].toString());
            long count = ((Number) r[1]).longValue(); BigDecimal revenue = new BigDecimal(r[2].toString()); BigDecimal aov = count > 0 ? revenue.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            Map<String, Object> m = new HashMap<>(); m.put("date", date.toString()); m.put("revenue", revenue); m.put("orderCount", count); m.put("avgOrderValue", aov);
            return m;
        }).sorted((a, b) -> b.get("date").toString().compareTo(a.get("date").toString())).collect(Collectors.toList());
    }

    public List<Map<String, Object>> getStoreProductRanking(Long storeId, String period) {
        LocalDateTime[] range = getPeriodRange(period);
        List<Object[]> rows = orderItemRepository.findTopProductsByStoreAndPeriod(storeId, range[0], range[1]);
        List<Map<String, Object>> result = new ArrayList<>(); int rank = 1;
        for (Object[] r : rows) { Map<String, Object> m = new HashMap<>(); m.put("rank", rank++); m.put("productName", r[0]); m.put("count", r[1]); m.put("revenue", r[2]); result.add(m); }
        return result;
    }

    public Map<String, Object> getStoreFinanceSummary(Long storeId, String period) {
        LocalDateTime[] range = getPeriodRange(period);
        List<GroupOrder> orders = groupOrderRepository.findByStoreIdAndStatusAndPeriod(storeId, "COMPLETED", range[0], range[1]);
        BigDecimal total = orders.stream().map(GroupOrder::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rate = new BigDecimal("0.30"); BigDecimal commission = total.multiply(rate).setScale(0, RoundingMode.HALF_UP); BigDecimal netIncome = total.subtract(commission);
        Map<String, Object> m = new HashMap<>(); m.put("period", period); m.put("totalRevenue", total); m.put("commission", commission); m.put("netIncome", netIncome); m.put("orderCount", orders.size());
        return m;
    }

    public Map<String, Object> getStoreFinanceCategorized(Long storeId, String period) {
        LocalDateTime[] range = getPeriodRange(period);
        List<Object[]> rows = orderItemRepository.findRevenueByCategory(storeId, range[0], range[1]);
        LocalDateTime prevStart = range[0].minusDays(java.time.Duration.between(range[0], range[1]).toDays() + 1); LocalDateTime prevEnd = range[0].minusNanos(1);
        List<Object[]> prevRows = orderItemRepository.findRevenueByCategory(storeId, prevStart, prevEnd);
        Map<String, BigDecimal> prevMap = new HashMap<>();
        for (Object[] r : prevRows) { prevMap.put(r[0].toString(), new BigDecimal(r[1].toString())); }
        BigDecimal totalRevenue = BigDecimal.ZERO; List<Map<String, Object>> categories = new ArrayList<>();
        for (Object[] r : rows) {
            String name = r[0] != null ? r[0].toString() : "未分類"; BigDecimal rev = new BigDecimal(r[1].toString()); Long orderCount = r[2] != null ? ((Number) r[2]).longValue() : 0L; BigDecimal discount = r[3] != null ? new BigDecimal(r[3].toString()) : BigDecimal.ZERO; totalRevenue = totalRevenue.add(rev);
            BigDecimal prevRev = prevMap.getOrDefault(name, BigDecimal.ZERO); double trend = 0;
            if (prevRev.compareTo(BigDecimal.ZERO) > 0) { trend = rev.subtract(prevRev).divide(prevRev, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")).doubleValue(); }
            Map<String, Object> cat = new HashMap<>(); cat.put("name", name); cat.put("revenue", rev); cat.put("orderCount", orderCount); cat.put("discountAmount", discount); cat.put("trend", trend);
            categories.add(cat);
        }
        BigDecimal rate = new BigDecimal("0.30"); BigDecimal commission = totalRevenue.multiply(rate).setScale(0, RoundingMode.HALF_UP); BigDecimal netIncome = totalRevenue.subtract(commission);
        Map<String, Object> result = new HashMap<>(); result.put("totalRevenue", totalRevenue); result.put("commission", commission); result.put("netIncome", netIncome); result.put("categories", categories);
        return result;
    }

    public List<Map<String, Object>> getStoreCommissionDetails(Long storeId, String period) {
        LocalDateTime[] range = getPeriodRange(period);
        return groupOrderRepository.findByStoreIdAndStatusAndPeriod(storeId, "COMPLETED", range[0], range[1]).stream().map(o -> {
            Map<String, Object> m = new HashMap<>(); m.put("orderId", o.getId()); m.put("orderNo", o.getOrderNo()); m.put("amount", o.getTotalAmount()); m.put("createdAt", o.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    public Map<String, Object> getStoreRatingStats(Long storeId) {
        List<OrderRating> ratings = orderRatingRepository.findByStoreIdOrderByCreatedAtDesc(storeId);
        double avg = ratings.stream().mapToInt(OrderRating::getRating).average().orElse(0.0);
        long total = ratings.size();
        Map<Integer, Long> dist = ratings.stream().collect(Collectors.groupingBy(OrderRating::getRating, Collectors.counting()));
        Map<String, Object> result = new HashMap<>();
        result.put("avgRating", Math.round(avg * 10.0) / 10.0);
        result.put("totalReviews", total);
        result.put("distribution", dist);
        return result;
    }

    private Map<LocalDate, BigDecimal> buildDailyMap(List<Object[]> rows) {
        Map<LocalDate, BigDecimal> map = new HashMap<>();
        for (Object[] row : rows) { if (row[0] == null) continue; LocalDate date = row[0] instanceof java.sql.Date ? ((java.sql.Date) row[0]).toLocalDate() : LocalDate.parse(row[0].toString()); map.put(date, new BigDecimal(row[1].toString())); }
        return map;
    }

    private double calcTrend(BigDecimal current, BigDecimal previous) { if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) return 0; return current.subtract(previous).divide(previous, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue(); }
    private double calcTrendInt(int current, int previous) { return calcTrend(BigDecimal.valueOf(current), BigDecimal.valueOf(previous)); }

    public Map<String, Object> getStoreCategoryTrend(Long storeId, String period) {
        LocalDateTime[] range = "last-week".equals(period) ? getPreviousPeriodRange("week") : getPeriodRange("week");
        List<Object[]> rows = orderItemRepository.findDailyRevenueByCategory(storeId, range[0], range[1]);
        Store store = storeRepository.findById(storeId).orElseThrow(() -> new CustomException("404", "店家不存在"));
        List<MenuCategory> allBrandCategories = menuCategoryRepository.findByBrandId(store.getBrand().getId());
        Set<String> categoryNames = allBrandCategories.stream().map(MenuCategory::getName).collect(Collectors.toSet());
        Map<String, Map<LocalDate, BigDecimal>> categoryMap = new HashMap<>();
        for (Object[] r : rows) { String name = r[0] != null ? r[0].toString() : "未分類"; LocalDate date = r[1] instanceof java.sql.Date ? ((java.sql.Date) r[1]).toLocalDate() : LocalDate.parse(r[1].toString()); BigDecimal rev = new BigDecimal(r[2].toString()); categoryMap.computeIfAbsent(name, k -> new HashMap<>()).put(date, rev); categoryNames.add(name); }
        List<String> labels = new ArrayList<>(Arrays.asList("週一", "週二", "週三", "週四", "週五", "週六", "週日")); LocalDate start = range[0].toLocalDate();
        List<Map<String, Object>> series = new ArrayList<>();
        for (String catName : categoryNames) {
            List<BigDecimal> values = new ArrayList<>();
            for (int i = 0; i < 7; i++) { BigDecimal dailyRev = categoryMap.getOrDefault(catName, Collections.emptyMap()).getOrDefault(start.plusDays(i), BigDecimal.ZERO); values.add(dailyRev); }
            Map<String, Object> s = new HashMap<>(); s.put("name", catName); s.put("data", values); series.add(s);
        }
        Map<String, Object> result = new HashMap<>(); result.put("labels", labels); result.put("series", series);
        return result;
    }

    private LocalDateTime[] getPeriodRange(String period) { LocalDateTime now = LocalDateTime.now(); LocalDateTime start; switch (period.toLowerCase()) { case "week" -> start = now.with(DayOfWeek.MONDAY).toLocalDate().atStartOfDay(); case "quarter" -> start = LocalDate.of(now.getYear(), ((now.getMonthValue()-1)/3)*3+1, 1).atStartOfDay(); case "year" -> start = LocalDate.of(now.getYear(), 1, 1).atStartOfDay(); default -> start = LocalDate.of(now.getYear(), now.getMonth(), 1).atStartOfDay(); } return new LocalDateTime[] { start, now }; }
    private LocalDateTime[] getPreviousPeriodRange(String period) { LocalDateTime now = LocalDateTime.now(); LocalDateTime start, end; switch (period.toLowerCase()) { case "week" -> { end = now.with(DayOfWeek.MONDAY).toLocalDate().atStartOfDay(); start = end.minusWeeks(1); } case "year" -> { start = LocalDate.of(now.getYear()-1, 1, 1).atStartOfDay(); end = LocalDate.of(now.getYear(), 1, 1).atStartOfDay(); } default -> { LocalDate cm = LocalDate.of(now.getYear(), now.getMonth(), 1); end = cm.atStartOfDay(); start = cm.minusMonths(1).atStartOfDay(); } } return new LocalDateTime[] { start, end }; }
    private BigDecimal sumRange(Map<LocalDate, BigDecimal> daily, LocalDate from, LocalDate to) { BigDecimal sum = BigDecimal.ZERO; for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) { sum = sum.add(daily.getOrDefault(d, BigDecimal.ZERO)); } return sum; }
    private Long toLong(Object o) { return o == null ? 0L : ((Number) o).longValue(); }
    private BigDecimal toBigDecimal(Object o) { return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString()); }
}
