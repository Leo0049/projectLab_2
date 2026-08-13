package com.example.demo.service;

import com.example.demo.dto.GroupOrderDTO;
import com.example.demo.service.order.CouponEligibility;
import com.example.demo.service.order.ItemHash;
import com.example.demo.service.order.ItemSpecResolver;
import com.example.demo.dto.OrderItemDTO;
import com.example.demo.dto.OrderItemToppingDTO;
import com.example.demo.entity.*;
import com.example.demo.exception.CustomException;
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
    private final com.example.demo.repository.BrandToppingSettingRepository brandToppingSettingRepository;
    private final StoreRepository storeRepository;
    private final ProductTemplateRepository productTemplateRepository;
    private final CartItemRepository cartItemRepository;

    private static final List<String> PAYMENT_STATUS_PRIORITY =
            List.of("UNPAID", "ESCROWED", "WAITING_SUBMIT", "PAID", "REFUNDED", "CANCELLED");

    // ============================================================
    // methods (entity-based, aligned with DATABASE.md)
    // ============================================================

    public GroupOrder createGroupOrderV2(Long hostId, Long storeId) {
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
        go.setStore(store);

        go.setShareToken(UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        go.setStatus("OPEN");
        go.setType("GROUP");
        go.setOrderNo(OrderService.generateOrderNo());
        go.setAddress("");
        go.setNote("");
        return groupOrderRepository.save(go);
    }

    public GroupOrder getGroupOrderByToken(String token) {
        return groupOrderRepository.findByShareToken(token)
                .orElseThrow(() -> new RuntimeException("Group order not found"));
    }

    // --- DTO Conversion & Grouping Logic ---

    public GroupOrderDTO getGroupOrderDTOByToken(String token) {
        GroupOrder go = getGroupOrderByToken(token);
        return convertToDTO(go);
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
        GroupOrder go = getGroupOrderByToken(token);
        if (!go.getInitiator().getId().equals(hostId)) {
            throw new RuntimeException("Only host can delete this group order");
        }
        if ("CLOSED".equals(go.getStatus())) {
            throw new RuntimeException("Cannot delete a closed group order");
        }

        // 先執行退款：PAID 品項退給成員，escrowAmount 退給團長，ESCROWED 僅改狀態
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

    @Transactional
    public OrderItem addItem(String token, Map<String, Object> req) {
        GroupOrder go = getGroupOrderByToken(token);
        if (!"OPEN".equalsIgnoreCase(go.getStatus())) {
            throw new RuntimeException(
                    "Group order is " + go.getStatus().toLowerCase() + " and cannot accept more items");
        }

        Long userId = Long.parseLong(req.get("userId").toString());
        User user = userRepository.findById(userId).orElseThrow(() -> new CustomException("404", "找不到用戶"));

        Long productId = Long.parseLong(req.get("productId").toString());
        ProductTemplate pt = productTemplateRepository.findById(productId)
                .orElseThrow(() -> new CustomException("404", "找不到商品 " + productId));

        // --- Topping Count Validation ---
        @SuppressWarnings("unchecked")
        List<Number> toppingIds = (List<Number>) req.getOrDefault("toppingIds", new ArrayList<>());
        int max = pt.getMaxToppings() != null ? pt.getMaxToppings() : 3;
        if (toppingIds.size() > max) {
            throw new RuntimeException("該商品最多只能選擇 " + max + " 種配料");
        }

        OrderItem item = new OrderItem();
        item.setGroupOrder(go);
        item.setUser(user);
        item.setProduct(pt);
        item.setProductNameSnapshot(pt.getName());
        item.setUnitPriceSnapshot(new BigDecimal(req.getOrDefault("unitPrice", pt.getBasePrice()).toString()));
        item.setFinalPrice(new BigDecimal(req.getOrDefault("finalPrice", item.getUnitPriceSnapshot()).toString()));
        item.setQty(Integer.parseInt(req.getOrDefault("qty", "1").toString()));
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

        // --- Handle Toppings ---
        if (!toppingIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<String> toppingNames = (List<String>) req.getOrDefault("toppingNames", new ArrayList<>());
            @SuppressWarnings("unchecked")
            List<Number> toppingPrices = (List<Number>) req.getOrDefault("toppingPrices", new ArrayList<>());

            if (item.getToppings() == null) {
                item.setToppings(new ArrayList<>());
            }

            for (int i = 0; i < toppingIds.size(); i++) {
                OrderItemTopping t = new OrderItemTopping();
                OrderItemToppingId id = new OrderItemToppingId();
                id.setOrderItemId(item.getId());

                String tName = (i < toppingNames.size()) ? toppingNames.get(i) : "配料#" + toppingIds.get(i);
                id.setToppingNameSnapshot(tName);
                t.setId(id);
                t.setOrderItem(item);

                BigDecimal tPrice = BigDecimal.ZERO;
                if (i < toppingPrices.size()) {
                    tPrice = new BigDecimal(toppingPrices.get(i).toString());
                }
                t.setToppingPriceSnapshot(tPrice);
                item.getToppings().add(t);
            }
        }

        // --- Recalculate Hash ---
        String toppingsStr = "";
        if (item.getToppings() != null && !item.getToppings().isEmpty()) {
            toppingsStr = item.getToppings().stream()
                    .map(t -> t.getId().getToppingNameSnapshot())
                    .sorted()
                    .collect(Collectors.joining(","));
        }
        item.setItemHash(generateItemHash(item.getProduct().getId(), item.getSugarSnapshot(),
                item.getIceSnapshot(), item.getSizeSnapshot(), toppingsStr, item.getCouponId()));

        return orderItemRepository.save(item);
    }

    @Transactional
    public OrderItem updateItem(String token, Long itemId, Map<String, Object> req, Long userId) {
        GroupOrder go = getGroupOrderByToken(token);
        if (!"OPEN".equalsIgnoreCase(go.getStatus())) {
            throw new RuntimeException("Group order is " + go.getStatus().toLowerCase() + " and cannot be edited");
        }
        OrderItem item = getItemById(itemId);
        if (!go.getInitiator().getId().equals(userId) && !item.getUser().getId().equals(userId)) {
            throw new RuntimeException("Permission denied");
        }
        if ("PAID".equalsIgnoreCase(item.getPaymentStatus())) {
            throw new RuntimeException("Cannot edit a paid item. Please contact host for refund/removal.");
        }

        // --- Update Snapshots ---
        if (req.containsKey("qty")) {
            int newQty = Integer.parseInt(req.get("qty").toString());
            // 當數量更新時，若請求中未包含新的總價，用「含配料的實際單杯價」重算
            // 必須在 setQty 之前讀取舊的 qty，否則會用新 qty 來除
            if (!req.containsKey("finalPrice") && item.getFinalPrice() != null && item.getQty() > 0) {
                // 實際單杯價 = 當前 finalPrice ÷ 當前 qty（已含配料加價）
                BigDecimal actualUnitPrice = item.getFinalPrice()
                        .divide(new BigDecimal(item.getQty()), 4, java.math.RoundingMode.HALF_UP);
                item.setFinalPrice(actualUnitPrice.multiply(new BigDecimal(newQty))
                        .setScale(2, java.math.RoundingMode.HALF_UP));
            } else if (!req.containsKey("finalPrice") && item.getUnitPriceSnapshot() != null) {
                // Fallback：若 finalPrice 為 null，才改用 unitPriceSnapshot
                item.setFinalPrice(item.getUnitPriceSnapshot().multiply(new BigDecimal(newQty)));
            }
            item.setQty(newQty);
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
        if (req.containsKey("unitPriceSnapshot")) {
            item.setUnitPriceSnapshot(new BigDecimal(req.get("unitPriceSnapshot").toString()));
        }
        if (req.containsKey("finalPrice")) {
            item.setFinalPrice(new BigDecimal(req.get("finalPrice").toString()));
        }

        // --- Process idList to clean up grouped duplicates ---
        if (req.containsKey("idList")) {
            @SuppressWarnings("unchecked")
            List<Number> idList = (List<Number>) req.get("idList");
            if (idList != null && idList.size() > 1) {
                List<Long> idsToDelete = idList.stream()
                        .map(Number::longValue)
                        .filter(id -> !id.equals(item.getId()))
                        .collect(Collectors.toList());
                if (!idsToDelete.isEmpty()) {
                    orderItemRepository.deleteAllById(idsToDelete);
                }
            }
        }

        // --- Handle Toppings ---
        if (req.containsKey("toppingIds")) {
            // 1. 清除舊配料 (透過 Hibernate orphanRemoval)
            if (item.getToppings() != null) {
                item.getToppings().clear();
            } else {
                item.setToppings(new ArrayList<>());
            }

            // 2. 新增新配料
            @SuppressWarnings("unchecked")
            List<Number> toppingIds = (List<Number>) req.get("toppingIds");
            @SuppressWarnings("unchecked")
            List<String> toppingNames = (List<String>) req.getOrDefault("toppingNames", new ArrayList<>());
            @SuppressWarnings("unchecked")
            List<Number> toppingPrices = (List<Number>) req.getOrDefault("toppingPrices", new ArrayList<>());

            for (int i = 0; i < toppingIds.size(); i++) {
                OrderItemTopping t = new OrderItemTopping();
                OrderItemToppingId id = new OrderItemToppingId();
                id.setOrderItemId(item.getId());

                String tName = (i < toppingNames.size()) ? toppingNames.get(i) : "配料#" + toppingIds.get(i);
                id.setToppingNameSnapshot(tName);
                t.setId(id);
                t.setOrderItem(item);

                BigDecimal tPrice = BigDecimal.ZERO;
                if (i < toppingPrices.size()) {
                    tPrice = new BigDecimal(toppingPrices.get(i).toString());
                }
                t.setToppingPriceSnapshot(tPrice);
                item.getToppings().add(t);
            }
        }

        // --- Recalculate Hash ---
        String toppingsStr = item.getToppings().stream()
                .map(t -> t.getId().getToppingNameSnapshot())
                .sorted()
                .collect(Collectors.joining(","));
        item.setItemHash(generateItemHash(item.getProduct().getId(), item.getSugarSnapshot(),
                item.getIceSnapshot(), item.getSizeSnapshot(), toppingsStr, item.getCouponId()));

        return orderItemRepository.save(item);
    }

    @Transactional
    public void removeItem(String token, Long itemId, Long userId) {
        GroupOrder go = getGroupOrderByToken(token);
        if (!"OPEN".equalsIgnoreCase(go.getStatus()) && !"LOCKED".equalsIgnoreCase(go.getStatus())) {
            throw new RuntimeException("Cannot remove items from a " + go.getStatus().toLowerCase() + " group order");
        }
        OrderItem item = orderItemRepository.findById(itemId).orElseThrow();
        if (!go.getInitiator().getId().equals(userId) && !item.getUser().getId().equals(userId)) {
            throw new RuntimeException("Permission denied");
        }
        if ("PAID".equals(item.getPaymentStatus())) {
            BigDecimal refundAmount = item.getFinalPrice();
            transactionRecordService.updateStoreCredit(item.getUser().getId(), refundAmount,
                    "Refund\n揪團品項退款 (商品: " + item.getProductNameSnapshot() + ")", LocalDateTime.now());
        }
        if (item.getCouponId() != null) {
            restoreUserCoupon(item.getUser().getId(), item.getCouponId());
        }
        orderItemRepository.deleteById(itemId);
    }

    @Transactional
    public GroupOrder setStatus(String token, String status, Long hostId) {
        GroupOrder go = getGroupOrderByToken(token);
        if (!go.getInitiator().getId().equals(hostId)) {
            throw new RuntimeException("Only host can change status");
        }
        go.setStatus(status);
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
        List<OrderItem> items = getItems(go.getId());
        if (items.isEmpty()) {
            throw new RuntimeException("Cart is empty");
        }

        BigDecimal fullGrossTotal = items.stream()
                .map(OrderItem::getFinalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal discountAmount = BigDecimal.ZERO;
        if (couponId != null) {
            UserCoupon userCoupon = userCouponRepository.findById(couponId)
                    .orElseThrow(() -> new RuntimeException("Coupon not found"));
            discountAmount = userCoupon.getDiscountAmount();
            markCouponAsUsed(couponId);
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
        go.setEscrowAmount(amountToCharge);

        if ("WALLET".equals(paymentMethod)) {
            transactionRecordService.updateStoreCredit(hostId, amountToCharge.negate(),
                    "消費扣款\n揪團結帳扣款 (已扣除團員已付部分)", LocalDateTime.now());
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

        BigDecimal discountAmount = BigDecimal.ZERO;
        if (couponId != null) {
            UserCoupon userCoupon = userCouponRepository.findById(couponId)
                    .orElseThrow(() -> new RuntimeException("Coupon not found"));
            discountAmount = userCoupon.getDiscountAmount();
            markCouponAsUsed(couponId);
        }

        BigDecimal finalAmount = totalAmount.subtract(discountAmount).max(BigDecimal.ZERO);

        if ("WALLET".equals(paymentMethod)) {
            transactionRecordService.updateStoreCredit(userId, finalAmount.negate(),
                    "消費扣款\n揪團個人品項結帳扣款", LocalDateTime.now());
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
        if ("CANCELLED".equalsIgnoreCase(go.getStatus()) || "REJECTED".equalsIgnoreCase(go.getStatus())) {
            return false;
        }
        List<OrderItem> items = orderItemRepository.findByGroupOrderIdForUpdate(go.getId());

        // 1. 各品項退款、狀態鎖定與優惠券還原
        for (OrderItem item : items) {
            // 只有 PAID 且未退款的品項才處理
            if ("PAID".equalsIgnoreCase(item.getPaymentStatus())) {
                BigDecimal discount = item.getDiscountAmountSnapshot() != null
                        ? item.getDiscountAmountSnapshot() : BigDecimal.ZERO;
                BigDecimal refundAmount = item.getFinalPrice().subtract(discount).max(BigDecimal.ZERO);
                
                transactionRecordService.updateStoreCredit(item.getUser().getId(), refundAmount,
                        "Refund\n揪團取消退款 (品項: " + item.getProductNameSnapshot() + ")", LocalDateTime.now());
                item.setPaymentStatus("REFUNDED");

                // 還原個人優惠券
                if (item.getCouponId() != null) {
                    restoreUserCoupon(item.getUser().getId(), item.getCouponId());
                }
            } else if ("WAITING_SUBMIT".equalsIgnoreCase(item.getPaymentStatus())) {
                // WAITING_SUBMIT 表示已從錢包扣款但尚未 SUBMITTED (用於個人訂單或揪團結帳中間態)
                // 因為 checkout 計算 escrowAmount 時會扣除 PAID，
                // 如果是 WAITING_SUBMIT 且是個人訂單，也應在此退款
                if ("GROUP".equals(go.getType())) {
                    // 揪團中的 WAITING_SUBMIT 通常由 escrowAmount 覆蓋，故此處不重複退
                } else {
                    BigDecimal refundAmount = item.getFinalPrice();
                    transactionRecordService.updateStoreCredit(item.getUser().getId(), refundAmount,
                            "Refund\n訂單取消退款 (品項: " + item.getProductNameSnapshot() + ")", LocalDateTime.now());
                }
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
                        "Refund\n揪團差額扣款退還 (訂單 #" + go.getId() + ")", LocalDateTime.now());
                go.setEscrowAmount(BigDecimal.ZERO); // 清零防止重複退
            }
        }

        // 步驟 3：還原揪團整單優惠券（Timestamp 反查法，不需 Schema 異動）
        if (go.getSubmittedAt() != null && go.getInitiator() != null) {
            // 收集所有品項層級已使用的 couponId，排除在外
            java.util.Set<Long> itemCouponIds = items.stream()
                    .map(OrderItem::getCouponId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());

            // 查詢結帳時間前後 5 秒內被標記 used 的非品項券
            LocalDateTime from = go.getSubmittedAt().minusSeconds(5);
            LocalDateTime to = go.getSubmittedAt().plusSeconds(5);

            // excludedIds 不可為空集合，以 -1L 代替
            java.util.Collection<Long> excludedIds = itemCouponIds.isEmpty() ? List.of(-1L) : itemCouponIds;
            List<UserCoupon> candidates = userCouponRepository.findCheckoutLevelCoupon(go.getInitiator().getId(), from, to,
                    excludedIds);

            for (UserCoupon uc : candidates) {
                uc.setStatus("unused");
                uc.setUsedAt(null);
                userCouponRepository.save(uc);
            }
        }

        go.setStatus("CANCELLED");
        go.setCancelledOrRejectedAt(LocalDateTime.now());
        groupOrderRepository.save(go);
        return true;
    }

    @Transactional
    public void cancelMemberItems(Long realOrderId, Long userId) {
        GroupOrder go = groupOrderRepository.findById(realOrderId)
                .orElseThrow(() -> new RuntimeException("Group order not found"));

        List<OrderItem> memberItems = orderItemRepository.findByGroupOrderId(go.getId()).stream()
                .filter(i -> i.getUser().getId().equals(userId))
                .toList();

        if (memberItems.isEmpty()) {
            throw new RuntimeException("No items found for this user to cancel");
        }

        BigDecimal hostRefundAmount = BigDecimal.ZERO;

        for (OrderItem mItem : memberItems) {
            BigDecimal itemTotal = mItem.getFinalPrice();
            if ("PAID".equalsIgnoreCase(mItem.getPaymentStatus())) {
                transactionRecordService.updateStoreCredit(mItem.getUser().getId(), itemTotal,
                        "Refund\n揪團單品取消退款 (商品: " + mItem.getProductNameSnapshot() + ")", LocalDateTime.now());
            } else {
                hostRefundAmount = hostRefundAmount.add(itemTotal);
            }

            // 還原優惠券
            if (mItem.getCouponId() != null) {
                restoreUserCoupon(mItem.getUser().getId(), mItem.getCouponId());
            }
        }
        orderItemRepository.deleteAll(memberItems);

        if (hostRefundAmount.compareTo(BigDecimal.ZERO) > 0) {
            transactionRecordService.updateStoreCredit(go.getInitiator().getId(), hostRefundAmount,
                    "Refund\n揪團團員取消品項退款代墊金", LocalDateTime.now());
            go.setTotalAmount(go.getTotalAmount().subtract(hostRefundAmount));
            // ✅ 新增：同步扣減 escrowAmount，防止後續取消再次退款
            if (go.getEscrowAmount() != null) {
                BigDecimal newEscrow = go.getEscrowAmount().subtract(hostRefundAmount).max(BigDecimal.ZERO);
                go.setEscrowAmount(newEscrow);
            }
            groupOrderRepository.save(go);
        }
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
    public Optional<GroupOrderDTO> getGroupOrderDTOByOrderId(Long realOrderId) {
        return groupOrderRepository.findById(realOrderId).map(this::convertToDTO);
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
                "支付補款\n揪團轉付給團長 (補款)", LocalDateTime.now());

        // 存入團長錢包
        String userName = userRepository.findById(userId)
                .map(com.example.demo.entity.User::getName)
                .orElse("未知");
        transactionRecordService.updateStoreCredit(go.getInitiator().getId(), totalAmount,
                "收到團員補款 (團員名稱: " + userName + ", 團員ID: " + userId + ")", LocalDateTime.now());

        // 標記品項為已付款
        for (OrderItem item : memberItems) {
            item.setPaymentStatus("PAID");
        }
        orderItemRepository.saveAll(memberItems);
    }

    @Transactional
    public void applyCouponToItem(String token, Long itemId, Long userId, Long couponId) {
        GroupOrder go = getGroupOrderByToken(token);
        if (!"OPEN".equals(go.getStatus()) && !"LOCKED".equals(go.getStatus())) {
            throw new RuntimeException("Group order is closed");
        }
        OrderItem item = getItemById(itemId);
        if (!item.getUser().getId().equals(userId)) {
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
        if (userCouponRepository.markUsedIfUnused(couponId, LocalDateTime.now()) == 0) {
            throw new CustomException("409", "此優惠券已被使用");
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
                item.setGroupOrder(order); // 將品項重新指向新揪團
                item.setPaymentStatus("UNPAID"); // 確保轉移後為未付款狀態
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
                OrderItem oi = new OrderItem();
                oi.setGroupOrder(order);
                oi.setUser(user);
                oi.setProduct(ci.getProduct());
                oi.setProductNameSnapshot(ci.getProduct().getName());
                oi.setUnitPriceSnapshot(ci.getUnitPrice());
                BigDecimal cartFinalPrice = ci.getFinalPrice();
                if (cartFinalPrice != null) {
                    oi.setFinalPrice(cartFinalPrice.multiply(new BigDecimal(ci.getQuantity())));
                } else {
                    oi.setFinalPrice(BigDecimal.ZERO);
                }
                oi.setQty(ci.getQuantity());
                oi.setSugarSnapshot(ci.getSugarSnapshot());
                oi.setIceSnapshot(ci.getIceSnapshot());
                oi.setSizeSnapshot(ci.getSizeSnapshot());
                oi.setPaymentStatus("UNPAID");
                oi.setPaymentType("WALLET"); // 建立揪團時預設錢包支付，結帳時可再覆寫
                
                // 處理配料 (CartItem CSV -> OrderItemTopping List)
                List<OrderItemTopping> toppings = new ArrayList<>();
                if (ci.getToppingNames() != null && !ci.getToppingNames().isBlank()) {
                    String[] tNames = ci.getToppingNames().split(",");
                    Long brandId = ci.getProduct().getBrand().getId();
                    // 預先取得該品牌所有配料設定，用於價格對應
                    List<BrandToppingSetting> settings = brandToppingSettingRepository.findByBrandId(brandId);

                    for (String tName : tNames) {
                        String name = tName.trim();
                        OrderItemTopping oit = new OrderItemTopping();
                        OrderItemToppingId id = new OrderItemToppingId();
                        id.setToppingNameSnapshot(name);
                        oit.setId(id);
                        oit.setOrderItem(oi);

                        // 查找配料單價，若找不到則預設 0
                        BigDecimal tPrice = settings.stream()
                                .filter(s -> name.equals(s.getCustomName()) || (s.getMasterTopping() != null && name.equals(s.getMasterTopping().getName())))
                                .map(s -> s.getBrandPrice() != null ? s.getBrandPrice() : (s.getMasterTopping() != null ? s.getMasterTopping().getDefaultPrice() : BigDecimal.ZERO))
                                .findFirst()
                                .orElse(BigDecimal.ZERO);
                        
                        oit.setToppingPriceSnapshot(tPrice);
                        toppings.add(oit);
                    }
                }
                oi.setToppings(toppings);
                
                // 計算 Hash (與 GroupOrderService 其他部分一致)
                String toppingsKey = toppings.stream()
                        .map(t -> t.getId().getToppingNameSnapshot())
                        .sorted()
                        .collect(java.util.stream.Collectors.joining(","));
                oi.setItemHash(generateItemHash(ci.getProduct().getId(), ci.getSugarSnapshot(), 
                        ci.getIceSnapshot(), ci.getSizeSnapshot(), toppingsKey, null));
                
                orderItemRepository.save(oi);
                if (oi.getFinalPrice() != null) {
                    migratedTotal = migratedTotal.add(oi.getFinalPrice());
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

    public Map<String, Object> getByToken(String token) {
        GroupOrder order = groupOrderRepository.findByShareToken(token)
                .orElseThrow(() -> new CustomException("404", "找不到此揪團，連結可能已失效"));
        if (!"OPEN".equals(order.getStatus()))
            throw new CustomException("409", "此揪團已結束，無法加入");

        Map<String, Object> result = new HashMap<>();
        result.put("groupOrderId", order.getId());
        result.put("storeName", order.getStore().getStoreName());
        result.put("initiatorName", order.getInitiator().getName());
        result.put("note", order.getNote());
        result.put("status", order.getStatus());
        result.put("createdAt", order.getCreatedAt());
        return result;
    }

    @Transactional
    public Map<String, Object> joinGroup(Long userId, Long groupOrderId, Map<String, Object> req) {
        GroupOrder order = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
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
    public Map<String, Object> getGroupDetail(Long groupOrderId) {
        GroupOrder order = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
        List<OrderItem> allItems = orderItemRepository.findByGroupOrderId(groupOrderId);

        Map<Long, Map<String, Object>> memberMap = new LinkedHashMap<>();
        for (OrderItem item : allItems) {
            Long uid = item.getUser().getId();
            memberMap.computeIfAbsent(uid, k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("userId", uid);
                m.put("userName", item.getUser().getName());
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
    public Map<String, Object> getGroupSummary(Long groupOrderId) {
        GroupOrder order = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
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
        result.put("memberCount", items.stream().map(i -> i.getUser().getId()).distinct().count());
        return result;
    }

    public Map<String, Object> getShareInfo(Long groupOrderId) {
        GroupOrder order = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
        String joinUrl = BASE_URL + order.getShareToken();
        Map<String, Object> result = new HashMap<>();
        result.put("groupOrderId", groupOrderId);
        result.put("shareToken", order.getShareToken());
        result.put("joinUrl", joinUrl);
        result.put("qrCodeUrl", "https://api.qrserver.com/v1/create-qr-code/?size=200x200&data="
                + java.net.URLEncoder.encode(joinUrl, java.nio.charset.StandardCharsets.UTF_8));
        return result;
    }

    @Transactional
    public Map<String, Object> submitGroupOrder(Long userId, Long groupOrderId, Map<String, Object> req) {
        GroupOrder order = groupOrderRepository.findById(groupOrderId)
                .orElseThrow(() -> new CustomException("404", "找不到揪團訂單"));
        if (!order.getInitiator().getId().equals(userId))
            throw new CustomException("403", "只有團長可以送單");
        if (!"OPEN".equals(order.getStatus()))
            throw new CustomException("409", "訂單狀態不允許送單");

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
        for (Map<String, Object> req : items) {
            Long productId = Long.parseLong(req.get("productId").toString());
            ProductTemplate product = productTemplateRepository.findById(productId)
                    .orElseThrow(() -> new CustomException("404", "找不到商品 " + productId));
            OrderItem item = new OrderItem();
            item.setGroupOrder(order);
            item.setUser(user);
            item.setProduct(product);
            item.setSugarSnapshot((String) req.getOrDefault("sugar", ""));
            item.setIceSnapshot((String) req.getOrDefault("ice", ""));

            int qty = Integer.parseInt(req.getOrDefault("qty", "1").toString());
            item.setQty(qty);
            item.setPaymentStatus("UNPAID");
            item.setPaymentType((String) req.getOrDefault("paymentType", "WALLET"));
            item.setProductNameSnapshot(product.getName());
            item.setUnitPriceSnapshot(product.getBasePrice());
            item.setFinalPrice(product.getBasePrice().multiply(new BigDecimal(qty)));
            orderItemRepository.save(item);
            orderTotal = orderTotal.add(item.getFinalPrice());
        }
        order.setTotalAmount(orderTotal);
        groupOrderRepository.save(order);
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}