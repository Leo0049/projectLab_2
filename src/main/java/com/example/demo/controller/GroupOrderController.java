package com.example.demo.controller;

import com.example.demo.common.Result;
import com.example.demo.dto.GroupOrderDTO;
import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.OrderItem;
import com.example.demo.service.GroupOrderService;
import com.example.demo.service.IdempotencyService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    @Autowired
    private IdempotencyService idempotencyService;

    /**
     * 團長與團員結帳接受的付款方式，須與團購頁實際送出的值一致。
     * STORE_CREDIT 由前端映射為 WALLET，不在此列；團購頁已隱藏「信用卡」、不再送出 CREDIT_CARD（N-22）；後端仍接受它並視為門市付款。
     * 不可只留 WALLET／CASH：N-18 曾因此擋下團長與團員當時的預設 CREDIT_CARD；保留它是為了不擋下仍在舊版頁面上的請求。
     */
    private static final Set<String> ALLOWED_PAYMENT_METHODS = Set.of("WALLET", "CASH", "CREDIT_CARD");

    private static final String PAYMENT_METHOD_NOT_ALLOWED = "paymentMethod 僅接受 WALLET、CASH 或 CREDIT_CARD";

    private static boolean isAllowedPaymentMethod(String paymentMethod) {
        return paymentMethod != null && ALLOWED_PAYMENT_METHODS.contains(paymentMethod);
    }

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
        // 身分只認 token。查詢參數的 userId 為相容舊前端而保留，不採信。
        Long finalUserId = getUserId(request);
        if (finalUserId == null)
            return Result.error("Unauthorized");

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
            log.error("取得進行中揪團失敗", e);
            throw e;
        }
    }

    // ⚠️ 已移除 GET /api/public/debug-exception。
    //    它位於 permitAll 的 /api/public/** 之下，可傳入任意 userId 查詢他人的進行中揪團，
    //    且發生例外時會把完整 stack trace 直接回傳給呼叫端。

    @Operation(summary = "透過 token 取得揪團資訊",
            description = "團內資料（品項、地址、備註、金額、團長、分享 token）只給團長與團員。其他人（含未登入）只看到店家、狀態與建立時間，須先加入揪團才能檢視。")
    @GetMapping({ "/api/group-orders/join/{token}", "/api/group-orders/{token}" })
    public Result getByToken(HttpServletRequest request, @PathVariable String token) {
        try {
            GroupOrderDTO dto = groupOrderService.getGroupOrderDTOByToken(token, getUserId(request));
            return Result.success(dto);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "加入揪團並新增品項", description = "需提供對應揪團的 shareToken。Body: { shareToken, items }")
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
    public Result getGroupDetail(HttpServletRequest request, @PathVariable Long groupOrderId) {
        Long userId = getUserId(request);
        if (userId == null) return Result.error("Unauthorized");
        return Result.success(groupOrderService.getGroupDetail(userId, groupOrderId));
    }

    @Operation(summary = "揪團付款統計", description = "取得已付款/未付款金額統計，用於結帳頁顯示。")
    @GetMapping("/api/group-orders/{groupOrderId}/summary")
    public Result getGroupSummary(HttpServletRequest request, @PathVariable Long groupOrderId) {
        Long userId = getUserId(request);
        if (userId == null) return Result.error("Unauthorized");
        return Result.success(groupOrderService.getGroupSummary(userId, groupOrderId));
    }

    @Operation(summary = "取得揪團分享資訊", description = "取得分享連結與 QR Code URL，供分享至 LINE、Messenger 等。")
    @GetMapping("/api/group-orders/{groupOrderId}/share")
    public Result getShareInfo(HttpServletRequest request, @PathVariable Long groupOrderId) {
        Long userId = getUserId(request);
        if (userId == null) return Result.error("Unauthorized");
        return Result.success(groupOrderService.getShareInfo(userId, groupOrderId));
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
    public ResponseEntity<?> createGroupOrderV2(@RequestBody CreateRequest request,
            HttpServletRequest httpRequest) {
        try {
            // 團長就是登入者，body 的 hostId 不採信
            Long uid = getUserId(httpRequest);
            if (uid == null)
                return ResponseEntity.status(403).body(Map.of("error", "請先登入"));
            GroupOrder go = groupOrderService.createGroupOrderV2(uid, request.getStoreId());
            return ResponseEntity.ok(groupOrderService.convertToDTO(go));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/api/group-orders/token/{token}")
    public ResponseEntity<?> deleteGroupOrder(@PathVariable String token,
            @RequestParam(required = false) Long hostId, HttpServletRequest httpRequest) {
        try {
            // ⚠️ hostId 原本取自查詢參數，而 Service 只是拿它去比對團長——
            // 等於「填上真正團長的 id 就能刪掉別人的團」，且會連帶觸發整團退款。
            // 查詢參數保留只為相容舊前端，一律不採信。
            Long uid = getUserId(httpRequest);
            if (uid == null)
                return ResponseEntity.status(403).body(Map.of("error", "請先登入"));
            groupOrderService.deleteGroupOrder(token, uid);
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
    public ResponseEntity<?> addItem(@PathVariable String token, @RequestBody Map<String, Object> item,
            HttpServletRequest request) {
        try {
            // ⚠️ M-2 修復：userId 只認 JWT。舊版直接取 body 的 userId，
            //    可把品項掛到他人身上（讓受害者被代墊或被嫁禍）。
            Long uid = getUserId(request);
            if (uid == null)
                return ResponseEntity.status(403).body(Map.of("error", "請先登入"));
            OrderItem savedItem = groupOrderService.addItem(token, item, uid);
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
                return ResponseEntity.status(403).body(Map.of("error", "請先登入"));

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
                return ResponseEntity.status(403).body(Map.of("error", "請先登入"));

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
            @RequestBody ApplyCouponRequest request, HttpServletRequest httpRequest) {
        try {
            // ⚠️ 這支原本完全沒看 token，userId 直接取自 request body。
            // itemId 與 couponId 都是連續整數，實測可以把「別人的券」套到自己的品項上——
            // 受害者的券變成 used、折扣算在攻擊者頭上。
            Long uid = getUserId(httpRequest);
            if (uid == null)
                return ResponseEntity.status(403).body(Map.of("error", "請先登入"));
            groupOrderService.applyCouponToItem(token, itemId, uid, request.getCouponId());
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
            HttpServletRequest request) {
        // ⚠️ H-3 修復：移除「取不到登入身分就退回 query 參數 hostId」的 fallback——
        //    那等於帶上真團長的 id 就能改別人的揪團狀態（S-2/S-6 同型漏洞殘留）。
        //    身分只認 JWT；status 白名單見 Service。前端多送的 hostId 參數會被 Spring 忽略。
        Long finalHostId = getUserId(request);
        if (finalHostId == null)
            return ResponseEntity.status(403).body(Map.of("error", "請先登入"));

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
            @RequestBody CheckoutRequest request,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        // ⚠️ 身分只認 token。原本寫成「取不到就改用 body 的 hostId／userId」，
        // 那正是 S-2／S-6 的成因：只要讓前者取不到，檢查就整段被跳過。
        Long resolvedHostId = getUserId(httpRequest);
        if (resolvedHostId == null)
            return ResponseEntity.status(403).body(Map.of("error", "請先登入"));

        // ⚠️ 付款方式不可為空。舊版未驗證：paymentMethod 為 null 時結帳會先成功提交，之後回應組裝的
        //    Map.of 遇到 null 直接 NPE，客戶端看到失敗而重送，又因已結帳而回 409。
        //    只接受 ALLOWED_PAYMENT_METHODS 內的值，任意字串會被當成非錢包付款寫入。STORE_CREDIT 由前端映射為 WALLET；
        //    CASH／CREDIT_CARD 的已付款語意維持原行為，屬於金流產品決定（見審查報告 N-15、N-22）。
        String paymentMethod = request.getPaymentMethod();
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "paymentMethod 不可為空"));
        }
        if (!isAllowedPaymentMethod(paymentMethod)) {
            return ResponseEntity.badRequest().body(Map.of("error", PAYMENT_METHOD_NOT_ALLOWED));
        }

        // 冪等鍵是選用的（ADR-0005）：沒有帶就照舊執行；帶了就保證同一把鍵只會真的結帳一次。
        // 鍵的範圍包含團的 token：同一把鍵用在另一團時，不會被當成重送而回放別團的結果。
        if (idempotencyKey == null) {
            return placeHostCheckout(token, request, resolvedHostId);
        }
        return idempotencyService.execute("group-checkout", resolvedHostId, idempotencyKey, request,
                () -> placeHostCheckout(token, request, resolvedHostId));
    }

    /**
     * 團長結帳的實際流程：送單（扣款或代墊、套用優惠券）、回傳金額、推播通知。
     *
     * <p>交易邊界：{@code groupOrderService.checkout} 自己是一個交易，回傳時結帳已經提交。
     * 所以提交之後的步驟（讀取通知資訊、推播）失敗時只記 log，照常回傳成功。原本它們與送單共用同一個 try，
     * 任一失敗都回 400：前端會當成失敗，但送單其實已完成；重送即使帶了冪等鍵也只會得到 400
     * （佔位已釋放，第二次送單被服務層拒絕），無法回放成功的結果。
     *
     * <p>提交之前的失敗（業務拒絕等）回 400。沒有提交，所以冪等佔位會釋放，同一把鍵可以重試。
     */
    private ResponseEntity<?> placeHostCheckout(String token, CheckoutRequest request, Long hostId) {
        // 優先使用單一 address 欄位，若無則組合 city/district/street
        String fullAddress = request.getAddress();
        if (fullAddress == null || fullAddress.isBlank()) {
            fullAddress = (request.getCity() != null)
                    ? (request.getCity() + request.getDistrict() + request.getStreet())
                    : "";
        }
        String paymentMethod = request.getPaymentMethod();

        Long orderId;
        try {
            orderId = groupOrderService.checkout(token, hostId,
                    request.getCouponId(), paymentMethod, fullAddress, request.getNote());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", Objects.toString(e.getMessage(), "結帳失敗")));
        }

        // 以下結帳已提交：讀取金額或推播失敗只記 log，回應仍為成功。
        BigDecimal totalAmount = null;
        try {
            // 通知所需資訊在交易內讀出：控制器不拿 entity 出去再 getStore()（見 CLAUDE.md「open-in-view」）
            Map<String, Object> info = groupOrderService.getCheckoutNotifyInfo(token);
            Long storeId = (Long) info.get("storeId");
            totalAmount = (BigDecimal) info.get("totalAmount");
            if (messagingTemplate != null && storeId != null) {
                messagingTemplate.convertAndSend("/topic/store/" + storeId,
                        Map.of("type", "NEW_ORDER", "data", newOrderData(orderId, totalAmount, paymentMethod)));
            }
        } catch (RuntimeException e) {
            log.error("揪團結帳已提交（orderId={}），門市通知未送出；回應仍為成功", orderId, e);
        }
        try {
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/group/" + token,
                        Map.of("orderId", orderId, "paymentMethod", paymentMethod));
            }
        } catch (RuntimeException e) {
            log.error("揪團結帳已提交（orderId={}），揪團推播未送出；回應仍為成功", orderId, e);
        }

        // LinkedHashMap：totalAmount 讀取失敗時為 null，而 Map.of 不接受 null 值
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Group Order checked out successfully");
        body.put("orderId", orderId);
        body.put("totalAmount", totalAmount);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> newOrderData(Long orderId, BigDecimal totalAmount, String paymentMethod) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", orderId);
        data.put("totalAmount", totalAmount);
        data.put("paymentMethod", paymentMethod);
        return data;
    }

    @Operation(summary = "依據訂單 ID 取得揪團詳情", description = "供訂單確認頁面使用。")
    @GetMapping("/api/group-orders/by-order/{orderId}")
    public Result getGroupByOrderId(HttpServletRequest request, @PathVariable Long orderId) {
        Long userId = getUserId(request);
        if (userId == null) return Result.error("Unauthorized");
        // 轉 DTO 必須在 service 的交易內完成，不可把 entity 帶出來再轉（見 service 內說明）
        return groupOrderService.getGroupOrderDTOByOrderId(orderId, userId)
                .map(Result::success)
                .orElse(Result.error("找不到對應的揪團資訊"));
    }

    @PostMapping("/api/group-orders/{token}/member-checkout")
    public ResponseEntity<?> memberCheckout(
            @PathVariable String token,
            @RequestBody MemberCheckoutRequest request,
            HttpServletRequest httpRequest) {
        try {
            Long uid = getUserId(httpRequest);
            if (uid == null)
                return ResponseEntity.status(403).body(Map.of("error", "請先登入"));

            // 白名單同團長結帳。任意字串會被當成非錢包付款而標記為已付款，故一律拒絕。
            if (!isAllowedPaymentMethod(request.getPaymentMethod())) {
                return ResponseEntity.badRequest().body(Map.of("error", PAYMENT_METHOD_NOT_ALLOWED));
            }

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
            if (uid == null)
                return ResponseEntity.status(403).body(Map.of("error", "請先登入"));

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
    // package-private：GroupOrderCheckoutIdempotencyTest 需要建構請求主體
    static class CheckoutRequest {
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
