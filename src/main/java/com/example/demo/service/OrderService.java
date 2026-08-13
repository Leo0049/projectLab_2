package com.example.demo.service;

import com.example.demo.dto.OrderItemRequest;
import com.example.demo.dto.PlaceOrderRequest;
import com.example.demo.dto.RecentOrderDTO;
import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.ProductTemplate;
import com.example.demo.entity.Store;
import com.example.demo.entity.User;
import com.example.demo.entity.UserCoupon;
import com.example.demo.entity.OrderItemTopping;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRatingRepository;
import com.example.demo.repository.OrderItemToppingRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.ProductTemplateRepository;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserRepository;
import jakarta.transaction.Transactional;
import com.example.demo.repository.UserCouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final GroupOrderRepository groupOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final ProductRepository productRepository;
    private final TransactionRecordService transactionRecordService;
    private final CouponService couponService;
    private final UserCouponRepository userCouponRepository;
    private final OrderItemToppingRepository orderItemToppingRepository;
    private final RedisLockService redisLockService;
    private final ProductTemplateRepository productTemplateRepository;
    private final com.example.demo.repository.ProductSpecRelationRepository productSpecRelationRepository;

    @Autowired(required = false)
    private OrderRatingRepository orderRatingRepository;

    @Autowired(required = false)
    private OrderRatingService orderRatingService;

    // ============================================================
    // Entity-based methods (existing pending methods)
    // ============================================================

    public org.springframework.data.domain.Page<GroupOrder> getOrdersByUserId(Long userId,
            org.springframework.data.domain.Pageable pageable) {
        return groupOrderRepository.findVisibleOrdersForUser(userId, pageable);
    }

    public org.springframework.data.domain.Page<GroupOrder> getOrdersByUserIdAndStatuses(Long userId,
            java.util.List<String> statuses, org.springframework.data.domain.Pageable pageable) {
        return groupOrderRepository.findVisibleOrdersForUserByStatuses(userId, statuses, pageable);
    }

    public GroupOrder getOrderById(Long orderId) {
        return groupOrderRepository.findById(orderId).orElse(null);
    }

    public GroupOrder getOrderByIdAndUserId(Long orderId, Long userId) {
        return groupOrderRepository.findById(orderId)
                .filter(o -> o.getInitiator() != null && o.getInitiator().getId().equals(userId))
                .orElse(null);
    }

    public List<OrderItem> getOrderItems(Long orderId) {
        return orderItemRepository.findByGroupOrderId(orderId);
    }

    public List<GroupOrder> getOrdersByStoreId(Long storeId) {
        return groupOrderRepository.findByStoreIdAndOptionalStatus(storeId, null);
    }

    public GroupOrder updateOrderStatus(Long orderId, String status) {
        GroupOrder order = groupOrderRepository.findById(orderId).orElseThrow();
        order.setStatus(status);
        applyStatusTimestamp(order, status);
        return groupOrderRepository.save(order);
    }

    public GroupOrder updateOrderStatusWithUserCheck(Long orderId, String status, Long userId) {
        GroupOrder order = groupOrderRepository.findById(orderId)
                .filter(o -> o.getInitiator() != null && o.getInitiator().getId().equals(userId))
                .orElseThrow(() -> new RuntimeException("無權限更新此訂單或訂單不存在"));
        order.setStatus(status);
        applyStatusTimestamp(order, status);
        return groupOrderRepository.save(order);
    }

    private void applyStatusTimestamp(GroupOrder order, String status) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Taipei"));
        switch (status.toUpperCase()) {
            case "SUBMITTED" -> {
                if (order.getSubmittedAt() == null)
                    order.setSubmittedAt(now);
            }
            case "PREPARING" -> {
                if (order.getPreparingAt() == null)
                    order.setPreparingAt(now);
            }
            case "READY" -> {
                if (order.getReadyAt() == null)
                    order.setReadyAt(now);
            }
            case "COMPLETED" -> {
                if (order.getCompletedAt() == null)
                    order.setCompletedAt(now);
            }
            case "CANCELLED", "REJECTED" -> {
                if (order.getCancelledOrRejectedAt() == null)
                    order.setCancelledOrRejectedAt(now);
            }
        }
    }

    @Transactional
    public List<RecentOrderDTO> getRecentOrderedItems(Long userId) {
        List<GroupOrder> latestOrders = groupOrderRepository.findByInitiatorIdOrderByCreatedAtDesc(userId);
        if (latestOrders.isEmpty())
            return new ArrayList<>();

        List<GroupOrder> limitedOrders = latestOrders.stream().limit(5).toList();
        List<Long> orderIds = limitedOrders.stream().map(GroupOrder::getId).toList();

        // 1. 批量抓取品項 (避免 N+1)
        List<OrderItem> allItems = orderItemRepository.findByGroupOrderIdIn(orderIds);
        Map<Long, List<OrderItem>> itemsByOrderId = allItems.stream()
                .collect(Collectors.groupingBy(oi -> oi.getGroupOrder().getId()));

        return limitedOrders.stream()
                .flatMap(order -> {
                    Store store = order.getStore();
                    List<OrderItem> items = itemsByOrderId.getOrDefault(order.getId(), new ArrayList<>());
                    return items.stream().map(item -> {
                        ProductTemplate product = item.getProduct();
                        RecentOrderDTO dto = new RecentOrderDTO();
                        dto.setId(item.getId());
                        dto.setStoreId(store != null ? store.getId() : null);
                        dto.setStoreName(store != null ? store.getStoreName() : "Unknown Store");
                        dto.setProductName(product != null ? product.getName() : "Unknown Product");
                        dto.setPrice(item.getUnitPriceSnapshot());
                        if (store != null) {
                            String sn = store.getStoreName();
                            if (sn.contains("可不可")) {
                                dto.setLogoBg("bg-[#002b49]");
                                dto.setLogoText("可不可");
                            } else if (sn.contains("得正")) {
                                dto.setLogoBg("bg-[#1a4a38]");
                                dto.setLogoText("得正");
                            } else if (sn.contains("珍煮丹")) {
                                dto.setLogoBg("bg-[#7a1b1b]");
                                dto.setLogoText("珍煮丹");
                            } else if (sn.contains("50嵐") || sn.contains("五十嵐")) {
                                dto.setLogoBg("bg-[#f4c20d]");
                                dto.setLogoText("50嵐");
                                dto.setTextColor("text-blue-900");
                            } else {
                                dto.setLogoBg("bg-gray-200");
                                dto.setLogoText(sn.substring(0, Math.min(sn.length(), 2)));
                                dto.setTextColor("text-gray-800");
                            }
                        }
                        return dto;
                    });
                })
                .limit(10)
                .toList();
    }

    @Transactional
    public Long createOrder(Long userId, Long storeId, BigDecimal totalAmount, Long couponId,
            List<OrderItem> items, String paymentMethod, String deliveryAddress, String note) {
        return createOrder(userId, storeId, totalAmount, couponId, items, paymentMethod, deliveryAddress,
                note, BigDecimal.ZERO);
    }

    @Transactional
    public Long createOrder(Long userId, Long storeId, BigDecimal totalAmount, Long couponId,
            List<OrderItem> items, String paymentMethod, String deliveryAddress, String note,
            BigDecimal alreadyPaidAmount) {
        String lockKey = "lock:checkout:" + userId;
        if (!redisLockService.acquireLock(lockKey, 30)) {
            throw new RuntimeException("系統繁忙中，請稍後再試");
        }
        try {
            BigDecimal discountAmount = BigDecimal.ZERO;
            if (couponId != null) {
                // couponId = user_coupons.id
                List<Long> productIds = items.stream()
                        .map(i -> i.getProduct() != null ? i.getProduct().getId() : 0L)
                        .toList();
                if (!couponService.isValidForStore(couponId, storeId, productIds)) {
                    throw new RuntimeException("無效的優惠券，或該優惠券不適用於此店家與飲品");
                }

                // 直接用 user_coupons.id 查找，確認未使用
                UserCoupon uc = userCouponRepository.findByIdForUpdate(couponId)
                        .orElseThrow(() -> new RuntimeException("Coupon not found"));

                if (!"unused".equals(uc.getStatus()) || !uc.getUser().getId().equals(userId)) {
                    throw new RuntimeException("優惠券已被使用或失效");
                }

                discountAmount = uc.getDiscountAmount();

                // 標記為已使用
                uc.setStatus("used");
                uc.setUsedAt(LocalDateTime.now());
                userCouponRepository.save(uc);
            }

            BigDecimal totalItemDiscount = items.stream()
                    .map(i -> i.getDiscountAmountSnapshot() != null ? i.getDiscountAmountSnapshot() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal finalAmount = totalAmount.subtract(discountAmount).subtract(totalItemDiscount);
            if (finalAmount.compareTo(BigDecimal.ZERO) < 0)
                finalAmount = BigDecimal.ZERO;

            // The amount the CURRENT user (host) actually needs to pay
            BigDecimal amountToCharge = finalAmount
                    .subtract(alreadyPaidAmount != null ? alreadyPaidAmount : BigDecimal.ZERO);
            if (amountToCharge.compareTo(BigDecimal.ZERO) < 0)
                amountToCharge = BigDecimal.ZERO;

            GroupOrder order = new GroupOrder();
            User initiator = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("找不到使用者"));
            Store orderStore = storeRepository.findById(storeId)
                    .orElseThrow(() -> new RuntimeException("找不到店家"));
            order.setInitiator(initiator);
            order.setStore(orderStore);
            order.setTotalAmount(finalAmount);
            // order.setCouponDiscountAmount(discountAmount);
            order.setNote(note != null ? note : "");
            order.setAddress(deliveryAddress != null ? deliveryAddress : "");
            order.setType("SOLO");
            order.setOrderNo(generateOrderNo());

            // DATABASE.md 訂單狀態: OPEN → SUBMITTED
            order.setStatus("SUBMITTED");
            order.setSubmittedAt(LocalDateTime.now());
            if ("WALLET".equals(paymentMethod)) {
                order.setEscrowAmount(amountToCharge);
            }

            order = groupOrderRepository.save(order);

            if ("WALLET".equals(paymentMethod)) {
                transactionRecordService.updateStoreCredit(userId, amountToCharge.negate(), "ESCROW" +
                        "Order #" + order.getId() + " 結帳扣款 (已扣除團員已付部分)", LocalDateTime.now());
            }

            // --- 固定規格防竄改 (Fixed Specification Anti-Tamper) ---
            for (OrderItem item : items) {
                if (item.getProduct() == null) continue;
                List<com.example.demo.entity.ProductSpecRelation> relations = productSpecRelationRepository.findByIdProductId(item.getProduct().getId());
                Map<String, List<com.example.demo.entity.ProductSpecRelation>> specsByType = relations.stream()
                        .filter(r -> r.getBrandSpec() != null && r.getBrandSpec().getSpecType() != null)
                        .collect(Collectors.groupingBy(r -> r.getBrandSpec().getSpecType().toUpperCase()));

                List<com.example.demo.entity.ProductSpecRelation> sugarOpts = specsByType.get("SWEETNESS");
                if (sugarOpts != null && sugarOpts.size() == 1) {
                    item.setSugarSnapshot(sugarOpts.get(0).getBrandSpec().getCustomName());
                }
                List<com.example.demo.entity.ProductSpecRelation> iceOpts = specsByType.get("ICE");
                if (iceOpts != null && iceOpts.size() == 1) {
                    item.setIceSnapshot(iceOpts.get(0).getBrandSpec().getCustomName());
                }
                List<com.example.demo.entity.ProductSpecRelation> sizeOpts = specsByType.get("SIZE");
                if (sizeOpts != null && sizeOpts.size() == 1) {
                    item.setSizeSnapshot(sizeOpts.get(0).getBrandSpec().getCustomName());
                }
                item.setGroupOrder(order);
                item.setPaymentType(paymentMethod);
                if ("WALLET".equals(paymentMethod)) {
                    item.setPaymentStatus("WAITING_SUBMIT");
                } else {
                    item.setPaymentStatus("UNPAID");
                }
            }
            orderItemRepository.saveAll(items);

            return order.getId();
        } finally {
            redisLockService.releaseLock(lockKey);
        }
    }

    // ============================================================
    // Map-based methods (from service/OrderService.java)
    // Merged to ensure full compatibility
    // ============================================================

    public static String generateOrderNo() {
        String datePart = java.time.format.DateTimeFormatter.ofPattern("MMdd")
                .format(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")));
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        return "ORD" + datePart + randomPart;
    }

    @Transactional
    public Map<String, Object> placeOrder(Long userId, PlaceOrderRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("404", "User not found"));
        Store store = storeRepository.findById(req.getStoreId())
                .orElseThrow(() -> new CustomException("404", "Store not found"));

        if (!"active".equals(store.getStatus())) {
            throw new CustomException("400", "Store is not accepting new orders");
        }
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new CustomException("400", "Order items cannot be empty");
        }

        GroupOrder order = new GroupOrder();
        order.setInitiator(user);
        order.setStore(store);
        order.setType("SOLO");
        order.setStatus("SUBMITTED");
        order.setOrderNo(generateOrderNo());
        order.setNote(req.getNote() != null ? req.getNote() : "");
        order.setAddress("");

        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();

        // 1. Bulk fetch all ProductTemplates to avoid N+1
        List<Long> productIds = req.getItems().stream().map(OrderItemRequest::getProductId).toList();
        Map<Long, ProductTemplate> productMap = productTemplateRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductTemplate::getId, p -> p));

        for (OrderItemRequest itemReq : req.getItems()) {
            ProductTemplate product = productMap.get(itemReq.getProductId());
            if (product == null) {
                throw new CustomException("404", "Product not found: " + itemReq.getProductId());
            }

            BigDecimal finalPrice = product.getBasePrice() != null ? product.getBasePrice() : BigDecimal.ZERO;

            OrderItem item = new OrderItem();
            item.setGroupOrder(order);
            item.setUser(user);
            item.setProduct(product);
            item.setProductNameSnapshot(product.getName());
            item.setUnitPriceSnapshot(product.getBasePrice());
            // --- 固定規格防竄改 (Fixed Specification Anti-Tamper) ---
            List<com.example.demo.entity.ProductSpecRelation> relations = productSpecRelationRepository.findByIdProductId(product.getId());
            Map<String, List<com.example.demo.entity.ProductSpecRelation>> specsByType = relations.stream()
                    .filter(r -> r.getBrandSpec() != null && r.getBrandSpec().getSpecType() != null)
                    .collect(Collectors.groupingBy(r -> r.getBrandSpec().getSpecType().toUpperCase()));

            String sugar = itemReq.getSugarSnapshot();
            List<com.example.demo.entity.ProductSpecRelation> sugarOpts = specsByType.get("SWEETNESS");
            if (sugarOpts != null && sugarOpts.size() == 1) {
                sugar = sugarOpts.get(0).getBrandSpec().getCustomName();
            }
            item.setSugarSnapshot(sugar);

            String ice = itemReq.getIceSnapshot();
            List<com.example.demo.entity.ProductSpecRelation> iceOpts = specsByType.get("ICE");
            if (iceOpts != null && iceOpts.size() == 1) {
                ice = iceOpts.get(0).getBrandSpec().getCustomName();
            }
            item.setIceSnapshot(ice);

            String size = itemReq.getSizeSnapshot() != null ? itemReq.getSizeSnapshot() : "大杯";
            List<com.example.demo.entity.ProductSpecRelation> sizeOpts = specsByType.get("SIZE");
            if (sizeOpts != null && sizeOpts.size() == 1) {
                size = sizeOpts.get(0).getBrandSpec().getCustomName();
            }
            item.setSizeSnapshot(size);

            item.setFinalPrice(finalPrice);
            item.setPaymentType(itemReq.getPaymentType() != null ? itemReq.getPaymentType() : "WALLET");
            item.setPaymentStatus("WALLET".equals(item.getPaymentType()) ? "WAITING_SUBMIT" : "UNPAID");

            total = total.add(finalPrice);
            orderItems.add(item);
        }

        BigDecimal creditTotal = orderItems.stream()
                .filter(i -> "WALLET".equals(i.getPaymentType()))
                .map(OrderItem::getFinalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (creditTotal.compareTo(BigDecimal.ZERO) > 0) {
            // 使用 TransactionRecordService 進行扣款，內部會檢查餘額並儲存紀錄
            transactionRecordService.updateStoreCredit(userId, creditTotal.negate(),
                    "消費扣款\n個人訂單 #" + (order.getId() != null ? order.getId() : "") + " 結帳扣款", LocalDateTime.now());

            // 將品項狀態改為 PAID
            for (OrderItem item : orderItems) {
                if ("WALLET".equals(item.getPaymentType())) {
                    item.setPaymentStatus("PAID");
                }
            }
        }

        order.setTotalAmount(total);
        groupOrderRepository.save(order);
        // 單號不再依賴 ID，直接使用新格式
        if (order.getOrderNo() == null)
            order.setOrderNo(generateOrderNo());
        groupOrderRepository.save(order);

        orderItemRepository.saveAll(orderItems);

        log.info("user {} placed order {}", userId, order.getOrderNo());

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", order.getOrderNo());
        result.put("orderId", order.getId());
        result.put("totalAmount", total);
        result.put("status", order.getStatus());
        return result;
    }

    // 目前靠 findVisibleOrdersForUser 的 JOIN FETCH g.store 才沒炸，但那是查詢的實作細節；
    // 這裡同樣會讀 order.getStore()，補上交易免得日後改查詢時又踩到同一顆雷。
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<Map<String, Object>> getOrderHistory(Long userId) {
        List<GroupOrder> orders = groupOrderRepository
                .findVisibleOrdersForUser(userId, org.springframework.data.domain.Pageable.unpaged()).getContent();
        if (orders.isEmpty()) {
            return List.of();
        }

        List<Long> orderIds = orders.stream().map(GroupOrder::getId).toList();
        Map<Long, List<OrderItem>> myItemsByOrder = orderItemRepository.findByGroupOrderIdInAndUserId(orderIds, userId)
                .stream()
                .collect(Collectors.groupingBy(
                        item -> item.getGroupOrder().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<Long, Integer> ratingsByOrder = new HashMap<>();
        if (orderRatingService != null) {
            ratingsByOrder = orderRatingService.getMyRatingScores(userId, orderIds);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (GroupOrder order : orders) {
            List<OrderItem> myItems = myItemsByOrder.getOrDefault(order.getId(), List.of());
            BigDecimal myAmount = sumPrices(myItems);
            Integer myRating = ratingsByOrder.get(order.getId());

            Map<String, Object> map = new HashMap<>();
            map.put("orderId", order.getId());
            map.put("orderNo", order.getOrderNo());
            map.put("type", order.getType());
            map.put("historyType", "GROUP".equals(order.getType()) ? "group" : "personal");
            map.put("storeId", order.getStore() != null ? order.getStore().getId() : null);
            map.put("storeName", order.getStore() != null ? order.getStore().getStoreName() : null);
            map.put("storeCoverUrl", order.getStore() != null ? order.getStore().getCoverUrl() : null);
            map.put("totalAmount", order.getTotalAmount());
            map.put("myAmount", myAmount);
            map.put("status", order.getStatus());
            map.put("note", order.getNote());
            map.put("createdAt", order.getCreatedAt());
            map.put("completedAt", order.getCompletedAt());
            map.put("isInitiator",
                    order.getInitiator() != null && Objects.equals(order.getInitiator().getId(), userId));
            map.put("participated", !myItems.isEmpty());
            map.put("canRate", !myItems.isEmpty() && "COMPLETED".equals(order.getStatus()));
            map.put("myRating", myRating);
            map.put("ratingSubmitted", myRating != null);
            map.put("items", summarizeItems(myItems));
            result.add(map);
        }
        return result;
    }

    // readOnly 交易不可移除：open-in-view=false，下面會讀 order.getStore().getStoreName()
    // 這個 LAZY 關聯，沒有交易時 session 已關閉，會拋 LazyInitializationException。
    // 這是顧客點「檢視詳情」必打的端點，壞掉等於訂單詳情頁完全打不開。
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Map<String, Object> getOrderDetail(Long userId, Long orderId) {
        GroupOrder order = groupOrderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException("404", "Order not found"));

        boolean isInitiator = order.getInitiator() != null && Objects.equals(order.getInitiator().getId(), userId);
        boolean participated = orderItemRepository.existsByGroupOrderIdAndUserId(orderId, userId);
        if (!isInitiator && !participated) {
            throw new CustomException("403", "You cannot access this order");
        }

        List<OrderItem> allItems = orderItemRepository.findByGroupOrderId(orderId);

        // 根據身分決定可見品項：團長看全部，成員看自己
        List<OrderItem> visibleItems = ("GROUP".equals(order.getType()) && !isInitiator)
                ? allItems.stream()
                        .filter(item -> item.getUser() != null && Objects.equals(item.getUser().getId(), userId))
                        .toList()
                : allItems;

        List<OrderItem> myItems = allItems.stream()
                .filter(item -> item.getUser() != null && Objects.equals(item.getUser().getId(), userId))
                .toList();

        // 1. 批量抓取配料 (避免 N+1)
        List<Long> visibleItemIds = visibleItems.stream().map(OrderItem::getId).toList();
        List<OrderItemTopping> allToppings = orderItemToppingRepository.findByOrderItemIdIn(visibleItemIds);
        Map<Long, List<OrderItemTopping>> toppingsByItemId = allToppings.stream()
                .collect(Collectors.groupingBy(oit -> oit.getOrderItem().getId()));

        // 統計折扣與成員付款進度
        BigDecimal totalDiscount = allItems.stream()
                .map(i -> i.getDiscountAmountSnapshot() != null ? i.getDiscountAmountSnapshot() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal memberPaid = allItems.stream()
                .filter(i -> "PAID".equalsIgnoreCase(i.getPaymentStatus()))
                .filter(i -> i.getUser() != null && order.getInitiator() != null
                        && !i.getUser().getId().equals(order.getInitiator().getId()))
                .map(i -> {
                    BigDecimal fp = i.getFinalPrice() != null ? i.getFinalPrice() : BigDecimal.ZERO;
                    BigDecimal disc = i.getDiscountAmountSnapshot() != null ? i.getDiscountAmountSnapshot()
                            : BigDecimal.ZERO;
                    return fp.subtract(disc).max(BigDecimal.ZERO);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> itemList = new ArrayList<>();
        for (OrderItem item : visibleItems) {
            Map<String, Object> i = new HashMap<>();
            i.put("id", item.getId());
            i.put("productName", item.getProductNameSnapshot());
            i.put("productNameSnapshot", item.getProductNameSnapshot());
            i.put("unitPrice", item.getUnitPriceSnapshot());
            i.put("unitPriceSnapshot", item.getUnitPriceSnapshot());
            i.put("finalPrice", item.getFinalPrice());
            i.put("sugar", item.getSugarSnapshot());
            i.put("sugarSnapshot", item.getSugarSnapshot());
            i.put("ice", item.getIceSnapshot());
            i.put("iceSnapshot", item.getIceSnapshot());
            i.put("size", item.getSizeSnapshot());
            i.put("sizeSnapshot", item.getSizeSnapshot());
            i.put("paymentType", item.getPaymentType());
            i.put("paymentStatus", item.getPaymentStatus());
            i.put("userId", item.getUser() != null ? item.getUser().getId() : null);
            i.put("userName", item.getUser() != null ? item.getUser().getName() : null);
            i.put("quantity", 1);
            i.put("imageUrl", item.getProduct() != null ? item.getProduct().getLogoUrl() : null);
            i.put("discountAmount",
                    item.getDiscountAmountSnapshot() != null ? item.getDiscountAmountSnapshot() : BigDecimal.ZERO);
            // 從 Map 中取得預抓取的配料（轉為簡單 Map，避免 OrderItemTopping entity 循環序列化）
            List<Map<String, Object>> toppingList = toppingsByItemId.getOrDefault(item.getId(), new ArrayList<>())
                    .stream()
                    .map(oit -> {
                        Map<String, Object> t = new HashMap<>();
                        t.put("toppingName", oit.getId().getToppingNameSnapshot());
                        t.put("toppingPrice", oit.getToppingPriceSnapshot() != null ? oit.getToppingPriceSnapshot()
                                : BigDecimal.ZERO);
                        return t;
                    }).collect(Collectors.toList());
            i.put("toppings", toppingList);
            itemList.add(i);
        }

        Map<String, Object> result = new HashMap<>();
        // 基本欄位
        result.put("id", order.getId()); // 前端 fetchOrderWithRetry 需要此欄位確認載入成功
        result.put("orderId", order.getId());
        result.put("orderNo", order.getOrderNo());
        result.put("type", order.getType());
        result.put("status", order.getStatus());
        result.put("totalAmount", order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
        result.put("finalAmount", order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
        result.put("deliveryFee", BigDecimal.ZERO); // 當前 schema 暫無運費欄位，預設為 0
        result.put("myAmount", sumPrices(myItems));
        result.put("note", order.getNote());
        result.put("deliveryAddress", order.getAddress() != null ? order.getAddress() : "");
        result.put("deliveryType", (order.getAddress() != null && !order.getAddress().isEmpty()) ? "delivery" : "pickup");
        result.put("note", order.getNote() != null ? order.getNote() : "");
        result.put("createdAt", order.getCreatedAt());
        result.put("submittedAt", order.getSubmittedAt());
        result.put("preparingAt", order.getPreparingAt());
        result.put("readyAt", order.getReadyAt());
        result.put("completedAt", order.getCompletedAt());
        result.put("isRejected", order.getIsRejected());
        result.put("totalDiscountAmount", totalDiscount);
        result.put("alreadyPaidAmount", memberPaid);
        result.put("shareToken", order.getShareToken());
        result.put("hostId", order.getInitiator() != null ? order.getInitiator().getId() : null);

        // 店家資訊
        if (order.getStore() != null) {
            result.put("storeId", order.getStore().getId());
            result.put("storeName", order.getStore().getStoreName());
            result.put("storeAddress", order.getStore().getAddress());
        }

        // 團長資訊
        if (order.getInitiator() != null) {
            result.put("initiatorId", order.getInitiator().getId());
            result.put("initiatorName", order.getInitiator().getName());
            result.put("initiatorPhone", order.getInitiator().getPhone());
        }

        // 付款方式 (從第一個品項擷取)
        if (!allItems.isEmpty()) {
            result.put("paymentMethod",
                    allItems.get(0).getPaymentType() != null ? allItems.get(0).getPaymentType() : "CASH");
        } else {
            result.put("paymentMethod", "CASH");
        }

        result.put("items", itemList);
        result.put("isInitiator", isInitiator);
        result.put("participated", participated);

        // 評分資訊
        Integer myRating = null;
        if (orderRatingRepository != null) {
            myRating = orderRatingRepository.findByOrderIdAndUserId(orderId, userId)
                    .map(r -> r.getRating())
                    .orElse(null);
        }
        result.put("canRate", participated && "COMPLETED".equals(order.getStatus()) && myRating == null);
        result.put("myRating", myRating);
        result.put("ratingSubmitted", myRating != null);

        return result;
    }

    public Map<String, Object> getOrderSummary(Long userId) {
        List<GroupOrder> all = groupOrderRepository
                .findVisibleOrdersForUser(userId, org.springframework.data.domain.Pageable.unpaged()).getContent();
        long active = all.stream()
                .filter(o -> List.of("OPEN", "SUBMITTED", "READY").contains(o.getStatus()))
                .count();
        long completed = all.stream().filter(o -> "COMPLETED".equals(o.getStatus())).count();
        long cancelled = all.stream()
                .filter(o -> List.of("CANCELLED", "REJECTED").contains(o.getStatus()))
                .count();

        Map<String, Object> result = new HashMap<>();
        result.put("all", all.size());
        result.put("active", active);
        result.put("completed", completed);
        result.put("cancelled", cancelled);
        return result;
    }

    public List<Map<String, Object>> getOrderTimeline(Long userId, Long orderId) {
        GroupOrder order = groupOrderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException("404", "Order not found"));

        boolean isInitiator = order.getInitiator() != null && Objects.equals(order.getInitiator().getId(), userId);
        boolean participated = orderItemRepository.existsByGroupOrderIdAndUserId(orderId, userId);
        if (!isInitiator && !participated) {
            throw new CustomException("403", "You cannot access this order");
        }

        List<Map<String, Object>> timeline = new ArrayList<>();
        String[] statuses = { "OPEN", "SUBMITTED", "READY", "COMPLETED" };
        String[] labels = { "Order Opened", "Submitted", "Ready", "Completed" };
        String currentStatus = order.getStatus();

        boolean reached = false;
        for (int i = 0; i < statuses.length; i++) {
            Map<String, Object> step = new HashMap<>();
            step.put("status", statuses[i]);
            step.put("label", labels[i]);
            if (statuses[i].equals(currentStatus))
                reached = true;
            step.put("isDone", !reached || statuses[i].equals(currentStatus));
            timeline.add(step);
            if (statuses[i].equals(currentStatus))
                break;
        }

        if ("REJECTED".equals(currentStatus)) {
            Map<String, Object> step = new HashMap<>();
            step.put("status", "REJECTED");
            step.put("label", "Rejected");
            step.put("isDone", true);
            timeline.add(step);
        }
        return timeline;
    }

    @Transactional
    public Map<String, Object> reorder(Long userId, Long orderId) {
        GroupOrder order = groupOrderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException("404", "Order not found"));
        if (order.getInitiator() == null || !Objects.equals(order.getInitiator().getId(), userId)) {
            throw new CustomException("403", "You cannot reorder this order");
        }

        List<OrderItem> items = orderItemRepository.findByGroupOrderId(orderId);
        if (items.isEmpty()) {
            throw new CustomException("400", "This order has no items to reorder");
        }

        GroupOrder newOrder = new GroupOrder();
        newOrder.setInitiator(order.getInitiator());
        newOrder.setStore(order.getStore());
        newOrder.setType(order.getType());
        newOrder.setStatus("OPEN");
        newOrder.setOrderNo(generateOrderNo());
        newOrder.setShareToken(UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        newOrder.setNote(order.getNote());

        BigDecimal total = BigDecimal.ZERO;
        groupOrderRepository.save(newOrder);

        for (OrderItem old : items) {
            OrderItem newItem = new OrderItem();
            newItem.setGroupOrder(newOrder);
            newItem.setUser(old.getUser());
            newItem.setProduct(old.getProduct());
            newItem.setSugarSnapshot(old.getSugarSnapshot());
            newItem.setIceSnapshot(old.getIceSnapshot());
            newItem.setSizeSnapshot(old.getSizeSnapshot());
            newItem.setPaymentStatus("UNPAID");
            newItem.setPaymentType(old.getPaymentType());
            newItem.setProductNameSnapshot(old.getProductNameSnapshot());
            newItem.setUnitPriceSnapshot(old.getUnitPriceSnapshot());
            newItem.setFinalPrice(old.getFinalPrice());
            newItem.setQty(old.getQty() > 0 ? old.getQty() : 1);
            newItem.setItemHash(null);

            if (old.getFinalPrice() != null) {
                total = total.add(old.getFinalPrice());
            }
            orderItemRepository.save(newItem);
        }

        newOrder.setTotalAmount(total);
        groupOrderRepository.save(newOrder);

        Map<String, Object> result = new HashMap<>();
        result.put("newOrderId", newOrder.getId());
        result.put("orderNo", newOrder.getOrderNo());
        result.put("message", "Reorder created");
        return result;
    }

    @Transactional
    public void cancelOrder(Long userId, Long orderId) {
        GroupOrder order = groupOrderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException("404", "Order not found"));
        if (order.getInitiator() == null || !Objects.equals(order.getInitiator().getId(), userId)) {
            throw new CustomException("403", "You cannot cancel this order");
        }
        if (!"OPEN".equals(order.getStatus())) {
            throw new CustomException("409", "Only open orders can be cancelled");
        }
        order.setStatus("CANCELLED");
        groupOrderRepository.save(order);
    }

    // ─── private helpers ──────────────────────────────────────
    private BigDecimal sumPrices(List<OrderItem> items) {
        return items.stream()
                .map(OrderItem::getFinalPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<Map<String, Object>> summarizeItems(List<OrderItem> items) {
        Map<String, Long> counts = items.stream()
                .collect(Collectors.groupingBy(
                        item -> item.getProductNameSnapshot() != null ? item.getProductNameSnapshot() : "Item",
                        LinkedHashMap::new,
                        Collectors.counting()));

        List<Map<String, Object>> result = new ArrayList<>();
        counts.forEach((name, qty) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("name", name);
            item.put("qty", qty);
            result.add(item);
        });
        return result;
    }
}
