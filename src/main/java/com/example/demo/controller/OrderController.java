package com.example.demo.controller;

import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.UserAddress;
import com.example.demo.service.OrderService;
import com.example.demo.repository.UserAddressRepository;
import com.example.demo.entity.User;
import com.example.demo.entity.OrderItemTopping;
import com.example.demo.entity.OrderItemToppingId;

import lombok.Data;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.example.demo.exception.CustomException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
@lombok.extern.slf4j.Slf4j
public class OrderController {

    private final OrderService orderService;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    private final com.example.demo.service.GroupOrderService groupOrderService;
    private final com.example.demo.service.TransactionRecordService transactionRecordService;
    private final com.example.demo.repository.OrderItemRepository orderItemRepository;
    private final com.example.demo.repository.GroupOrderRepository groupOrderRepository;

    private final com.example.demo.repository.OrderItemToppingRepository orderItemToppingRepository;
    private final UserAddressRepository userAddressRepository;
    private final com.example.demo.service.OrderRatingService orderRatingService;

    public OrderController(OrderService orderService,
            org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate,
            com.example.demo.service.GroupOrderService groupOrderService,
            com.example.demo.service.TransactionRecordService transactionRecordService,
            com.example.demo.repository.OrderItemRepository orderItemRepository,
            com.example.demo.repository.GroupOrderRepository groupOrderRepository,
            com.example.demo.repository.OrderItemToppingRepository orderItemToppingRepository,
            UserAddressRepository userAddressRepository,
            com.example.demo.service.OrderRatingService orderRatingService) {
        this.orderService = orderService;
        this.messagingTemplate = messagingTemplate;
        this.groupOrderService = groupOrderService;
        this.transactionRecordService = transactionRecordService;
        this.orderItemRepository = orderItemRepository;
        this.groupOrderRepository = groupOrderRepository;

        this.orderItemToppingRepository = orderItemToppingRepository;
        this.userAddressRepository = userAddressRepository;
        this.orderRatingService = orderRatingService;
    }

    /**
     * 身分一律取自 JwtAuthenticationFilter 寫入的 currentUserId，不可取自路徑或查詢參數。
     *
     * ⚠️ 這支 controller 原本整份沒有任何擁有權檢查：
     *  - GET /api/orders/user/{userId}/cards|active|recent-cards 直接吃路徑上的 userId，
     *    實測任一登入顧客即可讀取他人完整訂單歷史（金額、品項、甚至揪團的 groupToken）。
     *  - PUT /api/orders/{orderId}/status 寫成 `if (userId != null) 檢查 else 不檢查`，
     *    只要不帶 userId 就整段跳過，實測可把他人訂單從 READY 改成 COMPLETED。
     * 與 UserController.requireSelf 同一套規則，新增端點請照用。
     */
    private void requireSelf(Long targetUserId, Long currentUserId) {
        if (currentUserId == null || !currentUserId.equals(targetUserId))
            throw new CustomException("403", "無權存取其他使用者的訂單");
    }

    /** 確認這張訂單的發起人就是目前登入者 */
    private GroupOrder requireOwnedOrder(Long orderId, Long currentUserId) {
        GroupOrder order = orderService.getOrderById(orderId);
        if (order == null)
            throw new CustomException("404", "訂單不存在");
        Long initiatorId = order.getInitiator() != null ? order.getInitiator().getId() : null;
        requireSelf(initiatorId, currentUserId);
        return order;
    }

