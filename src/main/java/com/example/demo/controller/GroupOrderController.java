package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.GroupOrderDTO;
import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.OrderItem;
import com.example.demo.service.GroupOrderService;
import com.example.demo.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Tag(name = "GroupOrder / 揪團", description = "揪團訂單管理。團長建立 → 分享連結 → 團員加入 → 團長送單。")
@RestController
@Slf4j
public class GroupOrderController {

    @Autowired
    private GroupOrderService groupOrderService;

    @Autowired(required = false)
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    @Autowired
    private com.example.demo.service.RedisCartService redisCartService;

    @Autowired(required = false)
    private OrderService orderService;

    private Long getUserId(HttpServletRequest request) {
        Object userIdObj = request.getAttribute("currentUserId");
        if (userIdObj instanceof Number) {
            return ((Number) userIdObj).longValue();
        }
        return null;
    }

    // ============================================================
    // DTO-based methods
    // ============================================================

    @Operation(summary = "建立揪團訂單", description = "團長建立揪團，取得分享連結與 QR Code token。\n\nBody: { storeId, type: GROUP|SOLO }")
    @PostMapping("/api/group-orders")
    public Result createGroupOrder(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        Long uid = getUserId(request);
        if (uid == null)
            return Result.error("Unauthorized");
        return Result.success(groupOrderService.createGroupOrder(uid, req));
    }

