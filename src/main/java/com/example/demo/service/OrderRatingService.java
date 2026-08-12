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

    @Transactional
    public void refreshStoreAggregate(Long storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException("404", "Store not found"));

        long reviewCount = orderRatingRepository.countByStoreId(storeId);
        Double avg = orderRatingRepository.findAverageRatingByStoreId(storeId);

        store.setReviewCount(Math.toIntExact(reviewCount));
        store.setAvgRating(reviewCount == 0 || avg == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP));
        storeRepository.save(store);
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
