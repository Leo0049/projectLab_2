package com.example.demo.service;

import com.example.demo.entity.GroupOrder;
import com.example.demo.entity.OrderRating;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.Store;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.GroupOrderRepository;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRatingRepository;
import com.example.demo.repository.StoreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class OrderRatingService {

    @Autowired
    private OrderRatingRepository orderRatingRepository;
    @Autowired
    private GroupOrderRepository groupOrderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private StoreRepository storeRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> getMyRating(Long userId, Long orderId) {
        GroupOrder order = groupOrderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException("404", "Order not found"));

        boolean isInitiator = order.getInitiator() != null && Objects.equals(order.getInitiator().getId(), userId);
        boolean participated = orderItemRepository.existsByGroupOrderIdAndUserId(orderId, userId);
        if (!isInitiator && !participated) {
            throw new CustomException("403", "You cannot access this order rating");
        }

        Integer myRating = orderRatingRepository.findByOrderIdAndUserId(orderId, userId)
                .map(OrderRating::getRating)
                .orElse(null);

        return buildRatingPayload(order, participated, myRating);
    }

    @Transactional
    public Map<String, Object> upsertRating(Long userId, Long orderId, Integer ratingValue) {
        if (ratingValue == null || ratingValue < 1 || ratingValue > 5) {
            throw new CustomException("400", "rating must be between 1 and 5");
        }

        GroupOrder order = groupOrderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException("404", "Order not found"));

        if (!"COMPLETED".equals(order.getStatus())) {
            throw new CustomException("409", "Only completed orders can be rated");
        }

        List<OrderItem> memberItems = orderItemRepository.findByGroupOrderIdAndUserId(orderId, userId);
        if (memberItems.isEmpty()) {
            throw new CustomException("403", "Only participating members can rate this order");
        }

        // ⚠️ 先取門市那一列的寫鎖，再寫評分。
        // 反過來的話：insert order_ratings 會因為外鍵在 stores 那列加共享鎖，
        // 隨後 refreshStoreAggregate 的 update 要升級成排他鎖，兩個併發交易互相等待
        // ——實測 12 個併發評分只有 2 筆成功，其餘 10 筆全部死鎖失敗。
        storeRepository.findByIdForUpdate(order.getStore().getId())
                .orElseThrow(() -> new CustomException("404", "Store not found"));

        OrderRating rating = orderRatingRepository.findByOrderIdAndUserId(orderId, userId)
                .orElseGet(OrderRating::new);

        rating.setOrder(order);
        rating.setUser(memberItems.get(0).getUser());
        rating.setStore(order.getStore());
        rating.setRating(ratingValue);
        orderRatingRepository.save(rating);

        refreshStoreAggregate(order.getStore().getId());
        return buildRatingPayload(order, true, ratingValue);
    }

    @Transactional(readOnly = true)
    public Map<Long, Integer> getMyRatingScores(Long userId, Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        return orderRatingRepository.findByUserIdAndOrderIdIn(userId, orderIds).stream()
                .collect(Collectors.toMap(r -> r.getOrder().getId(), OrderRating::getRating));
    }

    /**
     * 重算門市的 avg_rating / review_count。
     *
     * <p>算式交給資料庫用一句 UPDATE 完成，理由見
     * {@link com.example.demo.repository.StoreRepository#refreshRatingAggregate}：
     * 在 Java 端 COUNT 再寫回是 read-modify-write，併發時會互相覆蓋。
     */
    @Transactional
    public void refreshStoreAggregate(Long storeId) {
        if (storeRepository.refreshRatingAggregate(storeId) == 0) {
            throw new CustomException("404", "Store not found");
        }
    }

    private Map<String, Object> buildRatingPayload(GroupOrder order, boolean participated, Integer myRating) {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", order.getId());
        result.put("storeId", order.getStore() != null ? order.getStore().getId() : null);
        result.put("canRate", participated && "COMPLETED".equals(order.getStatus()) && myRating == null);
        result.put("myRating", myRating);
        result.put("ratingSubmitted", myRating != null);
        result.put("type", order.getType());
        result.put("status", order.getStatus());
        return result;
    }
}