    @GetMapping("/user/{userId}/active")
    public ResponseEntity<List<Map<String, Object>>> getActiveOrders(@PathVariable Long userId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        List<com.example.demo.entity.GroupOrder> orders = groupOrderService.getActiveGroupOrders(userId);
        List<Map<String, Object>> result = orders.stream().map(order -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", order.getId());
            map.put("orderId", order.getId());
            map.put("orderNo", order.getOrderNo());
            map.put("storeName", order.getStore() != null ? order.getStore().getStoreName() : "未知店家");
            map.put("logoUrl",
                    order.getStore() != null && order.getStore().getBrand() != null
                            ? order.getStore().getBrand().getLogoUrl()
                            : null);
            map.put("totalAmount", order.getTotalAmount());
            map.put("status", order.getStatus());
            map.put("createdAt", order.getCreatedAt());
            map.put("type", order.getType());
            map.put("address", order.getAddress());
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "取得使用者訂單卡片 (分頁)", description = "取得使用者的訂單歷史卡片，支援分頁。會自動根據身份（團長/成員）計算顯示金額。")
    @GetMapping("/user/{userId}/cards")
    public ResponseEntity<?> getUserOrderCards(
            @PathVariable Long userId,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestParam(required = false) String statuses,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);

        startTime = System.currentTimeMillis();
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        org.springframework.data.domain.Page<GroupOrder> orderPage;
        if (statuses != null && !statuses.isBlank()) {
            List<String> statusList = java.util.Arrays.asList(statuses.split(","));
            orderPage = orderService.getOrdersByUserIdAndStatuses(userId, statusList, pageable);
        } else {
            orderPage = orderService.getOrdersByUserId(userId, pageable);
        }
        List<GroupOrder> orders = orderPage.getContent();

        if (orders.isEmpty()) {
            Map<String, Object> emptyResp = new HashMap<>();
            emptyResp.put("content", List.of());
            emptyResp.put("totalPages", 0);
            return ResponseEntity.ok(emptyResp);
        }

        List<Long> orderIds = orders.stream().map(GroupOrder::getId).collect(Collectors.toList());

        // 1. 批次抓取所有訂單的品項，解決 N+1 問題
        List<OrderItem> allItems = orderItemRepository.findByGroupOrderIdIn(orderIds);
        Map<Long, List<OrderItem>> itemsByOrderId = allItems.stream()
                .filter(i -> i.getGroupOrder() != null)
                .collect(Collectors.groupingBy(item -> item.getGroupOrder().getId()));

        List<com.example.demo.dto.OrderHistoryCardDTO> cards = orders.stream().map(order -> {
            com.example.demo.dto.OrderHistoryCardDTO card = new com.example.demo.dto.OrderHistoryCardDTO();
            card.setOrderId(order.getId());
            card.setStoreId(order.getStore() != null ? order.getStore().getId() : null);
            card.setStatus(order.getStatus());
            card.setGroup("GROUP".equals(order.getType()));
            if (card.isGroup())
                card.setGroupToken(order.getShareToken());

            // 時間格式化
            if (order.getCreatedAt() != null) {
                card.setOrderTime(
                        order.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            }

            // 店家資訊
            if (order.getStore() != null) {
                card.setStoreName(order.getStore().getStoreName());
                card.setStoreImageUrl(order.getStore().getCoverUrl());
            }

            // 身份判斷與金額計算
            boolean isHost = order.getInitiator() != null && order.getInitiator().getId().equals(userId);
            List<OrderItem> orderItems = itemsByOrderId.getOrDefault(order.getId(), List.of());

            if (isHost || !"GROUP".equals(order.getType())) {
                card.setFinalAmount(order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
            } else {
                // 作為成員：顯示個人的品項加總
                BigDecimal personalTotal = orderItems.stream()
                        .filter(i -> i.getUser() != null && i.getUser().getId().equals(userId))
                        .map(i -> {
                            BigDecimal fp = i.getFinalPrice() != null ? i.getFinalPrice() : BigDecimal.ZERO;
                            BigDecimal disc = i.getDiscountAmountSnapshot() != null ? i.getDiscountAmountSnapshot()
                                    : BigDecimal.ZERO;
                            return fp.subtract(disc).max(BigDecimal.ZERO);
                        })
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                card.setFinalAmount(personalTotal);
            }

            // 品項摘要 (由批次抓取的資料產生)
            String summary = orderItems.stream()
                    .map(item -> item.getProductNameSnapshot() != null ? item.getProductNameSnapshot() : "品項")
                    .distinct()
                    .limit(3)
                    .collect(Collectors.joining("，"));
            if (orderItems.size() > 3)
                summary += "...";
            card.setItemsSummary(summary);

            return card;
        }).collect(Collectors.toList());

        // 2. 批次查詢評分狀態，避免 N+1
        Map<Long, Integer> ratingScores = orderRatingService.getMyRatingScores(userId, orderIds);
        for (var card : cards) {
            card.setRatingSubmitted(ratingScores.containsKey(card.getOrderId()));
        }

        log.info("Optimized getUserOrderCards for user {} in {}ms", userId, System.currentTimeMillis() - startTime);

        Map<String, Object> result = new HashMap<>();
        result.put("content", cards);
        result.put("totalPages", orderPage.getTotalPages());
        result.put("totalElements", orderPage.getTotalElements());
        result.put("isLast", orderPage.isLast());

        return ResponseEntity.ok(result);
    }

    private long startTime;

    @GetMapping("/user/{userId}/recent-cards")
    public ResponseEntity<?> getRecentCards(
            @PathVariable Long userId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        // 快速獲取最近 10 筆，不支援分頁
        return getUserOrderCards(userId, 0, 10, null, currentUserId);
    }

    // 已移除 GET /api/orders/store/{storeId}：
    // 它是門市視角的列表，卻掛在 /api/orders/** 這條 CUSTOMER-only 的路徑下——門市根本呼叫不到，
    // 反而讓任一登入顧客能撈出該門市全部訂單，且直接回傳 GroupOrder entity，
    // 內含其他顧客的外送地址與揪團 shareToken（實測 13 筆全出）。
    // 門市要看訂單請用 GET /api/stores/orders（STORE 權限、有分頁）。

    @io.swagger.v3.oas.annotations.Operation(summary = "取得訂單詳情", description = "取得訂單詳情 API。回傳 ResponseEntity 格式。")
    @GetMapping("/{orderId}/v2")
    public ResponseEntity<?> getOrderByIdV2(@PathVariable Long orderId,
            @RequestParam(required = false) Long ignoredUserId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        // ignoredUserId 是用戶端自送的，只為相容既有前端呼叫而保留，實際一律以 token 為準。
        // 下面本來就會判斷「發起人或參與者」，用 currentUserId 就能正確擋住外人。
        if (currentUserId == null)
            throw new CustomException("403", "請先登入");
        final Long userId = currentUserId;
        GroupOrder order = orderService.getOrderByIdAndUserId(orderId, userId);
        if (order != null) {
            return ResponseEntity.ok(buildOrderResponse(order, userId));
        } else {
            // Check if user is a participant in the order via order items
            GroupOrder fullOrder = orderService.getOrderById(orderId);
            if (fullOrder != null) {
                boolean isParticipant = orderItemRepository.findByGroupOrderId(orderId).stream()
                        .anyMatch(item -> item.getUser() != null && item.getUser().getId().equals(userId));
                if (isParticipant) {
                    return ResponseEntity.ok(buildOrderResponse(fullOrder, userId));
                }
            }
            return ResponseEntity.status(403).build();
        }
    }

    private Map<String, Object> buildOrderResponse(GroupOrder order, Long userId) {
        List<OrderItem> items = orderItemRepository.findByGroupOrderId(order.getId());

        BigDecimal totalDiscount = items.stream()
                .map(i -> i.getDiscountAmountSnapshot() != null ? i.getDiscountAmountSnapshot() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal memberPaid = items.stream()
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

        Map<String, Object> result = new HashMap<>();
        result.put("id", order.getId());
        result.put("orderNo", order.getOrderNo());
        result.put("type", order.getType());
        result.put("status", order.getStatus());
        result.put("totalAmount", order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
        result.put("escrowAmount", order.getEscrowAmount());
        result.put("note", order.getNote());
        result.put("createdAt", order.getCreatedAt());
        result.put("submittedAt", order.getSubmittedAt());
        result.put("readyAt", order.getReadyAt());
        result.put("completedAt", order.getCompletedAt());
        result.put("isRejected", order.getIsRejected());
        result.put("totalDiscountAmount", totalDiscount);
        result.put("alreadyPaidAmount", memberPaid);
        if (order.getStore() != null) {
            result.put("storeId", order.getStore().getId());
            result.put("storeName", order.getStore().getStoreName());
        }
        if (order.getInitiator() != null) {
            result.put("initiatorId", order.getInitiator().getId());
            result.put("initiatorName", order.getInitiator().getName());
            result.put("initiatorPhone", order.getInitiator().getPhone());
        }
        result.put("shareToken", order.getShareToken());
        result.put("hostId", order.getInitiator() != null ? order.getInitiator().getId() : null);

        String deliveryAddress = order.getAddress() != null ? order.getAddress() : "";
        result.put("deliveryAddress", deliveryAddress);
        result.put("note", order.getNote() != null ? order.getNote() : "");
        result.put("deliveryType", deliveryAddress.isEmpty() ? "pickup" : "delivery");

        // Use totalAmount as finalAmount fallback, and provide deliveryFee
        result.put("finalAmount", order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
        result.put("deliveryFee", BigDecimal.ZERO); // Database doesn't have this field, default to 0

        result.put("orderTime", order.getCreatedAt());

        if (order.getStore() != null) {
            result.put("storeAddress", order.getStore().getAddress());
            result.put("storePhone", order.getStore().getStorePhone());
        }

        // Extract payment method from items
        if (!items.isEmpty()) {
            result.put("paymentMethod", items.get(0).getPaymentType() != null ? items.get(0).getPaymentType() : "CASH");
        } else {
            result.put("paymentMethod", "CASH");
        }
        return result;
    }

    @GetMapping("/{orderId}/items")
    public ResponseEntity<List<Map<String, Object>>> getOrderItems(@PathVariable Long orderId,
            @RequestParam(required = false) Long ignoredUserId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {

        // 身分取自 token；查詢參數的 userId 只保留相容性，不採信
        if (currentUserId == null)
            throw new CustomException("403", "請先登入");
        final Long userId = currentUserId;

        GroupOrder order = groupOrderRepository.findById(orderId).orElse(null);
        List<OrderItem> allItems = orderItemRepository.findByGroupOrderId(orderId);

        // 只有發起人或該團的參與者能看品項，否則任何人都能列舉他人訂單內容
        boolean isInitiator = order != null && order.getInitiator() != null
                && order.getInitiator().getId().equals(userId);
        boolean isParticipant = allItems.stream()
                .anyMatch(i -> i.getUser() != null && i.getUser().getId().equals(userId));
        if (order != null && !isInitiator && !isParticipant)
            throw new CustomException("403", "無權存取此訂單");

        // 分離出當前用戶應該看到的品項 (如果是揪團成員則看自己，否則看全部)
        List<OrderItem> visibleItems = allItems;
        if (userId != null && order != null && "GROUP".equals(order.getType())) {
            boolean isHost = order.getInitiator() != null && order.getInitiator().getId().equals(userId);
            if (!isHost) {
                visibleItems = allItems.stream()
                        .filter(item -> item.getUser() != null && item.getUser().getId().equals(userId))
                        .toList();
            }
        }

        // --- 批次抓取加料 (解決 N+1) ---
        List<Long> itemIds = visibleItems.stream().map(OrderItem::getId).toList();
        List<OrderItemTopping> allToppings = orderItemToppingRepository.findByOrderItemIdIn(itemIds);
        Map<Long, List<OrderItemTopping>> toppingsByItemId = allToppings.stream()
                .collect(Collectors.groupingBy(t -> t.getOrderItem().getId()));

        List<Map<String, Object>> result = visibleItems.stream().map(item -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", item.getId());
            m.put("orderId", orderId);
            m.put("productNameSnapshot", item.getProductNameSnapshot());
            m.put("unitPriceSnapshot", item.getUnitPriceSnapshot());
            m.put("paymentStatus", item.getPaymentStatus());
            
            BigDecimal disc = item.getDiscountAmountSnapshot() != null ? item.getDiscountAmountSnapshot() : BigDecimal.ZERO;
            BigDecimal fp = item.getFinalPrice() != null ? item.getFinalPrice() : BigDecimal.ZERO;
            
            // 如果是揪團成員視角，金額要扣掉折扣
            if (userId != null && order != null && "GROUP".equals(order.getType()) && !order.getInitiator().getId().equals(userId)) {
                m.put("finalPrice", fp.subtract(disc).max(BigDecimal.ZERO));
            } else {
                m.put("finalPrice", fp);
            }
            m.put("discountAmount", disc);
            
            m.put("iceSnapshot", item.getIceSnapshot());
            m.put("sugarSnapshot", item.getSugarSnapshot());
            m.put("sizeSnapshot", item.getSizeSnapshot());

            // 使用批次抓取的加料
            List<OrderItemTopping> itemToppings = toppingsByItemId.getOrDefault(item.getId(), List.of());
            m.put("toppings", itemToppings.stream().map(t -> {
                Map<String, Object> tm = new HashMap<>();
                tm.put("toppingName", t.getId().getToppingNameSnapshot());
                tm.put("toppingPrice", t.getToppingPriceSnapshot() != null ? t.getToppingPriceSnapshot() : BigDecimal.ZERO);
                return tm;
            }).toList());

            m.put("quantity", item.getQty()); // 使用 Entity 中的 qty 欄位
            m.put("productId", item.getProduct() != null ? item.getProduct().getId() : null);
            
            // 直接包含圖片網址，解決前端 N+1 補抓商品
            String logoUrl = null;
            if (item.getProduct() != null) {
                logoUrl = item.getProduct().getLogoUrl();
            }
            m.put("logoUrl", logoUrl);
            
            return m;
        }).toList();

        return ResponseEntity.ok(result);
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody CheckoutRequest request) {
        try {
            String deliveryAddress = null;
            if ("delivery".equals(request.getDeliveryType())) {
                deliveryAddress = request.getCity() + request.getDistrict() + request.getStreet();
            }
            // 將 OrderItemDTO 轉換為 OrderItem Entity (嚴禁更動 Entity，故在此手動映射)
            List<OrderItem> entities = request.getItems().stream().map(dto -> {
                OrderItem item = new OrderItem();

                // 核心關鍵: 手動建立 ProductTemplate 殼並填入 ID
                if (dto.getProductId() != null) {
                    com.example.demo.entity.ProductTemplate p = new com.example.demo.entity.ProductTemplate();
                    p.setId(dto.getProductId());
                    item.setProduct(p);
                }

                // 拷貝快照欄位
                item.setProductNameSnapshot(dto.getProductNameSnapshot());
                item.setUnitPriceSnapshot(dto.getUnitPriceSnapshot());
                item.setFinalPrice(dto.getFinalPrice());
                item.setIceSnapshot(dto.getIceSnapshot());
                item.setSugarSnapshot(dto.getSugarSnapshot());
                item.setSizeSnapshot(dto.getSizeSnapshot());
                item.setPaymentStatus(dto.getPaymentStatus());

                // 設定關聯 User (如果需要)
                if (dto.getUserId() != null) {
                    com.example.demo.entity.User u = new com.example.demo.entity.User();
                    u.setId(dto.getUserId());
                    item.setUser(u);
                }

                // 處理加料 (這部分需要 Service 支援，或在 Controller 處理)
                if (dto.getToppingNames() != null) {
                    List<OrderItemTopping> toppings = dto.getToppingNames().stream().map(name -> {
                        OrderItemTopping t = new OrderItemTopping();
                        OrderItemToppingId tid = new OrderItemToppingId();
                        tid.setToppingNameSnapshot(name);
                        t.setId(tid);
                        t.setOrderItem(item);
                        t.setToppingPriceSnapshot(BigDecimal.ZERO); // 簡化處理
                        return t;
                    }).collect(Collectors.toList());
                    item.setToppings(toppings);
                }

                return item;
            }).collect(java.util.stream.Collectors.toList());

            Long orderId = orderService.createOrder(
                    request.getUserId(),
                    request.getStoreId(),
                    request.getTotalAmount(),
                    request.getCouponId(),
                    entities,
                    request.getPaymentMethod(),
                    deliveryAddress,
                    request.getNote());

            // Handle Save Address
            if (Boolean.TRUE.equals(request.getSaveAddress()) && "delivery".equals(request.getDeliveryType())) {
                saveOrUpdateUserAddress(request);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Order created");
            response.put("orderId", orderId);

            GroupOrder createdOrder = orderService.getOrderById(orderId);
            BigDecimal finalAmount = createdOrder != null ? createdOrder.getTotalAmount() : BigDecimal.ZERO;
            response.put("finalAmount", finalAmount);

            // Notify merchant
            if (messagingTemplate != null) {
                Map<String, Object> orderData = new HashMap<>();
                orderData.put("id", orderId);
                orderData.put("finalAmount", finalAmount);
                orderData.put("paymentMethod", request.getPaymentMethod());
                messagingTemplate.convertAndSend("/topic/store/" + request.getStoreId(),
                        Map.of("type", "NEW_ORDER", "data", orderData));
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> errorResp = new HashMap<>();
            errorResp.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResp);
        }
    }

    @PutMapping("/{orderId}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long orderId, @RequestParam String status,
            @RequestParam(required = false) Long userId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        // ⚠️ 這裡原本是 `if (userId != null) 檢查 else 不檢查`，而 userId 是用戶端自送的選填參數，
        // 等於「不帶參數就免檢查」——實測任一登入顧客可把他人訂單從 READY 改成 COMPLETED。
        // 現在一律以 token 身分驗證發起人，沒有繞過分支。
        // 門市要改狀態請走 /api/stores/dashboard/orders/{id}/accept|reject|complete-production|finalize。
        requireOwnedOrder(orderId, currentUserId);
        GroupOrder updatedOrder = orderService.updateOrderStatusWithUserCheck(orderId, status, currentUserId);
        // 1. Notify store if order was just paid/submitted
        if ("PAID".equalsIgnoreCase(status) || "SUBMITTED".equalsIgnoreCase(status)) {
            Map<String, Object> orderData = new HashMap<>();
            orderData.put("id", updatedOrder.getId());
            orderData.put("finalAmount", updatedOrder.getTotalAmount());
            orderData.put("paymentMethod", "CASH");
            Long storeId = updatedOrder.getStore() != null ? updatedOrder.getStore().getId() : null;
            if (storeId != null && messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/store/" + storeId,
                        Map.of("type", "NEW_ORDER", "data", orderData));
            }
        }

        // 2. Notify order status change to customer (real-time sync)
        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/order/" + orderId, Map.of("status", status));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("id", updatedOrder.getId());
        result.put("status", updatedOrder.getStatus());
        result.put("totalAmount", updatedOrder.getTotalAmount());
        return ResponseEntity.ok(result);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "取消訂單 (V2)", description = "舊版取消訂單 API。回傳 ResponseEntity 格式。供特定舊版前端或情境使用。")
    @PutMapping("/{orderId}/cancel/v2")
    public ResponseEntity<?> cancelOrderV2(@PathVariable Long orderId,
            @RequestParam(required = false) Long ignoredUserId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        // 同 updateStatus：原本的 `if (userId != null)` 讓「不帶參數」直接跳過權限檢查，
        // 等於任何人都能取消他人訂單並觸發退款。身分改為一律取自 token。
        if (currentUserId == null)
            throw new CustomException("403", "請先登入");
        final Long userId = currentUserId;
        try {
            GroupOrder order = orderService.getOrderById(orderId);
            if (order == null)
                return ResponseEntity.notFound().build();

            String currentStatus = order.getStatus();
            if (!"OPEN".equals(currentStatus) && !"SUBMITTED".equals(currentStatus)) {
                return ResponseEntity.badRequest().body(Map.of("error", "訂單狀態無法取消"));
            }

            // 1. 權限檢查：只有團主或是個人訂單擁有者可以取消整份訂單
            if (userId != null) {
                boolean isGroupOrder = "GROUP".equals(order.getType());
                Long initiatorId = order.getInitiator() != null ? order.getInitiator().getId() : null;

                if (isGroupOrder && !Objects.equals(initiatorId, userId)) {
                    // 成員取消個人品項 (Partial Cancellation)
                    groupOrderService.cancelMemberItems(orderId, userId);
                    return ResponseEntity.ok(Map.of("success", true, "message", "個人專屬品項已成功取消，退款已處理完畢。"));
                } else if (!Objects.equals(initiatorId, userId)) {
                    return ResponseEntity.status(403).body(Map.of("error", "無權限取消此訂單"));
                }
            }

            // 2. 執行集中式取消與退款邏輯 (涵蓋所有錢包退款與優惠券還原)
            boolean success = groupOrderService.handleGroupOrderCancellation(orderId);

            if (!success) {
                return ResponseEntity.badRequest().body(Map.of("error", "取消失敗"));
            }

            // 3. 通知前端狀態更新
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/order/" + orderId, Map.of("status", "CANCELLED"));
            }

            return ResponseEntity.ok(Map.of("id", orderId, "status", "CANCELLED", "message", "訂單已成功取消並退款"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private void saveOrUpdateUserAddress(CheckoutRequest request) {
        if (userAddressRepository == null)
            return;

        List<UserAddress> addresses = userAddressRepository.findByUserId(request.getUserId());
        UserAddress tempTarget = addresses.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsDefault()))
                .findFirst()
                .orElse(null);

        if (tempTarget == null) {
            tempTarget = new UserAddress();
            User user = new User();
            user.setId(request.getUserId());
            tempTarget.setUser(user);
            tempTarget.setLabel("預設地址");
        }

        final UserAddress target = tempTarget;
        String fullAddress = request.getCity() + request.getDistrict() + request.getStreet();
        target.setAddressName(fullAddress);
        target.setIsDefault(true);
        target.setCreatedAt(LocalDateTime.now());

        // Unset other defaults
        addresses.forEach(a -> {
            if (!a.equals(target))
                a.setIsDefault(false);
        });
        if (!addresses.isEmpty()) {
            userAddressRepository.saveAll(addresses);
        }
        userAddressRepository.save(target);
    }

    @GetMapping("")
    public ResponseEntity<?> getOrders(@RequestParam(required = false) Long userId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        // 一律回傳「自己的」訂單；查詢參數的 userId 不採信（否則就是列舉他人訂單的入口）
        return getUserOrderCards(currentUserId, 0, 20, null, currentUserId);
    }

    @Data
    private static class CheckoutRequest {
        private Long userId;
        private Long storeId;
        private BigDecimal totalAmount;
        private Long couponId;
        private List<OrderItemDTO> items;
        private String paymentMethod;
        private String deliveryType;
        private String city;
        private String district;
        private String street;
        private Boolean saveAddress;
        private String note;
    }

    /**
     * 接取前端品項資料的 DTO，避免直接修改 OrderItem Entity 造成的映射問題
     */
    @Data
    private static class OrderItemDTO {
        private Long productId;
        private Long userId;
        private Integer quantity;
        private String productNameSnapshot;
        private BigDecimal unitPriceSnapshot;
        private BigDecimal finalPrice;
        private String iceSnapshot;
        private String sugarSnapshot;
        private List<String> toppingNames;
        private Long brandSpecId;
        private String sizeSnapshot;
        private String paymentStatus;
    }
}