    @Operation(summary = "取得進行中的揪團", description = "根據用戶與分店取得活躍中的揪團資訊。若 storeId 為空，則回傳該用戶所有活躍中的揪團列表。")
    @GetMapping("/api/group-orders/active")
    public Result getActiveOrders(HttpServletRequest request,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long storeId) {
        // 優先從 Filter 取得 UserId，若無則從參數取得 (相容舊有前端呼叫方式)
        Long finalUserId = getUserId(request);
        if (finalUserId == null)
            finalUserId = userId;

        if (finalUserId == null)
            return Result.error("Unauthorized or missing userId");

        try {
            if (storeId != null) {
                GroupOrder go = groupOrderService.getActiveGroupOrder(finalUserId, storeId);
                if (go == null)
                    return Result.error("No active group order found for the given store");
                return Result.success(groupOrderService.convertToDTO(go));
            } else {
                List<GroupOrder> activeOrders = groupOrderService.getActiveGroupOrders(finalUserId);
                List<GroupOrderDTO> dtos = groupOrderService.convertToDTOList(activeOrders);
                return Result.success(dtos);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    // ⚠️ 已移除 GET /api/public/debug-exception。
    //    它位於 permitAll 的 /api/public/** 之下，可傳入任意 userId 查詢他人的進行中揪團，
    //    且發生例外時會把完整 stack trace 直接回傳給呼叫端。

    @Operation(summary = "透過 token 取得揪團資訊", description = "取得揪團基本資訊與品項列表(視覺合併後)。")
    @GetMapping({ "/api/group-orders/join/{token}", "/api/group-orders/{token}" })
    public Result getByToken(@PathVariable String token) {
        try {
            GroupOrderDTO dto = groupOrderService.getGroupOrderDTOByToken(token);
            return Result.success(dto);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "加入揪團並新增品項", description = "Body:\n```json\n{\n  \"items\": [\n    { \"productId\": 1, \"sugar\": \"微糖\", \"ice\": \"少冰\", \"paymentType\": \"WALLET\" }\n  ]\n}\n```")
    @PostMapping("/api/group-orders/{groupOrderId}/join")
    public Result joinGroup(HttpServletRequest request,
            @PathVariable Long groupOrderId,
            @RequestBody Map<String, Object> req) {
        Long uid = getUserId(request);
        if (uid == null)
            return Result.error("Unauthorized");
        return Result.success(groupOrderService.joinGroup(uid, groupOrderId, req));
    }

    @Operation(summary = "取得揪團成員與品項列表", description = "顯示所有團員的點餐內容與付款狀態。")
    @GetMapping("/api/group-orders/{groupOrderId}/members")
    public Result getGroupDetail(@PathVariable Long groupOrderId) {
        return Result.success(groupOrderService.getGroupDetail(groupOrderId));
    }

    @Operation(summary = "揪團付款統計", description = "取得已付款/未付款金額統計，用於結帳頁顯示。")
    @GetMapping("/api/group-orders/{groupOrderId}/summary")
    public Result getGroupSummary(@PathVariable Long groupOrderId) {
        return Result.success(groupOrderService.getGroupSummary(groupOrderId));
    }

    @Operation(summary = "取得揪團分享資訊", description = "取得分享連結與 QR Code URL，供分享至 LINE、Messenger 等。")
    @GetMapping("/api/group-orders/{groupOrderId}/share")
    public Result getShareInfo(@PathVariable Long groupOrderId) {
        return Result.success(groupOrderService.getShareInfo(groupOrderId));
    }

    @Operation(summary = "團長送單", description = "團長確認送出訂單至店家。訂單狀態從 OPEN → SUBMITTED。")
    @PostMapping("/api/group-orders/{groupOrderId}/submit")
    public Result submitGroupOrder(HttpServletRequest request,
            @PathVariable Long groupOrderId,
            @RequestBody(required = false) Map<String, Object> req) {
        Long uid = getUserId(request);
        if (uid == null)
            return Result.error("Unauthorized");
        return Result.success(groupOrderService.submitGroupOrder(uid, groupOrderId, req));
    }

    @Operation(summary = "取消揪團", description = "團長取消揪團，訂單狀態從 OPEN → CANCELLED。已送出的訂單無法取消。")
    @DeleteMapping("/api/group-orders/{groupOrderId}")
    public Result cancelGroupOrder(HttpServletRequest request, @PathVariable Long groupOrderId) {
        Long uid = getUserId(request);
        if (uid == null)
            return Result.error("Unauthorized");
        groupOrderService.cancelGroupOrder(uid, groupOrderId);
        return Result.success("揪團已取消");
    }

    @PostMapping("/api/group-orders/v2")
    public ResponseEntity<?> createGroupOrderV2(@RequestBody CreateRequest request) {
        try {
            GroupOrder go = groupOrderService.createGroupOrderV2(request.getHostId(), request.getStoreId());
            return ResponseEntity.ok(groupOrderService.convertToDTO(go));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/api/group-orders/token/{token}")
    public ResponseEntity<?> deleteGroupOrder(@PathVariable String token, @RequestParam Long hostId) {
        try {
            groupOrderService.deleteGroupOrder(token, hostId);
            redisCartService.clearCart(token);
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/group/" + token,
                        Map.of("type", "GROUP_DELETED", "message", "Group cart deleted"));
            }
            return ResponseEntity.ok(Map.of("message", "Group order deleted permanently"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/group-orders/{token}/items")
    public ResponseEntity<?> addItem(@PathVariable String token, @RequestBody Map<String, Object> item) {
        try {
            OrderItem savedItem = groupOrderService.addItem(token, item);
            redisCartService.saveItem(token, savedItem);
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/group/" + token,
                        Map.of("type", "CART_UPDATED", "message", "Item added", "itemId", savedItem.getId()));
            }
            return ResponseEntity.ok(Map.of("id", savedItem.getId(), "status", "success"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/api/group-orders/{token}/items/{itemId}")
    public ResponseEntity<?> removeItem(@PathVariable String token, @PathVariable Long itemId,
            HttpServletRequest request, @RequestParam(required = false) Long userId) {
        try {
            Long finalUserId = getUserId(request);
            if (finalUserId == null)
                finalUserId = userId;
            if (finalUserId == null)
                return ResponseEntity.badRequest().body(Map.of("error", "UserId is required"));

            groupOrderService.removeItem(token, itemId, finalUserId);
            // 移除品項時清空快照，確保前端重新載入時計算正確杯數
            redisCartService.clearCart(token);
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/group/" + token,
                        Map.of("type", "CART_UPDATED", "message", "Item removed", "itemId", itemId));
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/api/group-orders/{token}/items/{itemId}")
    public ResponseEntity<?> updateItem(@PathVariable String token, @PathVariable Long itemId,
            HttpServletRequest request, @RequestBody Map<String, Object> req,
            @RequestParam(required = false) Long userId) {
        try {
            Long finalUserId = getUserId(request);
            if (finalUserId == null)
                finalUserId = userId;
            if (finalUserId == null)
                return ResponseEntity.badRequest().body(Map.of("error", "UserId is required"));

            OrderItem updated = groupOrderService.updateItem(token, itemId, req, finalUserId);
            // 當執行數量調整（批量增減）時，清空 Redis 快照以確保回傳總數量正確
            redisCartService.clearCart(token);
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/group/" + token,
                        Map.of("type", "CART_UPDATED", "message", "Item updated", "itemId", updated.getId()));
            }
            return ResponseEntity.ok(Map.of("id", updated.getId(), "status", "success"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/group-orders/{token}/items/{itemId}/apply-coupon")
    public ResponseEntity<?> applyCoupon(@PathVariable String token, @PathVariable Long itemId,
            @RequestBody ApplyCouponRequest request) {
        try {
            groupOrderService.applyCouponToItem(token, itemId, request.getUserId(), request.getCouponId());
            // 使用優惠券後清空快照
            redisCartService.clearCart(token);
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/group/" + token,
                        Map.of("type", "CART_UPDATED", "message", "Coupon applied to item", "itemId", itemId));
            }
            return ResponseEntity.ok(Map.of("message", "Coupon applied successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/api/group-orders/{token}/status")
    public ResponseEntity<?> setStatus(@PathVariable String token, @RequestParam String status,
            HttpServletRequest request, @RequestParam(required = false) Long hostId) {
        Long finalHostId = getUserId(request);
        if (finalHostId == null)
            finalHostId = hostId;
        if (finalHostId == null)
            return ResponseEntity.badRequest().body(Map.of("error", "HostId is required"));

        GroupOrder updatedOrder = groupOrderService.setStatus(token, status, finalHostId);
        // 狀態變更時（如截單）清空快照
        redisCartService.clearCart(token);
        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/group/" + token,
                    Map.of("type", "STATUS_CHANGED", "status", status));
        }
        return ResponseEntity.ok(Map.of("id", updatedOrder.getId(), "status", updatedOrder.getStatus()));
    }

    @PostMapping("/api/group-orders/{token}/checkout")
    public ResponseEntity<?> checkout(@PathVariable String token, HttpServletRequest httpRequest,
            @RequestBody CheckoutRequest request) {
        try {
            // 優先從 JWT Filter 取得認證身分，再 fallback 至 body 中的 hostId / userId
            Long resolvedHostId = getUserId(httpRequest);
            if (resolvedHostId == null) resolvedHostId = request.getHostId();
            if (resolvedHostId == null) resolvedHostId = request.getUserId();
            if (resolvedHostId == null)
                return ResponseEntity.badRequest().body(Map.of("error", "HostId is required"));

            // 優先使用單一 address 欄位，若無則組合 city/district/street
            String fullAddress = request.getAddress();
            if (fullAddress == null || fullAddress.isBlank()) {
                fullAddress = (request.getCity() != null)
                        ? (request.getCity() + request.getDistrict() + request.getStreet())
                        : "";
            }
            Long orderId = groupOrderService.checkout(token, resolvedHostId,
                    request.getCouponId(), request.getPaymentMethod(), fullAddress, request.getNote());
            GroupOrder go = groupOrderService.getGroupOrderByToken(token);

            if (messagingTemplate != null && go.getStore() != null) {
                messagingTemplate.convertAndSend("/topic/store/" + go.getStore().getId(),
                        Map.of("type", "NEW_ORDER", "data", Map.of("id", orderId, "totalAmount", go.getTotalAmount(),
                                "paymentMethod", request.getPaymentMethod())));
            }
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/group/" + token,
                        Map.of("orderId", orderId, "paymentMethod", request.getPaymentMethod()));
            }
            return ResponseEntity.ok(Map.of("message", "Group Order checked out successfully", "orderId", orderId,
                    "totalAmount", go.getTotalAmount()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "依據訂單 ID 取得揪團詳情", description = "供訂單確認頁面使用。")
    @GetMapping("/api/group-orders/by-order/{orderId}")
    public Result getGroupByOrderId(@PathVariable Long orderId) {
        return groupOrderService.getGroupOrderByOrderId(orderId)
                .map(go -> Result.success(groupOrderService.convertToDTO(go)))
                .orElse(Result.error("找不到對應的揪團資訊"));
    }

    @PostMapping("/api/group-orders/{token}/member-checkout")
    public ResponseEntity<?> memberCheckout(
            @PathVariable String token,
            @RequestBody MemberCheckoutRequest request,
            HttpServletRequest httpRequest) {
        try {
            Long uid = getUserId(httpRequest);
            if (uid == null) uid = request.getUserId();
            if (uid == null)
                return ResponseEntity.badRequest().body(Map.of("error", "Unauthorized"));

            BigDecimal totalAmount = groupOrderService.getMemberUnpaidTotalAndMarkPaid(token, uid,
                    request.getPaymentMethod(), request.getCouponId());
            // 個人結帳後清空快照以更新付款狀態
            redisCartService.clearCart(token);
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/group/" + token,
                        Map.of("type", "CART_UPDATED", "message", "Member payment completed", "userId", uid));
            }
            return ResponseEntity.ok(Map.of("message", "Member items marked as paid", "totalAmount", totalAmount));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/group-orders/{token}/repay")
    public ResponseEntity<?> repay(
            @PathVariable String token,
            @RequestBody RepayRequest request,
            HttpServletRequest httpRequest) {
        try {
            Long uid = getUserId(httpRequest);
            if (uid == null) uid = request.getUserId();
            if (uid == null)
                return ResponseEntity.badRequest().body(Map.of("error", "Unauthorized"));

            groupOrderService.repayToHost(token, uid);
            // 補款後清空快照
            redisCartService.clearCart(token);
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/group/" + token,
                        Map.of("type", "CART_UPDATED", "message", "Member repay completed", "userId", uid));
            }
            return ResponseEntity.ok(Map.of("message", "Repayment successful"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @Data
    private static class RepayRequest {
        private Long userId;
    }

    @Data
    private static class CreateRequest {
        private Long hostId;
        private Long storeId;
    }

    @Data
    private static class CheckoutRequest {
        private Long hostId;      // 舊欄位，保留相容性
        private Long userId;      // 前端實際送出的欄位名稱
        private Long couponId;
        private String paymentMethod;
        private String city;
        private String district;
        private String street;
        private String address;
        private String note;
    }

    @Data
    private static class ApplyCouponRequest {
        private Long userId;
        private Long couponId;
    }

    @Data
    private static class MemberCheckoutRequest {
        private Long userId;
        private String paymentMethod;
        private Long couponId;
    }
}
