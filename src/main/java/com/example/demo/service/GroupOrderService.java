package com.example.demo.service;

import com.example.demo.common.SafeText;
import com.example.demo.dto.GroupOrderDTO;
import com.example.demo.service.order.CouponEligibility;
import com.example.demo.service.order.GroupRefundPolicy;
import com.example.demo.service.order.ItemHash;
import com.example.demo.service.order.ItemSpecResolver;
import com.example.demo.service.order.MemberCancelSettlement;
import com.example.demo.dto.OrderItemDTO;
import com.example.demo.dto.OrderItemToppingDTO;
import com.example.demo.entity.*;
import com.example.demo.exception.CustomException;
import com.example.demo.service.wallet.TxType;
import com.example.demo.repository.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupOrderService {

    private final TransactionRecordService transactionRecordService;
    private final GroupOrderRepository groupOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final TransactionRecordRepository transactionRecordRepository;
    private final com.example.demo.repository.UserRepository userRepository;
    private final com.example.demo.repository.UserCouponRepository userCouponRepository;
    private final com.example.demo.repository.ProductRepository productRepository;
    private final OrderItemToppingRepository orderItemToppingRepository;
    private final com.example.demo.repository.ProductSpecRelationRepository productSpecRelationRepository;
    private final StoreProductStatusRepository storeProductStatusRepository;
    private final StoreRepository storeRepository;
    private final ProductTemplateRepository productTemplateRepository;
    private final CartItemRepository cartItemRepository;
    // ⚠️ H-1/H-2 修復引入：優惠券適用性驗證、以及唯一的品項計價來源
    private final CouponService couponService;
    private final PricingService pricingService;
    // ⚠️ M-5 修復引入：建立揪團的 check-then-insert 序列化鎖
    private final RedisLockService redisLockService;

    private static final List<String> PAYMENT_STATUS_PRIORITY =
            List.of("UNPAID", "ESCROWED", "WAITING_SUBMIT", "PAID", "REFUNDED", "CANCELLED");

    // ============================================================
    // methods (entity-based, aligned with DATABASE.md)
    // ============================================================

    public GroupOrder createGroupOrderV2(Long hostId, Long storeId) {
        // ⚠️ M-5 修復：下面的「查活躍揪團 → 建立新團」是 check-then-insert，
        //    沒有鎖時同一人併發請求會開出多個活躍揪團。
        //    以 token 鎖序列化同一使用者對同一門市的建立動作（雙擊／重試也安全）。
        String lockKey = "lock:group-create:" + hostId + ":" + storeId;
        String lockToken = redisLockService.acquireLock(lockKey, 10);
        if (lockToken == null) {
            throw new CustomException("409", "系統繁忙中，請稍後再試");
        }
        try {
            Optional<GroupOrder> existing = groupOrderRepository.findByInitiatorIdAndStoreIdAndStatusIn(hostId, storeId,
                    List.of("OPEN", "LOCKED"));
            if (existing.isPresent()) {
                throw new RuntimeException("You already have an active group order for this store");
            }
            GroupOrder go = new GroupOrder();
            User host = userRepository.findById(hostId)
                    .orElseThrow(() -> new CustomException("404", "找不到使用者"));
            go.setInitiator(host);

            Store store = storeRepository.findById(storeId)
                    .orElseThrow(() -> new CustomException("404", "找不到店家"));
            validateActiveStore(store);
            go.setStore(store);

            go.setShareToken(UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
            go.setStatus("OPEN");
            go.setType("GROUP");
            go.setOrderNo(OrderService.generateOrderNo());
            go.setAddress("");
            go.setNote("");
            return groupOrderRepository.save(go);
        } finally {
            redisLockService.releaseLock(lockKey, lockToken);
        }
    }

    /**
     * 結帳後通知用的最小資訊（門市 id 與總額）。
     * ⚠️ 控制器不能拿 entity 出去再 getStore()：不在交易內，LAZY 關聯會拋 LazyInitializationException，
     *    而那發生在結帳已 commit 之後，客戶端只會看到 400。
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Map<String, Object> getCheckoutNotifyInfo(String token) {
        GroupOrder go = groupOrderRepository.findByShareToken(token)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
        Map<String, Object> info = new HashMap<>();
        info.put("storeId", go.getStore() != null ? go.getStore().getId() : null);
        info.put("totalAmount", go.getTotalAmount());
        return info;
    }

    public GroupOrder getGroupOrderByToken(String token) {
        return groupOrderRepository.findByShareToken(token)
                .orElseThrow(() -> new RuntimeException("Group order not found"));
    }

    /**
     * 取揪團並鎖住該列。會改品項、改狀態、扣款的流程一律用這個，不用 {@link #getGroupOrderByToken}：
     * 檢查狀態與寫入之間若沒有列鎖，團長結帳或送單可能插在中間（見 GroupItemRulesTest、GroupCheckoutConcurrencyTest）。
     * 鎖的順序固定為「揪團列 → 品項列 → 使用者列」，與結帳、整單取消一致。
     */
    private GroupOrder lockGroupByToken(String token) {
        return groupOrderRepository.findByShareTokenForUpdate(token)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
    }

    /**
     * 取出屬於這個揪團的品項。品項 id 是連續整數，光看「品項的主人是誰」不夠：
     * 同一個人可能同時在 A、B 兩團，拿 B 團的 token 就能動 A 團的品項（N1）。
     * 不屬於這個揪團一律回 404，也不洩漏該品項是否存在。
     */
    private OrderItem requireItemInGroup(GroupOrder go, Long itemId) {
        OrderItem item = orderItemRepository.findById(itemId)
                .orElseThrow(() -> new CustomException("404", "找不到品項"));
        if (item.getGroupOrder() == null || !go.getId().equals(item.getGroupOrder().getId())) {
            throw new CustomException("404", "找不到品項");
        }
        populateToppingIds(item);
        return item;
    }

    /** 品項的實付淨額：單價 × 數量，扣掉券折扣（不為負） */
    private static BigDecimal netOf(OrderItem item) {
        BigDecimal gross = item.getFinalPrice() != null ? item.getFinalPrice() : BigDecimal.ZERO;
        BigDecimal discount = item.getDiscountAmountSnapshot() != null
                ? item.getDiscountAmountSnapshot() : BigDecimal.ZERO;
        return gross.subtract(discount).max(BigDecimal.ZERO);
    }

    // --- DTO Conversion & Grouping Logic ---

    /**
     * 依分享 token 取揪團。
     *
     * <p>團內資料（地址、備註、品項、金額、團長姓名、分享 token）只給參與者：團長與已有品項的團員。
     * 持有連結的非團員（含未登入）只拿到店家、狀態與建立時間；要看內容必須先加入揪團（N12，團長 2026-10-13 定案）。
     */
    public GroupOrderDTO getGroupOrderDTOByToken(String token, Long viewerId) {
        GroupOrder go = getGroupOrderByToken(token);
        if (isParticipant(go, viewerId)) {
            GroupOrderDTO dto = convertToDTO(go);
            dto.setViewerIsMember(true);
            return dto;
        }
        return outsiderView(go);
    }

    /** 參與者 = 團長，或已在團內有品項的團員。未登入者一律不是參與者。 */
    private boolean isParticipant(GroupOrder go, Long viewerId) {
        if (viewerId == null) {
            return false;
        }
        return isInitiator(go, viewerId) || orderItemRepository.existsByGroupOrderIdAndUserId(go.getId(), viewerId);
    }

    /** 非參與者看到的揪團：只有店家、狀態與建立時間，團內欄位一律空白或為 null */
    private GroupOrderDTO outsiderView(GroupOrder go) {
        return GroupOrderDTO.builder()
                .id(go.getId())
                .status(go.getStatus())
                .type(go.getType())
                .storeId(go.getStore() != null ? go.getStore().getId() : null)
                .brandId(go.getStore() != null && go.getStore().getBrand() != null
                        ? go.getStore().getBrand().getId() : null)
                .storeName(go.getStore() != null ? go.getStore().getStoreName() : null)
                .storeLogoUrl(go.getStore() != null && go.getStore().getBrand() != null
                        ? go.getStore().getBrand().getLogoUrl() : "images/logo.png")
                .createdAt(go.getCreatedAt())
                .items(new ArrayList<>())
                .address("")
                .note("")
                .viewerIsMember(false)
                .build();
    }

    public GroupOrderDTO convertToDTO(GroupOrder go) {
        if (go == null)
            return null;
        return convertToDTOList(List.of(go)).get(0);
    }

    public List<GroupOrderDTO> convertToDTOList(List<GroupOrder> orders) {
        if (orders == null || orders.isEmpty())
            return new ArrayList<>();

        // 1. Bulk fetch all OrderItems for all GroupOrders
        List<Long> orderIds = orders.stream().map(GroupOrder::getId).toList();
        List<OrderItem> allItems = orderItemRepository.findByGroupOrderIdIn(orderIds);

        // 2. Group items by GroupOrderId
        Map<Long, List<OrderItem>> itemsByOrderId = allItems.stream()
                .collect(Collectors.groupingBy(oi -> oi.getGroupOrder() != null ? oi.getGroupOrder().getId() : -1L));

        // 3. Bulk fetch all Toppings for all these items
        List<Long> itemIds = allItems.stream().map(OrderItem::getId).toList();
        List<OrderItemTopping> allToppings = orderItemToppingRepository.findByOrderItemIdIn(itemIds);
        Map<Long, List<OrderItemTopping>> toppingsByItemId = allToppings.stream()
                .collect(Collectors.groupingBy(oit -> oit.getOrderItem().getId()));

        // 4. Assemble DTOs
        List<GroupOrderDTO> dtos = new ArrayList<>();
        for (GroupOrder go : orders) {
            List<OrderItem> orderItems = itemsByOrderId.getOrDefault(go.getId(), new ArrayList<>());

            dtos.add(GroupOrderDTO.builder()
                    .id(go.getId())
                    .orderNo(go.getOrderNo())
                    .shareToken(go.getShareToken())
                    .status(go.getStatus())
                    .type(go.getType())
                    .totalAmount(go.getTotalAmount())
                    .escrowAmount(go.getEscrowAmount())
                    .initiatorId(go.getInitiator() != null ? go.getInitiator().getId() : null)
                    .initiatorName(go.getInitiator() != null ? go.getInitiator().getName() : "Unknown")
                    .storeId(go.getStore() != null ? go.getStore().getId() : null)
                    .brandId(
                            go.getStore() != null && go.getStore().getBrand() != null ? go.getStore().getBrand().getId()
                                    : null)
                    .storeName(go.getStore() != null ? go.getStore().getStoreName() : "Unknown")
                    .storeLogoUrl(go.getStore() != null && go.getStore().getBrand() != null
                            ? go.getStore().getBrand().getLogoUrl()
                            : "images/logo.png")
                    .address(go.getAddress())
                    .note(go.getNote())
                    .createdAt(go.getCreatedAt())
                    .items(getGroupedItems(orderItems, toppingsByItemId))
                    .build());
        }
        return dtos;
    }

    private List<OrderItemDTO> getGroupedItems(List<OrderItem> items,
            Map<Long, List<OrderItemTopping>> toppingsByItemId) {
        if (items == null || items.isEmpty())
            return new ArrayList<>();

        Map<String, OrderItemDTO> groupedMap = new LinkedHashMap<>();

        for (OrderItem item : items) {
            // 從 Map 中取得預抓取的配料
            List<OrderItemTopping> toppings = toppingsByItemId.getOrDefault(item.getId(), new ArrayList<>());
            List<OrderItemToppingDTO> toppingDTOs = toppings.stream()
                    .map(oit -> OrderItemToppingDTO.builder()
                            .name(oit.getId().getToppingNameSnapshot())
                            .price(oit.getToppingPriceSnapshot())
                            .build())
                    .toList();

            List<String> toppingNames = toppingDTOs.stream()
                    .map(OrderItemToppingDTO::getName)
                    .sorted()
                    .toList();

            // Grouping key: userId + productId + sugar + ice + size + toppings + couponId
            String key = (item.getUser() != null ? item.getUser().getId() : "null") + "|" +
                    item.getProduct().getId() + "|" +
                    item.getSugarSnapshot() + "|" +
                    item.getIceSnapshot() + "|" +
                    item.getSizeSnapshot() + "|" +
                    String.join(",", toppingNames) + "|" +
                    (item.getCouponId() != null ? item.getCouponId() : "none");

            // 計算含配料的實際單杯單價 (finalPrice / qty)
            BigDecimal unitPriceWithToppings = BigDecimal.ZERO;
            if (item.getFinalPrice() != null) {
                unitPriceWithToppings = item.getFinalPrice().divide(new BigDecimal(Math.max(1, item.getQty())), 2, java.math.RoundingMode.HALF_UP);
            }

            OrderItemDTO dto = groupedMap.getOrDefault(key, OrderItemDTO.builder()
                    .userId(item.getUser() != null ? item.getUser().getId() : null)
                    .userName(item.getUser() != null ? item.getUser().getName() : "Unknown")
                    .productId(item.getProduct().getId())
                    .productName(item.getProductNameSnapshot())
                    .sugar(item.getSugarSnapshot())
                    .ice(item.getIceSnapshot())
                    .size(item.getSizeSnapshot())
                    .unitPrice(item.getUnitPriceSnapshot())
                    .finalPrice(unitPriceWithToppings)
                    .couponId(item.getCouponId())
                    .discountAmount(item.getDiscountAmountSnapshot())
                    .idList(new ArrayList<>())
                    .userNames(new ArrayList<>())
                    .toppings(toppingDTOs)
                    .qty(0)
                    .paymentStatus(item.getPaymentStatus())
                    .imageUrl(item.getProduct() != null ? item.getProduct().getLogoUrl() : "images/logo.png")
                    .build());

            dto.setIdList(dto.getIdList() == null ? new ArrayList<>() : dto.getIdList());
            dto.getIdList().add(item.getId());
            dto.setQty(dto.getQty() + item.getQty());
            if (item.getUser() != null && !dto.getUserNames().contains(item.getUser().getName())) {
                dto.getUserNames().add(item.getUser().getName());
            }

            if (dto.getTotalGroupPrice() == null) dto.setTotalGroupPrice(BigDecimal.ZERO);
            if (dto.getTotalGroupDiscount() == null) dto.setTotalGroupDiscount(BigDecimal.ZERO);

            dto.setTotalGroupPrice(dto.getTotalGroupPrice()
                    .add(item.getFinalPrice() != null ? item.getFinalPrice() : BigDecimal.ZERO));
            dto.setTotalGroupDiscount(dto.getTotalGroupDiscount()
                    .add(item.getDiscountAmountSnapshot() != null ? item.getDiscountAmountSnapshot() : BigDecimal.ZERO));

            // 每次合併後重新計算 DTO 的 finalPrice (加權後的單杯價)
            // finalPrice = totalGroupPrice / qty，用於前端顯示每杯單價
            if (dto.getQty() > 0 && dto.getTotalGroupPrice() != null) {
                dto.setFinalPrice(dto.getTotalGroupPrice().divide(
                        new BigDecimal(dto.getQty()), 2, java.math.RoundingMode.HALF_UP));
            }

            groupedMap.put(key, dto);

            // 狀態優先序合併邏輯
            String currentStatus = dto.getPaymentStatus();
            String newStatus = item.getPaymentStatus() != null ? item.getPaymentStatus() : "UNPAID";
            if (currentStatus == null) {
                dto.setPaymentStatus(newStatus);
            } else {
                int currentIdx = PAYMENT_STATUS_PRIORITY.indexOf(currentStatus.toUpperCase());
                int newIdx = PAYMENT_STATUS_PRIORITY.indexOf(newStatus.toUpperCase());
                // 取優先序較前（更未付款）的狀態：index 越小表示越未付款
                if (newIdx != -1 && (currentIdx == -1 || newIdx < currentIdx)) {
                    dto.setPaymentStatus(newStatus);
                }
            }
        }

        return new ArrayList<>(groupedMap.values());
    }

    @Transactional
    public List<GroupOrder> getActiveGroupOrders(Long userId) {
        return groupOrderRepository.findActiveByUser(userId, List.of("OPEN", "LOCKED", "SUBMITTED", "READY"));
    }

    public GroupOrder getActiveGroupOrder(Long userId, Long storeId) {
        List<GroupOrder> orders = getActiveGroupOrders(userId);
        if (storeId != null) {
            return orders.stream()
                    .filter(o -> o.getStore().getId().equals(storeId))
                    .findFirst()
                    .orElse(null);
        }
        return orders.isEmpty() ? null : orders.get(0);
    }

    @Transactional
    public void deleteGroupOrder(String token, Long hostId) {
        GroupOrder go = lockGroupByToken(token);
        if (!isInitiator(go, hostId)) {
            throw new RuntimeException("Only host can delete this group order");
        }
        // ⚠️ N2：店家接單（PREPARING 以後）之後不可刪除。刪除會連帶退款，店家卻已在製作。
        //    原本這裡沒有狀態守衛，而 handleGroupOrderCancellation 對 PREPARING 也照退不誤。
        if (!GroupRefundPolicy.wholeGroupCancellable(go.getStatus())) {
            throw new CustomException("409", "店家已接單或揪團已結束，無法刪除");
        }

        // 先執行退款（送單後已付款金額入團長帳號，見 GroupRefundPolicy）；
        // handleGroupOrderCancellation 會將 status 設為 CANCELLED 並清零 escrowAmount
        handleGroupOrderCancellation(go.getId());

        // V2 語意為永久刪除，繼續實體移除
        List<OrderItem> items = orderItemRepository.findByGroupOrderId(go.getId());
        orderItemRepository.deleteAll(items);
        groupOrderRepository.delete(go);
    }

    public List<OrderItem> getItems(Long groupOrderId) {
        List<OrderItem> items = orderItemRepository.findByGroupOrderId(groupOrderId);
        items.forEach(this::populateToppingIds);
        return items;
    }

    private void populateToppingIds(OrderItem item) {
        if (item.getId() == null)
            return;
        List<OrderItemTopping> toppings = orderItemToppingRepository.findByOrderItemId(item.getId());
        // 必須使用 clear + addAll 而非 setToppings，
        // 否則 Hibernate 的 orphanRemoval=true 集合會被解除關聯而拋出異常
        if (item.getToppings() == null) {
            item.setToppings(toppings);
        } else {
            item.getToppings().clear();
            item.getToppings().addAll(toppings);
        }
    }

    public OrderItem getItemById(Long itemId) {
        OrderItem item = orderItemRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Item not found"));
        populateToppingIds(item);
        return item;
    }

    public List<OrderItemTopping> getOrderItemToppings(Long orderItemId) {
        return orderItemToppingRepository.findByOrderItemId(orderItemId);
    }

    /**
     * ⚠️ H-1／M-2 修復：
     * <ul>
     *   <li>userId 一律取自 JWT（由 controller 傳入），body 的 userId 不採信——
     *       舊版可把品項掛到他人身上，讓受害者被代墊或被嫁禍。</li>
     *   <li>商品必須屬於揪團門市的品牌。</li>
     *   <li>金額（unitPrice/finalPrice/toppingPrices）一律經 {@link #repriceItem} 由
     *       PricingService 重算，body 內任何金額欄位都不採信
     *       （與 OrderService.repriceItems 同一標準）。</li>
     * </ul>
     */
    @Transactional
    public OrderItem addItem(String token, Map<String, Object> req, Long userId) {
        // 加點與團長結帳互斥：否則結帳讀完品項後才插入的新品項會是 UNPAID 的孤兒
        GroupOrder go = lockGroupByToken(token);
        if (!"OPEN".equalsIgnoreCase(go.getStatus())) {
            throw new RuntimeException(
                    "Group order is " + go.getStatus().toLowerCase() + " and cannot accept more items");
        }
        if (userId == null) {
            throw new CustomException("401", "請先登入");
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new CustomException("404", "找不到用戶"));

        Long productId = Long.parseLong(req.get("productId").toString());
        ProductTemplate pt = productTemplateRepository.findById(productId)
                .orElseThrow(() -> new CustomException("404", "找不到商品 " + productId));
        validateStoreProduct(go.getStore(), pt, getUnavailableProductIds(go.getStore()));

        @SuppressWarnings("unchecked")
        List<Number> toppingIds = (List<Number>) req.getOrDefault("toppingIds", new ArrayList<>());
        List<PricingService.ToppingPrice> resolvedToppings = pricingService.resolveToppingsByIds(pt, toppingIds);

        OrderItem item = new OrderItem();
        item.setGroupOrder(go);
        item.setUser(user);
        item.setProduct(pt);
        item.setProductNameSnapshot(pt.getName());
        // ⚠️ H-1：金額不採信 client，先以底價佔位，儲存前統一 repriceItem 重算
        item.setUnitPriceSnapshot(pt.getBasePrice() != null ? pt.getBasePrice() : BigDecimal.ZERO);
        item.setFinalPrice(BigDecimal.ZERO);
        item.setQty(parseQty(req));
        // --- 固定規格防竄改：規則見 ItemSpecResolver ---
        ItemSpecResolver specs = ItemSpecResolver.of(
                productSpecRelationRepository.findByIdProductId(pt.getId()));
        item.setSugarSnapshot(specs.resolveOrEmpty(
                ItemSpecResolver.SWEETNESS, (String) req.getOrDefault("sugarSnapshot", "")));
        item.setIceSnapshot(specs.resolveOrEmpty(
                ItemSpecResolver.ICE, (String) req.getOrDefault("iceSnapshot", "")));
        item.setSizeSnapshot(specs.resolveOrEmpty(
                ItemSpecResolver.SIZE, (String) req.getOrDefault("sizeSnapshot", "")));
        item.setPaymentStatus("UNPAID"); // 預設未付款
        item.setPaymentType("WALLET");

        // Save first to get an ID for toppings
        item = orderItemRepository.save(item);

        // --- 配料：名稱由 toppingIds 對品牌設定反查，價格一律由伺服器定價 ---
        applyToppings(item, resolvedToppings);

        // --- 金額與 Hash：全部伺服器端重算 ---
        repriceItem(go, item);
        item.setItemHash(generateItemHash(pt.getId(), item.getSugarSnapshot(),
                item.getIceSnapshot(), item.getSizeSnapshot(), toppingsKey(item), item.getCouponId()));

        return orderItemRepository.save(item);
    }

    /** 數量解析：非數字或小於 1 一律視為 1 */
    private static int parseQty(Map<String, Object> req) {
        return QuantityLimits.parse(req.getOrDefault("qty", "1"));
    }

    /** 品項配料的顯示名稱清單（排序後），供定價與 hash 使用 */
    private List<String> toppingNamesOf(OrderItem item) {
        if (item.getToppings() == null || item.getToppings().isEmpty()) {
            return List.of();
        }
        return item.getToppings().stream()
                .map(t -> t.getId().getToppingNameSnapshot())
                .sorted()
                .toList();
    }

    private String toppingsKey(OrderItem item) {
        return String.join(",", toppingNamesOf(item));
    }

    /** Persist the server-resolved product toppings and their configured prices. */
    private void applyToppings(OrderItem item, List<PricingService.ToppingPrice> toppings) {
        if (toppings == null || toppings.isEmpty()) return;
        if (item.getToppings() == null) {
            item.setToppings(new ArrayList<>());
        }
        for (PricingService.ToppingPrice topping : toppings) {
            OrderItemTopping t = new OrderItemTopping();
            OrderItemToppingId oid = new OrderItemToppingId();
            oid.setOrderItemId(item.getId());
            oid.setToppingNameSnapshot(topping.name());
            t.setId(oid);
            t.setOrderItem(item);
            t.setToppingPriceSnapshot(topping.price());
            item.getToppings().add(t);
        }
    }

    /**
     * ⚠️ H-1 核心：品項金額的唯一重算入口。
     * 單價 = {@link PricingService#itemPrice}（底價＋區域加價＋配料加價），
     * 總價 = 單價 × qty。呼叫端送來的 unitPrice/finalPrice 一律覆寫。
     */
    private void repriceItem(GroupOrder go, OrderItem item) {
        item.setQty(QuantityLimits.validate(item.getQty()));
        item.setSizeSnapshot(pricingService.resolveSizeName(item.getProduct(), item.getSizeSnapshot()));
        BigDecimal unit = pricingService.itemPrice(go.getStore(), item.getProduct(), item.getSizeSnapshot(),
                toppingNamesOf(item));
        item.setUnitPriceSnapshot(unit);
        item.setFinalPrice(unit.multiply(BigDecimal.valueOf(item.getQty())));
    }

    private Set<Long> getUnavailableProductIds(Store store) {
        if (store == null || store.getId() == null) {
            throw new CustomException("400", "揪團沒有有效的分店");
        }
        validateActiveStore(store);
        return storeProductStatusRepository.findByStoreId(store.getId()).stream()
                .filter(status -> Boolean.FALSE.equals(status.getIsEnabled()))
                .map(status -> status.getId().getProductId())
                .collect(Collectors.toSet());
    }

    private void validateActiveStore(Store store) {
        if (store == null || !"active".equalsIgnoreCase(store.getStatus())) {
            throw new CustomException("400", "分店目前不接受新訂單");
        }
        if (store.getBrand() == null || store.getBrand().getId() == null) {
            throw new CustomException("400", "分店未關聯有效品牌");
        }
    }

    private void validateStoreProduct(Store store, ProductTemplate product, Set<Long> unavailableProductIds) {
        validateActiveStore(store);
        if (product == null || product.getBrand() == null || product.getBrand().getId() == null
                || !store.getBrand().getId().equals(product.getBrand().getId())) {
            throw new CustomException("400", "商品不屬於該分店品牌");
        }
        if (!Boolean.TRUE.equals(product.getIsEnabled()) || unavailableProductIds.contains(product.getId())) {
            throw new CustomException("400", "此商品目前未在該分店供應");
        }
    }

    @Transactional
    public OrderItem updateItem(String token, Long itemId, Map<String, Object> req, Long userId) {
        GroupOrder go = lockGroupByToken(token);
        if (!"OPEN".equalsIgnoreCase(go.getStatus())) {
            throw new RuntimeException("Group order is " + go.getStatus().toLowerCase() + " and cannot be edited");
        }
        OrderItem item = requireItemInGroup(go, itemId);
        boolean isHost = isInitiator(go, userId);
        boolean isOwner = item.getUser() != null && userId != null && userId.equals(item.getUser().getId());
        if (!isHost && !isOwner) {
            throw new RuntimeException("Permission denied");
        }
        if ("PAID".equalsIgnoreCase(item.getPaymentStatus())) {
            throw new RuntimeException("Cannot edit a paid item. Please contact host for refund/removal.");
        }

        // --- Update Snapshots ---
        // ⚠️ H-1 修復：qty 只改數量，金額最後統一 repriceItem 重算；
        //    body 的 finalPrice／unitPriceSnapshot 一律不採信（舊版可任意改價）。
        if (req.containsKey("qty")) {
            item.setQty(parseQty(req));
        }
        // --- 固定規格防竄改：規則見 ItemSpecResolver ---
        // 與 addItem 的差別只在「沒帶這個欄位就不動」，規則本身共用同一份
        ItemSpecResolver specs = ItemSpecResolver.of(
                productSpecRelationRepository.findByIdProductId(item.getProduct().getId()));
        if (req.containsKey("sugarSnapshot")) {
            item.setSugarSnapshot(specs.resolve(ItemSpecResolver.SWEETNESS, (String) req.get("sugarSnapshot")));
        }
        if (req.containsKey("iceSnapshot")) {
            item.setIceSnapshot(specs.resolve(ItemSpecResolver.ICE, (String) req.get("iceSnapshot")));
        }
        if (req.containsKey("sizeSnapshot")) {
            item.setSizeSnapshot(specs.resolve(ItemSpecResolver.SIZE, (String) req.get("sizeSnapshot")));
        }
        // ⚠️ H-1 修復：移除「接受 client 傳入 unitPriceSnapshot／finalPrice」的分支——
        // 舊版等於任何人都能把自己品項改成任意價格。

        // --- Process idList to clean up grouped duplicates ---
        // ⚠️ H-4 修復：idList 是 client 可控的，舊版直接 deleteAllById 等於
        //    「知道 itemId 就能刪任何人的品項」，且繞過 PAID 不可刪與退款邏輯。
        //    現在只允許刪「同一揪團、本人、未付款」的合併副本，其餘一律略過；
        //    若副本上有券，先還原再刪。
        if (req.containsKey("idList")) {
            @SuppressWarnings("unchecked")
            List<Number> idList = (List<Number>) req.get("idList");
            if (idList != null && idList.size() > 1) {
                List<Long> requestedIds = idList.stream()
                        .map(Number::longValue)
                        .filter(id -> !id.equals(item.getId()))
                        .collect(Collectors.toList());
                if (!requestedIds.isEmpty()) {
                    List<OrderItem> deletable = orderItemRepository.findAllById(requestedIds).stream()
                            .filter(d -> d.getGroupOrder() != null && go.getId().equals(d.getGroupOrder().getId()))
                            .filter(d -> d.getUser() != null && d.getUser().getId().equals(userId))
                            .filter(d -> !"PAID".equalsIgnoreCase(d.getPaymentStatus()))
                            .toList();
                    for (OrderItem d : deletable) {
                        if (d.getCouponId() != null) {
                            restoreUserCoupon(d.getUser().getId(), d.getCouponId());
                        }
                    }
                    if (!deletable.isEmpty()) {
                        orderItemRepository.deleteAll(deletable);
                    }
                }
            }
        }

        // --- Handle Toppings ---
        // ⚠️ H-1 修復：名稱／價格改由 toppingIds 對品牌設定反查，不吃 client 清單
        if (req.containsKey("toppingIds")) {
            // 1. 清除舊配料 (透過 Hibernate orphanRemoval)
            if (item.getToppings() != null) {
                item.getToppings().clear();
            } else {
                item.setToppings(new ArrayList<>());
            }

            // 2. 反查品牌設定後新增
            @SuppressWarnings("unchecked")
            List<Number> toppingIds = (List<Number>) req.get("toppingIds");
            applyToppings(item, pricingService.resolveToppingsByIds(item.getProduct(), toppingIds));
        }

        // --- 金額與 Hash：伺服器端重算 ---
        repriceItem(go, item);
        item.setItemHash(generateItemHash(item.getProduct().getId(), item.getSugarSnapshot(),
                item.getIceSnapshot(), item.getSizeSnapshot(), toppingsKey(item), item.getCouponId()));

        return orderItemRepository.save(item);
    }

    /**
     * 移除一筆品項（DELETE /api/group-orders/{token}/items/{itemId}）。
     *
     * <ul>
     *   <li>團員只能移除自己的品項，且只限送單前（OPEN）。送單後一律由團長處理。</li>
     *   <li>團長可移除任一成員的品項：送單前照常退款給團員錢包（錢包付款）；送單後已付款金額入團長帳號。</li>
     *   <li>品項必須屬於這個揪團（見 {@link #requireItemInGroup}）。</li>
     * </ul>
     */
    @Transactional
    public void removeItem(String token, Long itemId, Long userId) {
        GroupOrder go = lockGroupByToken(token);
        OrderItem item = requireItemInGroup(go, itemId);
        boolean isHost = isInitiator(go, userId);
        boolean isOwner = item.getUser() != null && userId != null && userId.equals(item.getUser().getId());
        if (!isHost && !isOwner) {
            throw new CustomException("403", "無權限移除此品項");
        }
        if (isHost) {
            if (!GroupRefundPolicy.hostMayRemoveItems(go.getStatus())) {
                throw new CustomException("409", "揪團目前狀態為 " + go.getStatus() + "，無法移除品項");
            }
            // 團長自己以錢包結帳的品項已併入代墊金，單獨移除會讓代墊金與品項對不上，請走整單取消
            if ("WAITING_SUBMIT".equalsIgnoreCase(item.getPaymentStatus())) {
                throw new CustomException("409", "團長已付款的品項無法單獨移除，請使用整單取消");
            }
        } else if (!GroupRefundPolicy.memberMaySelfCancel(go.getStatus())) {
            throw new CustomException("409", memberCancelRefusal(go.getStatus()));
        }
        removeItemsAndSettle(go, List.of(item));
        groupOrderRepository.save(go);
    }

    /** 團員無法自行取消時回給使用者的原因（送單後以外的狀態各有不同的說法） */
    private static String memberCancelRefusal(String groupStatus) {
        GroupRefundPolicy.Stage stage = GroupRefundPolicy.stageOf(groupStatus);
        return switch (stage) {
            case SUBMITTED, FULFILLING -> "團長已送單，無法自行取消並退款，請洽團長處理";
            case LOCKED -> "團長已截單，無法自行取消品項，請洽團長處理";
            default -> "揪團已結束，無法取消品項";
        };
    }

    /**
     * 移除一批品項並結算金額（團員自行取消與團長移除共用）。呼叫端必須已持有揪團列鎖。
     *
     * <ul>
     *   <li><b>已付款品項</b>：依 {@link GroupRefundPolicy#paidRefundTo} 決定去向——
     *       送單前錢包付款退團員錢包；現金、信用卡與送單後的款項一律入團長帳號。</li>
     *   <li><b>團長代墊品項（ESCROWED）</b>：退給團長，且以 escrowAmount 為上限（{@link MemberCancelSettlement}）。</li>
     *   <li><b>優惠券</b>：一律還原給原持有人。</li>
     *   <li><b>總額</b>：送單前沿用加點時的毛額口徑；送單後以淨額扣回，與結帳的 finalAmount 一致。</li>
     * </ul>
     */
    private void removeItemsAndSettle(GroupOrder go, List<OrderItem> items) {
        GroupRefundPolicy.Stage stage = GroupRefundPolicy.stageOf(go.getStatus());
        Long hostId = go.getInitiator() != null ? go.getInitiator().getId() : null;
        LocalDateTime now = LocalDateTime.now();

        // 1) 已付款品項
        Map<Long, BigDecimal> refundToMembers = new LinkedHashMap<>();
        BigDecimal refundToHost = BigDecimal.ZERO;
        for (OrderItem item : items) {
            if (!"PAID".equalsIgnoreCase(item.getPaymentStatus())) {
                continue;
            }
            GroupRefundPolicy.PaidRefundTo to = GroupRefundPolicy.paidRefundTo(stage, item.getPaymentType());
            if (to == GroupRefundPolicy.PaidRefundTo.HOST_WALLET && hostId == null) {
                to = GroupRefundPolicy.PaidRefundTo.NONE;
            }
            if (to == GroupRefundPolicy.PaidRefundTo.MEMBER_WALLET && item.getUser() == null) {
                to = GroupRefundPolicy.PaidRefundTo.NONE;
            }
            BigDecimal net = netOf(item);
            if (to == GroupRefundPolicy.PaidRefundTo.MEMBER_WALLET) {
                refundToMembers.merge(item.getUser().getId(), net, BigDecimal::add);
            } else if (to == GroupRefundPolicy.PaidRefundTo.HOST_WALLET) {
                refundToHost = refundToHost.add(net);
            }
        }
        for (Map.Entry<Long, BigDecimal> entry : refundToMembers.entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                transactionRecordService.updateStoreCredit(entry.getKey(), entry.getValue(),
                        TxType.REFUND, "揪團單品取消退款（已付品項，退回錢包）", now);
            }
        }
        if (refundToHost.compareTo(BigDecimal.ZERO) > 0) {
            transactionRecordService.updateStoreCredit(hostId, refundToHost,
                    TxType.REFUND, "揪團品項退款（團員已付款，入團長帳號）", now);
        }

        // 2) 團長代墊品項：以 escrowAmount 為上限
        List<MemberCancelSettlement.Item> settlementItems = items.stream()
                .map(i -> MemberCancelSettlement.Item.of(i.getPaymentStatus(), i.getFinalPrice(),
                        i.getDiscountAmountSnapshot()))
                .toList();
        MemberCancelSettlement.Result settlement = MemberCancelSettlement.settle(settlementItems, go.getEscrowAmount());
        if (settlement.hostRefund().compareTo(BigDecimal.ZERO) > 0 && hostId != null) {
            transactionRecordService.updateStoreCredit(hostId, settlement.hostRefund(),
                    TxType.REFUND, "揪團團員取消品項退款代墊金", now);
        }
        go.setEscrowAmount(settlement.remainingEscrow());

        // 3) 優惠券還原給原持有人（與有沒有退款無關）
        for (OrderItem item : items) {
            if (item.getCouponId() != null && item.getUser() != null) {
                restoreUserCoupon(item.getUser().getId(), item.getCouponId());
            }
        }

        // 4) 總額：送單後以淨額扣回（與結帳口徑一致）；送單前維持原本的毛額口徑
        BigDecimal delta = BigDecimal.ZERO;
        for (OrderItem item : items) {
            BigDecimal amount = stage == GroupRefundPolicy.Stage.SUBMITTED
                    ? netOf(item)
                    : (item.getFinalPrice() != null ? item.getFinalPrice() : BigDecimal.ZERO);
            delta = delta.add(amount);
        }
        BigDecimal currentTotal = go.getTotalAmount() != null ? go.getTotalAmount() : BigDecimal.ZERO;
        go.setTotalAmount(currentTotal.subtract(delta).max(BigDecimal.ZERO));

        orderItemRepository.deleteAll(items);
    }

    @Transactional
    public GroupOrder setStatus(String token, String status, Long hostId) {
        GroupOrder go = lockGroupByToken(token);
        if (!isInitiator(go, hostId)) {
            // ⚠️ H-3 修復：訊息改為 403 語意；呼叫端身分已由 JWT 保證，
            //    不再存在「帶上真團長 id 就能通過」的路徑
            throw new CustomException("403", "只有團長可以變更揪團狀態");
        }
        // ⚠️ H-3 修復：status 白名單。截單／重開只有這兩種是合法入口；
        //    SUBMITTED 走 submitGroupOrder、CANCELLED 走 cancelGroupOrder。
        if (status == null || !Set.of("OPEN", "LOCKED").contains(status.toUpperCase())) {
            throw new CustomException("400", "不支援的狀態：" + status);
        }
        // ⚠️ N3：原本只檢查「要改成什麼」，沒看目前是什麼。送單後的揪團被改回 OPEN，
        //    就能再結帳一次，同一批品項重複扣款。截單與重開只在送單前有意義。
        if (!GroupRefundPolicy.mayToggleLock(go.getStatus())) {
            throw new CustomException("409", "揪團目前狀態為 " + go.getStatus() + "，無法變更為 " + status.toUpperCase());
        }
        go.setStatus(status.toUpperCase());
        return groupOrderRepository.save(go);
    }

    @Transactional
    public Long checkout(String token, Long hostId, Long couponId, String paymentMethod,
            String address, String note) {
        // ⚠️ 必須鎖住這一列再檢查狀態。原本兩者都沒有，團長雙擊送出就會重複結帳，
        // 實測 8 個併發請求全部成功、團長被扣 8 次（見 GroupCheckoutConcurrencyTest）。
        GroupOrder go = groupOrderRepository.findByShareTokenForUpdate(token)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
        if (!go.getInitiator().getId().equals(hostId)) {
            throw new RuntimeException("Only host can checkout");
        }
        if (!"OPEN".equalsIgnoreCase(go.getStatus()) && !"LOCKED".equalsIgnoreCase(go.getStatus())) {
            throw new CustomException("409", "此揪團已結帳，請勿重複送出");
        }
        // 品項也要鎖住：團員同時付款時，若這裡讀到的仍是 UNPAID，團員的錢已扣、品項卻又被改成代墊，等於收了兩次
        List<OrderItem> items = orderItemRepository.findByGroupOrderIdForUpdate(go.getId());
        items.forEach(this::populateToppingIds);
        if (items.isEmpty()) {
            throw new RuntimeException("Cart is empty");
        }

        // Recompute from the catalogue at checkout so legacy or stale rows cannot alter the total.
        Set<Long> unavailableProductIds = getUnavailableProductIds(go.getStore());
        items.forEach(item -> {
            validateStoreProduct(go.getStore(), item.getProduct(), unavailableProductIds);
            repriceItem(go, item);
        });
        orderItemRepository.saveAll(items);

        BigDecimal fullGrossTotal = items.stream()
                .map(OrderItem::getFinalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ⚠️ H-2 修復：整單券必須驗「未逾期＋適用此店/品」，並用資料庫原子 UPDATE
        //    消耗（WHERE id + owner + status='unused'）。舊版 findById 就打折，
        //    couponId 又是連續整數——實測可列舉盜用「他人」的未用券折抵自己的結帳。
        //    規則來源與 applyCouponToItem 的 CouponEligibility／markUsedIfUnused 同一套。
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (couponId != null) {
            List<Long> couponProductIds = items.stream()
                    .map(i -> i.getProduct() != null ? i.getProduct().getId() : null)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (!couponService.isValidForStore(couponId, go.getStore().getId(), couponProductIds)) {
                throw new CustomException("400", "無效的優惠券，或該優惠券不適用於此店家與飲品");
            }
            UserCoupon userCoupon = userCouponRepository.findById(couponId)
                    .orElseThrow(() -> new RuntimeException("Coupon not found"));
            // 先取折扣值：markUsedIfUnused(@Modifying clearAutomatically) 會清空一級快取
            discountAmount = userCoupon.getDiscountAmount() != null ? userCoupon.getDiscountAmount()
                    : BigDecimal.ZERO;
            if (userCouponRepository.markUsedIfUnused(couponId, hostId, LocalDateTime.now()) == 0) {
                throw new CustomException("409", "此優惠券已被使用或不屬於你");
            }
            // ⚠️ M-4 修復：把整單券記在訂單上，取消／拒單時才能精準還原
            //    （見 handleGroupOrderCancellation；舊版靠 ±5 秒時間戳反查）
            go.setCheckoutCouponId(couponId);
        }

        BigDecimal totalItemDiscount = items.stream()
                .map(i -> i.getDiscountAmountSnapshot() != null ? i.getDiscountAmountSnapshot() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal finalAmount = fullGrossTotal.subtract(discountAmount).subtract(totalItemDiscount);
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0)
            finalAmount = BigDecimal.ZERO;

        BigDecimal totalMemberPaid = items.stream()
                .filter(i -> "PAID".equalsIgnoreCase(i.getPaymentStatus()))
                .map(i -> {
                    BigDecimal iGross = i.getFinalPrice();
                    BigDecimal iDisc = i.getDiscountAmountSnapshot() != null ? i.getDiscountAmountSnapshot()
                            : BigDecimal.ZERO;
                    return iGross.subtract(iDisc).max(BigDecimal.ZERO);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal amountToCharge = finalAmount.subtract(totalMemberPaid);
        if (amountToCharge.compareTo(BigDecimal.ZERO) < 0)
            amountToCharge = BigDecimal.ZERO;

        go.setTotalAmount(finalAmount);
        go.setAddress(address != null ? address : "");
        go.setNote(note != null ? note : "");
        // 只有錢包付款才會從團長錢包扣款，現金／信用卡不留待退金額（見 GroupRefundPolicy.escrowAfterCheckout）
        go.setEscrowAmount(GroupRefundPolicy.escrowAfterCheckout(paymentMethod, amountToCharge));

        if ("WALLET".equals(paymentMethod)) {
            transactionRecordService.updateStoreCredit(hostId, amountToCharge.negate(),
                    TxType.PAYMENT, "揪團結帳扣款 (已扣除團員已付部分)", LocalDateTime.now());
        }

        List<OrderItem> itemsToSave = new ArrayList<>();
        for (OrderItem item : items) {
            if (!"PAID".equalsIgnoreCase(item.getPaymentStatus())) {
                if (item.getCouponId() != null && !item.getCouponId().equals(couponId)) {
                    markCouponAsUsed(item.getCouponId());
                }
                if (item.getUser().getId().equals(hostId)) {
                    item.setPaymentStatus("WALLET".equals(paymentMethod) ? "WAITING_SUBMIT" : "UNPAID");
                    item.setPaymentType(paymentMethod);
                    itemsToSave.add(item);
                } else {
                    // 非團長的未付款品項，標記為由團長代墊 (ESCROWED)
                    item.setPaymentStatus("ESCROWED");
                    itemsToSave.add(item);
                }
            }
        }
        if (!itemsToSave.isEmpty()) {
            orderItemRepository.saveAll(itemsToSave);
        }

        go.setStatus("SUBMITTED");
        go.setSubmittedAt(LocalDateTime.now());
        groupOrderRepository.save(go);

        return go.getId();
    }

    @Transactional
    public BigDecimal getMemberUnpaidTotalAndMarkPaid(String token, Long userId, String paymentMethod, Long couponId) {
        GroupOrder go = getGroupOrderByToken(token);
        if (!"OPEN".equals(go.getStatus()) && !"LOCKED".equals(go.getStatus())) {
            throw new RuntimeException("Group order is closed");
        }

        // ⚠️ 必須用有列鎖的查詢，不可讀出全部再用 stream 過濾：
        // 這裡是「讀出未付款品項 → 扣款 → 標記 PAID」的 read-modify-write，
        // 沒有鎖時同一批品項會被併發請求重複扣款（見 GroupCheckoutConcurrencyTest）。
        List<OrderItem> memberItems = orderItemRepository
                .findByGroupOrderAndUserAndStatusForUpdate(go.getId(), userId, List.of("UNPAID"));

        if (memberItems.isEmpty()) {
            throw new RuntimeException("No unpaid items found for user.");
        }

        BigDecimal totalAmount = memberItems.stream()
                .map(i -> i.getFinalPrice()
                        .subtract(
                                i.getDiscountAmountSnapshot() != null ? i.getDiscountAmountSnapshot() : BigDecimal.ZERO)
                        .max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ⚠️ H-2 修復：同團長結帳——券必須驗適用性並原子消耗，且擁有者必須是
        //    「正在付款的這位團員」（userId 來自 JWT），不得盜用他人券。
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (couponId != null) {
            List<Long> couponProductIds = memberItems.stream()
                    .map(i -> i.getProduct() != null ? i.getProduct().getId() : null)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (!couponService.isValidForStore(couponId, go.getStore().getId(), couponProductIds)) {
                throw new CustomException("400", "無效的優惠券，或該優惠券不適用於此店家與飲品");
            }
            UserCoupon userCoupon = userCouponRepository.findById(couponId)
                    .orElseThrow(() -> new RuntimeException("Coupon not found"));
            discountAmount = userCoupon.getDiscountAmount() != null ? userCoupon.getDiscountAmount()
                    : BigDecimal.ZERO;
            if (userCouponRepository.markUsedIfUnused(couponId, userId, LocalDateTime.now()) == 0) {
                throw new CustomException("409", "此優惠券已被使用或不屬於你");
            }
        }

        BigDecimal finalAmount = totalAmount.subtract(discountAmount).max(BigDecimal.ZERO);

        if ("WALLET".equals(paymentMethod)) {
            transactionRecordService.updateStoreCredit(userId, finalAmount.negate(),
                    TxType.PAYMENT, "揪團個人品項結帳扣款", LocalDateTime.now());
        }

        BigDecimal distributedDiscount = BigDecimal.ZERO;
        for (int i = 0; i < memberItems.size(); i++) {
            OrderItem item = memberItems.get(i);
            item.setPaymentStatus("PAID");
            item.setPaymentType(paymentMethod);
            if (discountAmount.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal share;
                if (i == memberItems.size() - 1) {
                    share = discountAmount.subtract(distributedDiscount);
                } else {
                    share = discountAmount.divide(new BigDecimal(memberItems.size()), 2,
                            java.math.RoundingMode.HALF_UP);
                    distributedDiscount = distributedDiscount.add(share);
                }
                item.setDiscountAmountSnapshot(
                        (item.getDiscountAmountSnapshot() != null ? item.getDiscountAmountSnapshot() : BigDecimal.ZERO)
                                .add(share));
            }
        }
        orderItemRepository.saveAll(memberItems);

        return finalAmount;
    }

    @Transactional
    public boolean handleGroupOrderCancellation(Long realOrderId) {
        // ⚠️ 先鎖住這一列再判斷狀態。原本兩者都沒有，重複觸發取消會重複退款——
        // 實測 8 個併發取消，團長的 escrow 退了 280（應該只退 35）。
        Optional<GroupOrder> goOpt = groupOrderRepository.findByIdForUpdate(realOrderId);
        if (goOpt.isEmpty())
            return false;
        GroupOrder go = goOpt.get();
        // 這道守衛是第二層保險：真正擋住重複退款的是上面的列鎖
        // （實測只拿掉列鎖、留著這道守衛，escrow 仍會退 140 而不是 35——
        //  因為沒有鎖時讀到的狀態本身就是舊的）。留著是為了讓意圖明確。
        // ⚠️ 取得列鎖後必須再檢查狀態（CLAUDE.md 不變量）。只有開放中、截單中與已送單可整單取消；
        //    店家接單（PREPARING 以後）之後退款會變成店家已在製作、款項卻已退回（N2）。
        if (false) {
            return false;
        }
        GroupRefundPolicy.Stage stage = GroupRefundPolicy.stageOf(go.getStatus());
        Long hostId = go.getInitiator() != null ? go.getInitiator().getId() : null;
        List<OrderItem> items = orderItemRepository.findByGroupOrderIdForUpdate(go.getId());

        // 1. 各品項退款、狀態鎖定與優惠券還原
        for (OrderItem item : items) {
            // ⚠️ 舊版 /api/orders/checkout 只在用戶端有送 item.userId 時才綁使用者，
            // 沒送就留下 user_id = NULL 的品項。那種品項沒有對象可退款，
            // 直接改狀態帶過——原本會在這裡 NPE，讓門市後台按「拒單」固定 500。
            if (item.getUser() == null) {
                item.setPaymentStatus("CANCELLED");
                continue;
            }
            // 優惠券一律還原給原持有人，與付款狀態無關（2026-10-19）：結帳時券已標為 used，
            // 但 ESCROWED、WAITING_SUBMIT、UNPAID 的品項原本從不還原，整單取消後團員的券就消失了。
            // 與 removeItemsAndSettle 的步驟 3 一致。restoreUserCoupon 只把 used 改回 unused，重複呼叫無害。
            if (item.getCouponId() != null) {
                restoreUserCoupon(item.getUser().getId(), item.getCouponId());
            }
            // 只有 PAID 且未退款的品項才處理
            if ("PAID".equalsIgnoreCase(item.getPaymentStatus())) {
                // 退款去向依規則：送單前錢包付款退團員、現金與信用卡不入帳；送單後入團長帳號（見 GroupRefundPolicy）。
                // 個人訂單（SOLO）的付款人就是發起人，維持原行為：退回付款人錢包。
                BigDecimal refundAmount = netOf(item);
                GroupRefundPolicy.PaidRefundTo to = "GROUP".equalsIgnoreCase(go.getType())
                        ? GroupRefundPolicy.paidRefundTo(stage, item.getPaymentType())
                        : GroupRefundPolicy.PaidRefundTo.MEMBER_WALLET;
                if (to == GroupRefundPolicy.PaidRefundTo.HOST_WALLET && hostId == null) {
                    to = GroupRefundPolicy.PaidRefundTo.NONE;
                }
                
                switch (to) {
                    case MEMBER_WALLET -> {
                        transactionRecordService.updateStoreCredit(item.getUser().getId(), refundAmount,
                                TxType.REFUND, "揪團取消退款 (品項: " + item.getProductNameSnapshot() + ")", LocalDateTime.now());
                        item.setPaymentStatus("REFUNDED");
                    }
                    case HOST_WALLET -> {
                        transactionRecordService.updateStoreCredit(hostId, refundAmount,
                                TxType.REFUND, "揪團取消退款，團員已付款金額入團長帳號（送單後，或現金、信用卡） (品項: " + item.getProductNameSnapshot() + ")",
                                LocalDateTime.now());
                        item.setPaymentStatus("REFUNDED");
                    }
                    case NONE -> item.setPaymentStatus("CANCELLED"); // 付款方式未記錄，無從判斷退款去向：不入帳（見 GroupRefundPolicy.paidRefundTo）
                }

            } else if ("WAITING_SUBMIT".equalsIgnoreCase(item.getPaymentStatus())) {
                // ⚠️ 不逐品項退款。WAITING_SUBMIT 的款項在結帳時已整筆併入 escrowAmount（OrderService.createOrder
                // 與 checkout），由下方步驟 2 統一退還。原本 SOLO 這裡又退一次品項金額：一杯 $35 取消後退 $70；
                // 用券折抵時，折抵前的金額也全退。escrowAmount 為 0（例如券已全額折抵）時本來就沒有款項可退。
                // 揪團原本就是這樣處理，SOLO 與 GROUP 現在一致（2026-10-19 修正，見 CLAUDE.md）。
                item.setPaymentStatus("CANCELLED");
            } else if ("ESCROWED".equalsIgnoreCase(item.getPaymentStatus())) {
                // 團長代墊品項：成員未實際付款，取消時僅改狀態
                // 團長退款由 escrowAmount 統一退還，此處不重複處理
                item.setPaymentStatus("CANCELLED");
            }
        }
        orderItemRepository.saveAll(items);

        // 2. 揪團整單差額退金 (Escrow Amount)
        // 只有當 escrowAmount > 0 且訂單非 OPEN (表示已結帳扣款) 時退還
        if (go.getEscrowAmount() != null && go.getEscrowAmount().compareTo(BigDecimal.ZERO) > 0) {
            Long initiatorId = go.getInitiator() != null ? go.getInitiator().getId() : null;
            if (initiatorId != null) {
                transactionRecordService.updateStoreCredit(initiatorId, go.getEscrowAmount(),
                        TxType.REFUND, "揪團差額扣款退還 (訂單 #" + go.getId() + ")", LocalDateTime.now());
                go.setEscrowAmount(BigDecimal.ZERO); // 清零防止重複退
            }
        }

        // 步驟 3：還原揪團整單優惠券
        if (go.getInitiator() != null) {
            // 收集所有品項層級已使用的 couponId，排除在外
            java.util.Set<Long> itemCouponIds = items.stream()
                    .map(OrderItem::getCouponId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());

            // excludedIds 不可為空集合，以 -1L 代替
            java.util.Collection<Long> excludedIds = itemCouponIds.isEmpty() ? List.of(-1L) : itemCouponIds;

            // ⚠️ M-4 修復：優先使用結帳時記錄在訂單上的券 ID 精準還原。
            //    舊版只用「submittedAt ±5 秒」反查，同時段多筆交易時可能還原到別張券；
            //    舊資料（checkoutCouponId 為 null）才回退到原本的時間戳猜測。
            boolean checkoutCouponRestored = false;
            Long recordedCouponId = go.getCheckoutCouponId();
            if (recordedCouponId != null && !excludedIds.contains(recordedCouponId)) {
                userCouponRepository.findById(recordedCouponId)
                        .filter(uc -> uc.getUser() != null && go.getInitiator().getId().equals(uc.getUser().getId()))
                        .filter(uc -> "used".equals(uc.getStatus()))
                        .ifPresent(uc -> {
                            uc.setStatus("unused");
                            uc.setUsedAt(null);
                            userCouponRepository.save(uc);
                        });
                // 已有精準記錄就不再走時間戳猜測（找不到／已還原過都視為處理完成）
                checkoutCouponRestored = true;
            }

            if (!checkoutCouponRestored && go.getSubmittedAt() != null) {
                // 查詢結帳時間前後 5 秒內被標記 used 的非品項券（舊資料回退路徑）
                LocalDateTime from = go.getSubmittedAt().minusSeconds(5);
                LocalDateTime to = go.getSubmittedAt().plusSeconds(5);

                List<UserCoupon> candidates = userCouponRepository.findCheckoutLevelCoupon(go.getInitiator().getId(), from, to,
                        excludedIds);

                for (UserCoupon uc : candidates) {
                    uc.setStatus("unused");
                    uc.setUsedAt(null);
                    userCouponRepository.save(uc);
                }
            }
        }

        go.setStatus("CANCELLED");
        go.setCancelledOrRejectedAt(LocalDateTime.now());
        groupOrderRepository.save(go);
        return true;
    }

    /**
     * 團長從訂單頁取消整張揪團（店家接單前皆可，含截單中）（舊端點 POST /api/orders/{orderId}/cancel 走這裡）。
     * ⚠️ 原本 OrderService.cancelOrder 只把狀態改成 CANCELLED：不退款、不還原優惠券，也沒有列鎖。
     *    同一張單從 /cancel/v2 取消會退款，從這個入口取消卻沒退，等於錢與券都憑空消失。
     *    改為與 /cancel/v2 相同的 handleGroupOrderCancellation（列鎖、退款、券還原）。
     */
    @Transactional
    public void cancelAsInitiator(Long userId, Long orderId) {
        GroupOrder go = groupOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new CustomException("404", "Order not found"));
        if (go.getInitiator() == null || !java.util.Objects.equals(go.getInitiator().getId(), userId)) {
            throw new CustomException("403", "You cannot cancel this order");
        }
        if (!GroupRefundPolicy.wholeGroupCancellable(go.getStatus())) {
            throw new CustomException("409", "Only groups the store has not accepted can be cancelled");
        }
        if (!handleGroupOrderCancellation(orderId)) {
            throw new CustomException("409", "取消失敗");
        }
    }

    /**
     * 團員取消自己的品項（PUT /api/orders/{orderId}/cancel/v2，非發起人才會走到這裡）。
     *
     * <p>只限送單前（OPEN）。送單後不論品項是否已付款，團員都不可自行取消並退款，
     * 一律由團長操作，退款入團長帳號（2026-10-13 團長定案，見 {@link GroupRefundPolicy}）。
     */
    @Transactional
    public void cancelMemberItems(Long realOrderId, Long userId) {
        // ⚠️ M-2 修復：必須先鎖住揪團那一列再讀品項（與結帳、整單取消同一把鎖），
        //    否則「讀出已付款品項 → 退款 → 刪品項」在併發時會對同一批品項各退一次。
        GroupOrder go = groupOrderRepository.findByIdForUpdate(realOrderId)
                .orElseThrow(() -> new RuntimeException("Group order not found"));
        // ⚠️ 狀態守衛必須在鎖內再做一次：控制器的檢查發生在取得鎖之前，期間團長可能已結帳或送單。
        if (!GroupRefundPolicy.memberMaySelfCancel(go.getStatus())) {
            throw new CustomException("409", memberCancelRefusal(go.getStatus()));
        }

        List<OrderItem> allItems = orderItemRepository.findByGroupOrderIdForUpdate(go.getId());
        List<OrderItem> memberItems = allItems.stream()
                .filter(i -> i.getUser() != null && i.getUser().getId().equals(userId))
                .toList();

        if (memberItems.isEmpty()) {
            throw new RuntimeException("No items found for this user to cancel");
        }

        removeItemsAndSettle(go, memberItems);
        groupOrderRepository.save(go);
    }

    public Optional<GroupOrder> getGroupOrderByOrderId(Long realOrderId) {
        return groupOrderRepository.findById(realOrderId);
    }

    /**
     * 依訂單 ID 取揪團 DTO（訂單完成頁會打）。
     *
     * ⚠️ 轉 DTO 一定要留在這個交易裡。原本是 Controller 拿 Optional&lt;GroupOrder&gt; 出去、
     * 在交易外才呼叫 convertToDTO，而 convertToDTO 會讀 initiator／store，
     * open-in-view=false 之下必定 LazyInitializationException——實測下單後的
     * 訂單完成頁固定看到 500。
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<GroupOrderDTO> getGroupOrderDTOByOrderId(Long realOrderId, Long userId) {
        return groupOrderRepository.findById(realOrderId)
                .filter(order -> isInitiator(order, userId))
                .map(this::convertToDTO);
    }

    /**
     * 團員補款給團長。
     *
     * 修正說明：原本要求狀態為 CLOSED（系統從未設定此狀態），
     * 改為允許 SUBMITTED / PREPARING / READY / COMPLETED，
     * 亦即揪團送單後皆可補款，符合實際業務流程。
     */
    @Transactional
    public void repayToHost(String token, Long userId) {
        GroupOrder go = getGroupOrderByToken(token);

        // 不允許團長自己補款給自己
        if (userId.equals(go.getInitiator().getId())) {
            throw new RuntimeException("Host does not need to repay themselves.");
        }

        // 修正：送單後任何進行中或已完成狀態皆可補款
        // 原邏輯要求 CLOSED，但系統從未產生此狀態，故調整如下
        List<String> repayableStatuses = List.of("SUBMITTED", "PREPARING", "READY", "COMPLETED");
        if (!repayableStatuses.contains(go.getStatus())) {
            throw new RuntimeException(
                    "補款僅限訂單已送出後進行（目前狀態：" + go.getStatus() + "）");
        }

        // ⚠️ 必須用鎖定讀，理由同團員結帳：這裡也是「讀出未付款品項 → 扣款 → 標記 PAID」，
        // 沒有鎖時併發補款會把團員扣好幾次、團長也收好幾次
        // （實測 8 個併發補款扣了 175，應該只扣 35）。
        List<OrderItem> memberItems = orderItemRepository
                .findByGroupOrderAndUserAndStatusForUpdate(go.getId(), userId, List.of("UNPAID", "ESCROWED"));

        if (memberItems.isEmpty()) {
            throw new RuntimeException("No unpaid items found for this user.");
        }

        BigDecimal totalAmount = memberItems.stream()
                .map(i -> i.getFinalPrice()
                        .subtract(
                                i.getDiscountAmountSnapshot() != null ? i.getDiscountAmountSnapshot() : BigDecimal.ZERO)
                        .max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 從團員錢包扣款
        transactionRecordService.updateStoreCredit(userId, totalAmount.negate(),
                TxType.REPAYMENT, "揪團轉付給團長 (補款)", LocalDateTime.now());

        // 存入團長錢包
        String userName = userRepository.findById(userId)
                .map(com.example.demo.entity.User::getName)
                .orElse("未知");
        transactionRecordService.updateStoreCredit(go.getInitiator().getId(), totalAmount,
                TxType.REPAYMENT_RECEIVED, "收到團員補款 (團員名稱: " + userName + ", 團員ID: " + userId + ")", LocalDateTime.now());

        // 標記品項為已付款
        for (OrderItem item : memberItems) {
            item.setPaymentStatus("PAID");
        }
        orderItemRepository.saveAll(memberItems);
    }

    @Transactional
    public void applyCouponToItem(String token, Long itemId, Long userId, Long couponId) {
        GroupOrder go = lockGroupByToken(token);
        if (!"OPEN".equals(go.getStatus()) && !"LOCKED".equals(go.getStatus())) {
            throw new RuntimeException("Group order is closed");
        }
        OrderItem item = requireItemInGroup(go, itemId);
        if (item.getUser() == null || !item.getUser().getId().equals(userId)) {
            throw new RuntimeException("Permission denied: You can only apply coupons to your own items");
        }
        CouponEligibility.requireUnpaid(item.getPaymentStatus());

        // 1. 還原舊優惠券 (若原本有套用)
        if (item.getCouponId() != null) {
            restoreUserCoupon(item.getUser().getId(), item.getCouponId());
        }

        // 2. 如果是移除優惠券
        if (couponId == null) {
            item.setCouponId(null);
            item.setDiscountAmountSnapshot(BigDecimal.ZERO);
            orderItemRepository.save(item);
            return;
        }

        // 3. 讀取並驗證新優惠券
        UserCoupon userCoupon = userCouponRepository.findById(couponId)
                .orElseThrow(() -> new RuntimeException("Coupon not found"));

        // 適用範圍（品牌／指定商品）規則見 CouponEligibility
        CouponEligibility.check(userCoupon, go.getStore().getBrand().getId(), item.getProduct().getId());

        // ⚠️ 先原子地消耗這張券，再去動品項。
        // 原本是最後才呼叫 markCouponAsUsed（先 findById 檢查 status 再 save），
        // 那是 read-check-write：兩個品項同時套同一張券時兩邊都會過，
        // 實測一張券折了兩個品項。改成把條件交給資料庫的 UPDATE ... WHERE status='unused'，
        // 受影響列數為 0 就代表已被用掉，直接擋下。
        // userId 一併帶進 WHERE：不是自己的券，受影響列數就是 0（見 repository 的說明）
        if (userCouponRepository.markUsedIfUnused(couponId, userId, LocalDateTime.now()) == 0) {
            throw new CustomException("409", "此優惠券已被使用或不屬於你");
        }

        // 4. 實作數量拆分 (Qty Splitting)
        // 如果 Qty > 1，則拆出 1 單位來套用優惠券，其餘維持原樣
        if (item.getQty() > 1) {
            // 建立新的 OrderItem (Qty=1)
            OrderItem couponedItem = new OrderItem();
            couponedItem.setGroupOrder(go);
            couponedItem.setUser(item.getUser());
            couponedItem.setProduct(item.getProduct());
            couponedItem.setProductNameSnapshot(item.getProductNameSnapshot());
            couponedItem.setUnitPriceSnapshot(item.getUnitPriceSnapshot());
            BigDecimal unitPrice = item.getFinalPrice()
                    .divide(new BigDecimal(item.getQty()), 2, java.math.RoundingMode.HALF_UP);
            couponedItem.setFinalPrice(unitPrice);
            couponedItem.setSugarSnapshot(item.getSugarSnapshot());
            couponedItem.setIceSnapshot(item.getIceSnapshot());
            couponedItem.setSizeSnapshot(item.getSizeSnapshot());
            couponedItem.setPaymentStatus(item.getPaymentStatus());
            couponedItem.setPaymentType(item.getPaymentType());
            couponedItem.setQty(1);
            couponedItem.setCouponId(couponId);
            couponedItem.setDiscountAmountSnapshot(userCoupon.getDiscountAmount());

            // 重新計算 ItemHash (因為多了 couponId，Hash 會不同，確保前端分開顯示)
            String toppingsStr = "";
            List<OrderItemTopping> originalToppings = orderItemToppingRepository.findByOrderItemId(item.getId());
            if (!originalToppings.isEmpty()) {
                toppingsStr = originalToppings.stream()
                        .map(t -> t.getId().getToppingNameSnapshot())
                        .sorted()
                        .collect(Collectors.joining(","));
            }
            String newHash = generateItemHash(item.getProduct().getId(), item.getSugarSnapshot(),
                    item.getIceSnapshot(), item.getSizeSnapshot(), toppingsStr, couponId);
            couponedItem.setItemHash(newHash);

            OrderItem savedCouponItem = orderItemRepository.save(couponedItem);

            // 複製配料 (Toppings)
            for (OrderItemTopping oit : originalToppings) {
                OrderItemTopping newTopping = new OrderItemTopping();
                OrderItemToppingId newId = new OrderItemToppingId();
                newId.setOrderItemId(savedCouponItem.getId());
                newId.setToppingNameSnapshot(oit.getId().getToppingNameSnapshot());
                newTopping.setId(newId);
                newTopping.setOrderItem(savedCouponItem);
                newTopping.setToppingPriceSnapshot(oit.getToppingPriceSnapshot());
                orderItemToppingRepository.save(newTopping);
            }

            // 原始品項數量減 1
            item.setQty(item.getQty() - 1);
            item.setFinalPrice(unitPrice.multiply(new BigDecimal(item.getQty()))
                    .setScale(2, java.math.RoundingMode.HALF_UP));
            orderItemRepository.save(item);
        } else {
            // Qty = 1, 直接套用
            item.setCouponId(couponId);
            item.setDiscountAmountSnapshot(userCoupon.getDiscountAmount());

            // 更新 Hash 以反映 couponId 變化 (避免與其他未套用券的品項合併)
            List<OrderItemTopping> toppings = orderItemToppingRepository.findByOrderItemId(item.getId());
            String toppingsStr = toppings.stream()
                    .map(t -> t.getId().getToppingNameSnapshot())
                    .sorted()
                    .collect(Collectors.joining(","));
            item.setItemHash(generateItemHash(item.getProduct().getId(), item.getSugarSnapshot(),
                    item.getIceSnapshot(), item.getSizeSnapshot(), toppingsStr, couponId));

            orderItemRepository.save(item);
        }
    }

    private String generateItemHash(Long productId, String sugar, String ice, String size, String toppings,
            Long couponId) {
        return ItemHash.of(productId, sugar, ice, size, toppings, couponId);
    }

    // ─── Coupon helpers ────────────────────────────────────────
    private void markCouponAsUsed(Long userCouponId) {
        userCouponRepository.findById(userCouponId).ifPresent(uc -> {
            if ("unused".equals(uc.getStatus())) {
                uc.setStatus("used");
                uc.setUsedAt(LocalDateTime.now());
                userCouponRepository.save(uc);
            }
        });
    }

    private void restoreUserCoupon(Long userId, Long userCouponId) {
        userCouponRepository.findById(userCouponId).ifPresent(uc -> {
            if (uc.getUser().getId().equals(userId) && "used".equals(uc.getStatus())) {
                uc.setStatus("unused");
                uc.setUsedAt(null);
                userCouponRepository.save(uc);
            }
        });
    }

    // ============================================================
    // Map-based API methods
    // ============================================================

    private static final String BASE_URL = "https://join-drink.app/group/join/";

    @Transactional
    public Map<String, Object> createGroupOrder(Long userId, Map<String, Object> req) {
        User user = userRepository.findById(userId).orElseThrow(() -> new CustomException("404", "找不到用戶"));
        Long storeId = Long.parseLong(req.get("storeId").toString());
        com.example.demo.entity.Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException("404", "找不到店家"));
        Set<Long> unavailableProductIds = getUnavailableProductIds(store);
        String type = (String) req.getOrDefault("type", "GROUP");

        GroupOrder order = new GroupOrder();
        order.setInitiator(user);
        order.setStore(store);
        order.setType(type);
        order.setStatus("OPEN");
        order.setOrderNo(OrderService.generateOrderNo());
        order.setShareToken(generateToken());
        order.setAddress("");
        order.setNote("");
        
        // --- 品項轉移邏輯 (Bug Fix: Initiator items missing) ---
        // 尋找該使用者在該分店是否有既存的個人購物車 (SOLO 訂單)
        Optional<GroupOrder> soloOpt = groupOrderRepository.findByInitiatorIdAndStoreIdAndTypeAndStatusIn(
            userId, storeId, "SOLO", List.of("OPEN", "LOCKED"));

        BigDecimal migratedTotal = BigDecimal.ZERO;
        int migratedItemsCount = 0;
        List<OrderItem> itemsToMigrate = new ArrayList<>();

        if (soloOpt.isPresent()) {
            GroupOrder soloOrder = soloOpt.get();
            itemsToMigrate = orderItemRepository.findByGroupOrderId(soloOrder.getId());

            // 先儲存新訂單以取得 ID
            groupOrderRepository.save(order);

            for (OrderItem item : itemsToMigrate) {
                validateStoreProduct(store, item.getProduct(), unavailableProductIds);
                item.setGroupOrder(order); // 將品項重新指向新揪團
                item.setPaymentStatus("UNPAID"); // 確保轉移後為未付款狀態
                repriceItem(order, item);
                if (item.getFinalPrice() != null) {
                    migratedTotal = migratedTotal.add(item.getFinalPrice());
                }
                migratedItemsCount++;
            }
            orderItemRepository.saveAll(itemsToMigrate);
            
            // 刪除舊的廢棄 SOLO 訂單
            groupOrderRepository.delete(soloOrder);
        }

        // --- 核心變更：移轉 CartItem 品項 ---
        List<CartItem> cartItems = cartItemRepository.findByUserIdAndStoreId(userId, storeId);
        if (!cartItems.isEmpty()) {
            if (order.getId() == null) groupOrderRepository.save(order);
            
            for (CartItem ci : cartItems) {
                int quantity = QuantityLimits.validate(ci.getQuantity() != null ? ci.getQuantity() : 1);
                ProductTemplate product = ci.getProduct();
                validateStoreProduct(store, product, unavailableProductIds);
                String size = pricingService.resolveSizeName(product, ci.getSizeSnapshot());
                List<String> toppingNames = ci.getToppingNames() == null || ci.getToppingNames().isBlank()
                        ? List.of()
                        : Arrays.stream(ci.getToppingNames().split(",")).map(String::trim)
                                .filter(name -> !name.isEmpty()).toList();
                List<PricingService.ToppingPrice> resolvedToppings = pricingService.resolveToppings(product,
                        toppingNames);
                BigDecimal unitPrice = pricingService.unitPrice(store, product, size);
                BigDecimal toppingExtra = resolvedToppings.stream().map(PricingService.ToppingPrice::price)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                OrderItem oi = new OrderItem();
                oi.setGroupOrder(order);
                oi.setUser(user);
                oi.setProduct(product);
                oi.setProductNameSnapshot(product.getName());
                oi.setUnitPriceSnapshot(unitPrice.add(toppingExtra));
                oi.setFinalPrice(unitPrice.add(toppingExtra).multiply(BigDecimal.valueOf(quantity)));
                oi.setQty(quantity);
                oi.setSugarSnapshot(ci.getSugarSnapshot());
                oi.setIceSnapshot(ci.getIceSnapshot());
                oi.setSizeSnapshot(size);
                oi.setPaymentStatus("UNPAID");
                oi.setPaymentType("WALLET"); // 建立揪團時預設錢包支付，結帳時可再覆寫

                // 計算 Hash (與 GroupOrderService 其他部分一致)
                String toppingsKey = resolvedToppings.stream().map(PricingService.ToppingPrice::name)
                        .sorted().collect(java.util.stream.Collectors.joining(","));
                oi.setItemHash(generateItemHash(product.getId(), ci.getSugarSnapshot(),
                        ci.getIceSnapshot(), size, toppingsKey, null));

                OrderItem savedOi = orderItemRepository.save(oi);
                List<OrderItemTopping> toppingRows = resolvedToppings.stream().map(topping -> {
                    OrderItemTopping row = new OrderItemTopping();
                    OrderItemToppingId id = new OrderItemToppingId();
                    id.setOrderItemId(savedOi.getId());
                    id.setToppingNameSnapshot(topping.name());
                    row.setId(id);
                    row.setOrderItem(savedOi);
                    row.setToppingPriceSnapshot(topping.price());
                    return row;
                }).toList();
                orderItemToppingRepository.saveAll(toppingRows);
                if (savedOi.getFinalPrice() != null) {
                    migratedTotal = migratedTotal.add(savedOi.getFinalPrice());
                }
                migratedItemsCount++;
            }
            // 移轉後清空該店家的購物車
            cartItemRepository.deleteAll(cartItems);
        }

        order.setTotalAmount(migratedTotal);
        groupOrderRepository.save(order);
        if (!itemsToMigrate.isEmpty()) {
            orderItemRepository.saveAll(itemsToMigrate);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("groupOrderId", order.getId());
        result.put("orderNo", order.getOrderNo());
        result.put("shareToken", order.getShareToken());
        result.put("joinUrl", BASE_URL + order.getShareToken());
        result.put("type", order.getType());
        result.put("status", order.getStatus());
        result.put("migratedItemsCount", migratedItemsCount);
        return result;
    }

    @Transactional
    public Map<String, Object> joinGroup(Long userId, Long groupOrderId, Map<String, Object> req) {
        // 加點與結帳互斥（同 addItem）
        GroupOrder order = groupOrderRepository.findByIdForUpdate(groupOrderId)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
        Object requestedToken = req != null ? req.get("shareToken") : null;
        if (!(requestedToken instanceof String token) || !token.equals(order.getShareToken())) {
            throw new CustomException("403", "邀請憑證無效");
        }
        if (!"OPEN".equals(order.getStatus()))
            throw new CustomException("409", "此揪團已結束，無法加入");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) req.get("items");
        if (items != null) {
            addItemsToOrder(userId, order, items);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("groupOrderId", order.getId());
        result.put("message", "已成功加入揪團");
        return result;
    }

    // readOnly 交易不可移除：open-in-view=false，這裡用 findById 取單（沒有 JOIN FETCH），
    // 之後會讀 order.getStore() 與每個品項的 item.getUser()，交易外一律 LazyInitializationException。
    // 這支是揪團「誰點了什麼」的清單，壞掉等於揪團功能的核心頁面打不開。
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Map<String, Object> getGroupDetail(Long userId, Long groupOrderId) {
        GroupOrder order = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
        requireGroupParticipant(order, userId);
        List<OrderItem> allItems = orderItemRepository.findByGroupOrderId(groupOrderId);

        Map<Long, Map<String, Object>> memberMap = new LinkedHashMap<>();
        for (OrderItem item : allItems) {
            // 舊資料可能有 user_id = NULL 的品項（見 handleGroupOrderCancellation），不可直接 getId()
            Long uid = item.getUser() != null ? item.getUser().getId() : null;
            memberMap.computeIfAbsent(uid, k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("userId", uid);
                m.put("userName", item.getUser() != null ? item.getUser().getName() : "未綁定成員");
                m.put("items", new ArrayList<>());
                m.put("subtotal", BigDecimal.ZERO);
                return m;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> memberItems = (List<Map<String, Object>>) memberMap.get(uid).get("items");
            Map<String, Object> i = new HashMap<>();
            i.put("productName", item.getProductNameSnapshot());
            i.put("sugar", item.getSugarSnapshot());
            i.put("ice", item.getIceSnapshot());
            i.put("finalPrice", item.getFinalPrice());
            i.put("paymentStatus", item.getPaymentStatus());
            memberItems.add(i);
            BigDecimal prev = (BigDecimal) memberMap.get(uid).get("subtotal");
            memberMap.get(uid).put("subtotal",
                    prev.add(item.getFinalPrice() != null ? item.getFinalPrice() : BigDecimal.ZERO));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("groupOrderId", order.getId());
        result.put("orderNo", order.getOrderNo());
        result.put("storeName", order.getStore().getStoreName());
        result.put("note", order.getNote());
        result.put("status", order.getStatus());
        result.put("totalAmount", order.getTotalAmount());
        result.put("members", new ArrayList<>(memberMap.values()));
        return result;
    }

    // 同 getGroupDetail：會讀每個品項的 item.getUser()。
    // 空團剛好不會走到那段，所以「沒有交易」這件事在空團上看不出來——有品項才會爆。
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Map<String, Object> getGroupSummary(Long userId, Long groupOrderId) {
        GroupOrder order = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
        requireGroupParticipant(order, userId);
        List<OrderItem> items = orderItemRepository.findByGroupOrderId(groupOrderId);
        BigDecimal paid = items.stream()
                .filter(i -> "PAID".equals(i.getPaymentStatus()))
                .map(i -> i.getFinalPrice() != null ? i.getFinalPrice() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;

        Map<String, Object> result = new HashMap<>();
        result.put("groupOrderId", groupOrderId);
        result.put("totalAmount", total);
        result.put("paidAmount", paid);
        result.put("unpaidAmount", total.subtract(paid));
        result.put("memberCount", items.stream().filter(i -> i.getUser() != null)
                .map(i -> i.getUser().getId()).distinct().count());
        return result;
    }

    public Map<String, Object> getShareInfo(Long userId, Long groupOrderId) {
        GroupOrder order = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
        if (!isInitiator(order, userId)) throw new CustomException("403", "只有團長可以取得分享連結");
        String joinUrl = BASE_URL + order.getShareToken();
        Map<String, Object> result = new HashMap<>();
        result.put("groupOrderId", groupOrderId);
        result.put("shareToken", order.getShareToken());
        result.put("joinUrl", joinUrl);
        result.put("qrCodeUrl", "https://api.qrserver.com/v1/create-qr-code/?size=200x200&data="
                + java.net.URLEncoder.encode(joinUrl, java.nio.charset.StandardCharsets.UTF_8));
        return result;
    }

    private void requireGroupParticipant(GroupOrder order, Long userId) {
        if (isInitiator(order, userId)) return;
        if (userId == null || !orderItemRepository.existsByGroupOrderIdAndUserId(order.getId(), userId)) {
            throw new CustomException("403", "無權限查看此揪團");
        }
    }

    private boolean isInitiator(GroupOrder order, Long userId) {
        return userId != null && order.getInitiator() != null
                && userId.equals(order.getInitiator().getId());
    }

    @Transactional
    public Map<String, Object> submitGroupOrder(Long userId, Long groupOrderId, Map<String, Object> req) {
        GroupOrder order = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
        if (!order.getInitiator().getId().equals(userId))
            throw new CustomException("403", "只有團長可以送單");
        if (!"OPEN".equals(order.getStatus()))
            throw new CustomException("409", "訂單狀態不允許送單");

        Set<Long> unavailableProductIds = getUnavailableProductIds(order.getStore());
        for (OrderItem item : orderItemRepository.findByGroupOrderId(groupOrderId)) {
            validateStoreProduct(order.getStore(), item.getProduct(), unavailableProductIds);
        }

        if (req != null && req.containsKey("note")) {
            String noteVal = (String) req.get("note");
            order.setNote(noteVal != null ? noteVal : "");
        } else if (order.getNote() == null) {
            order.setNote("");
        }
        order.setStatus("SUBMITTED");
        order.setSubmittedAt(LocalDateTime.now(ZoneId.of("Asia/Taipei")));
        groupOrderRepository.save(order);

        Map<String, Object> result = new HashMap<>();
        result.put("groupOrderId", order.getId());
        result.put("status", order.getStatus());
        result.put("message", "已成功送單至店家");
        return result;
    }

    @Transactional
    public void cancelGroupOrder(Long userId, Long groupOrderId) {
        GroupOrder order = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
        if (!order.getInitiator().getId().equals(userId))
            throw new CustomException("403", "只有團長可以取消揪團");
        if (!"OPEN".equals(order.getStatus()))
            throw new CustomException("409", "訂單已送出，無法取消");

        handleGroupOrderCancellation(groupOrderId);
    }

    // ─── private helpers ──────────────────────────────────────
    private void addItemsToOrder(Long userId, GroupOrder order, List<Map<String, Object>> items) {
        User user = userRepository.findById(userId).orElseThrow(() -> new CustomException("404", "找不到用戶"));
        BigDecimal orderTotal = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        Set<Long> unavailableProductIds = getUnavailableProductIds(order.getStore());
        for (Map<String, Object> req : items) {
            Long productId = Long.parseLong(req.get("productId").toString());
            ProductTemplate product = productTemplateRepository.findById(productId)
                    .orElseThrow(() -> new CustomException("404", "找不到商品 " + productId));
            validateStoreProduct(order.getStore(), product, unavailableProductIds);

            int qty = QuantityLimits.parse(req.getOrDefault("qty", "1"));
            String size = pricingService.resolveSizeName(product,
                    SafeText.normalizeLabel((String) req.get("size"), 50));
            List<String> toppingNames = new ArrayList<>();
            Object requestedToppings = req.get("toppingNames");
            if (requestedToppings instanceof List<?> values) {
                for (Object value : values) toppingNames.add(String.valueOf(value));
            } else if (requestedToppings != null) {
                throw new CustomException("400", "配料格式錯誤");
            }
            List<PricingService.ToppingPrice> toppingPrices = pricingService.resolveToppings(product, toppingNames);
            BigDecimal unitPrice = pricingService.unitPrice(order.getStore(), product, size);
            BigDecimal toppingExtra = toppingPrices.stream().map(PricingService.ToppingPrice::price)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            OrderItem item = new OrderItem();
            item.setGroupOrder(order);
            item.setUser(user);
            item.setProduct(product);
            item.setSugarSnapshot(SafeText.normalizeLabel((String) req.getOrDefault("sugar", ""), 50));
            item.setIceSnapshot(SafeText.normalizeLabel((String) req.getOrDefault("ice", ""), 50));
            item.setSizeSnapshot(size);
            item.setQty(qty);
            item.setPaymentStatus("UNPAID");
            item.setPaymentType((String) req.getOrDefault("paymentType", "WALLET"));
            item.setProductNameSnapshot(product.getName());
            item.setUnitPriceSnapshot(unitPrice.add(toppingExtra));
            item.setFinalPrice(unitPrice.add(toppingExtra).multiply(BigDecimal.valueOf(qty)));
            OrderItem savedItem = orderItemRepository.save(item);

            List<OrderItemTopping> toppingRows = toppingPrices.stream().map(topping -> {
                OrderItemTopping row = new OrderItemTopping();
                OrderItemToppingId id = new OrderItemToppingId();
                id.setOrderItemId(savedItem.getId());
                id.setToppingNameSnapshot(topping.name());
                row.setId(id);
                row.setOrderItem(savedItem);
                row.setToppingPriceSnapshot(topping.price());
                return row;
            }).toList();
            orderItemToppingRepository.saveAll(toppingRows);
            orderTotal = orderTotal.add(item.getFinalPrice());
        }
        order.setTotalAmount(orderTotal);
        groupOrderRepository.save(order);
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
