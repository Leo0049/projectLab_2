package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.*;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class CartService {

    @Autowired
    private CartItemRepository cartItemRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private ProductTemplateRepository productTemplateRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private PricingService pricingService;

    /**
     * 產生品項雜湊值 (MD5)
     * 組成維度：user_id, product_id, size, sugar, ice, 排序後的配料名稱, coupon_id
     */
    public String generateItemHash(Long userId, Long productId, String size, String sugar, String ice, List<String> toppings, Long couponId) {
        List<String> sortedToppings = (toppings == null) ? new ArrayList<>() : new ArrayList<>(toppings);
        Collections.sort(sortedToppings);
        String toppingKey = String.join(",", sortedToppings);
        
        // 處理 couponId，若為 null 則帶入空或 0
        String cpId = (couponId == null) ? "0" : couponId.toString();

        // 格式化 Key 維度
        String rawKey = String.format("u:%d|p:%d|sz:%s|su:%s|ic:%s|tp:%s|cp:%s",
                userId, productId, 
                (size == null ? "" : size), 
                (sugar == null ? "" : sugar), 
                (ice == null ? "" : ice), 
                toppingKey,
                cpId);

        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not supported", e);
        }
    }

    @Autowired
    private OrderItemToppingRepository orderItemToppingRepository;

    /**
     * 將品項加入揪團訂單（支援雜湊比對與數量合併）
     */
    @Transactional
    public OrderItem addOrUpdateOrderItem(GroupOrder order, OrderItem newItem, List<String> toppingNames) {
        // 1. 產生雜湊值
        String hash = generateItemHash(
                newItem.getUser().getId(),
                newItem.getProduct().getId(),
                newItem.getSizeSnapshot(),
                newItem.getSugarSnapshot(),
                newItem.getIceSnapshot(),
                toppingNames,
                newItem.getCouponId()
        );
        newItem.setItemHash(hash);

        // 2. 檢查是否存在相同雜湊的品項
        Optional<OrderItem> existingOpt = orderItemRepository.findByGroupOrderIdAndUserIdAndItemHash(
                order.getId(), newItem.getUser().getId(), hash);

        if (existingOpt.isPresent()) {
            OrderItem existing = existingOpt.get();
            // 合併數量
            existing.setQty(existing.getQty() + newItem.getQty());
            return orderItemRepository.save(existing);
        } else {
            // 新增品項
            newItem.setGroupOrder(order);
            OrderItem saved = orderItemRepository.save(newItem);

            // 處理配料快照
            if (toppingNames != null && !toppingNames.isEmpty()) {
                for (String tName : toppingNames) {
                    OrderItemTopping oit = new OrderItemTopping();
                    OrderItemToppingId oitId = new OrderItemToppingId();
                    oitId.setOrderItemId(saved.getId()); // Explicitly set it too
                    oitId.setToppingNameSnapshot(tName);
                    oit.setId(oitId);
                    oit.setOrderItem(saved);
                    oit.setToppingPriceSnapshot(BigDecimal.ZERO); // 簡化處理
                    orderItemToppingRepository.save(oit);
                }
            }
            return saved;
        }
    }

    @Transactional
    public Map<String, Object> getCart(Long userId) {
        List<CartItem> items = cartItemRepository.findByUserId(userId);
        List<Map<String, Object>> itemList = items.stream().map(this::toMap).toList();
        BigDecimal total = items.stream()
                .map(CartItem::getFinalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        Map<String, Object> result = new HashMap<>();
        result.put("items", itemList);
        result.put("itemCount", items.size());
        result.put("totalAmount", total);
        
        // 保留頂層 storeId 供舊版前端相容（取第一個品項的店家）
        if (!items.isEmpty()) {
            Store s = items.get(0).getStore();
            result.put("storeId", s.getId());
            result.put("storeName", s.getStoreName());
            if (s.getBrand() != null) {
                result.put("storeLogoUrl", s.getBrand().getLogoUrl());
            }
        }
        return result;
    }

    @Transactional
    public Map<String, Object> addItem(Long userId, Map<String, Object> req) {
        Long storeId = Long.parseLong(req.get("storeId").toString());
        Long productId = Long.parseLong(req.get("productId").toString());

        // 移除「不同店家不可混合」限制，改為支援多購物車
        // List<CartItem> existing = cartItemRepository.findByUserId(userId);
        // if (!existing.isEmpty() && !existing.get(0).getStore().getId().equals(storeId)) {
        //     throw new CustomException("409", "購物車內已有其他店家的商品，請先清空購物車");
        // }

        User user = userRepository.findById(userId).orElseThrow(() -> new CustomException("404", "找不到用戶"));
        Store store = storeRepository.findById(storeId).orElseThrow(() -> new CustomException("404", "找不到店家"));
        ProductTemplate product = productTemplateRepository.findById(productId)
                .orElseThrow(() -> new CustomException("404", "找不到商品"));

        CartItem item = new CartItem();
        item.setUser(user);
        item.setStore(store);
        item.setProduct(product);
        item.setSugarSnapshot((String) req.getOrDefault("sugar", ""));
        item.setIceSnapshot((String) req.getOrDefault("ice", ""));
        item.setSizeSnapshot((String) req.getOrDefault("size", "M"));
        item.setQuantity(Integer.parseInt(req.getOrDefault("qty", req.getOrDefault("quantity", "1")).toString()));

        // 計算配料加價
        @SuppressWarnings("unchecked")
        List<String> toppingNames = (List<String>) req.getOrDefault("toppingNames", new ArrayList<>());
        item.setToppingNames(String.join(",", toppingNames));

        // 售價一律走 PricingService，購物車與結帳共用同一條公式
        BigDecimal toppingExtra = pricingService.toppingExtra(product.getBrand().getId(), toppingNames);
        item.setToppingExtra(toppingExtra);
        BigDecimal unitPrice = pricingService.unitPrice(store, product);
        item.setUnitPrice(unitPrice);
        item.setFinalPrice(unitPrice.add(toppingExtra));

        cartItemRepository.save(item);

        Map<String, Object> result = new HashMap<>();
        result.put("cartItemId", item.getId());
        result.put("itemCount", cartItemRepository.countByUserId(userId));
        return result;
    }

    // ─── 修改購物車品項 ───────────────────────────────────────
    @Transactional
    public Map<String, Object> updateItem(Long userId, Long itemId, Map<String, Object> req) {
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new CustomException("404", "找不到此購物車品項"));
        if (!item.getUser().getId().equals(userId))
            throw new CustomException("403", "無權限修改此品項");

        if (req.containsKey("sugar"))
            item.setSugarSnapshot((String) req.get("sugar"));
        if (req.containsKey("ice"))
            item.setIceSnapshot((String) req.get("ice"));
        if (req.containsKey("size"))
            item.setSizeSnapshot((String) req.get("size"));
        if (req.containsKey("qty"))
            item.setQuantity(Integer.parseInt(req.get("qty").toString()));
        if (req.containsKey("quantity"))
            item.setQuantity(Integer.parseInt(req.get("quantity").toString()));

        if (req.containsKey("toppingNames")) {
            @SuppressWarnings("unchecked")
            List<String> toppingNames = (List<String>) req.get("toppingNames");
            item.setToppingNames(String.join(",", toppingNames));
            BigDecimal extra = pricingService.toppingExtra(item.getProduct().getBrand().getId(), toppingNames);
            item.setToppingExtra(extra);
            item.setFinalPrice(item.getUnitPrice().add(extra));
        }

        cartItemRepository.save(item);
        return toMap(item);
    }

    // ─── 刪除單一品項 ─────────────────────────────────────────
    @Transactional
    public void deleteItem(Long userId, Long itemId) {
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new CustomException("404", "找不到此購物車品項"));
        if (!item.getUser().getId().equals(userId))
            throw new CustomException("403", "無權限刪除此品項");
        cartItemRepository.delete(item);
    }

    // ─── 清空購物車 ───────────────────────────────────────────
    @Transactional
    public void clearCart(Long userId) {
        cartItemRepository.deleteByUserId(userId);
    }

    @Transactional
    public void clearCart(Long userId, Long storeId) {
        if (storeId == null) {
            cartItemRepository.deleteByUserId(userId);
        } else {
            // 需要在 Repository 增加對應方法，或先查詢再刪除
            List<CartItem> items = cartItemRepository.findByUserId(userId);
            List<CartItem> toDelete = items.stream()
                    .filter(it -> it.getStore().getId().equals(storeId))
                    .toList();
            cartItemRepository.deleteAll(toDelete);
        }
    }

    // ─── 購物車摘要 ───────────────────────────────────────────
    @Transactional
    public Map<String, Object> getCartSummary(Long userId) {
        List<CartItem> items = cartItemRepository.findByUserId(userId);
        BigDecimal total = items.stream().map(CartItem::getFinalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> result = new HashMap<>();
        result.put("itemCount", items.size());
        result.put("totalAmount", total);
        return result;
    }

    // ─── private helpers ──────────────────────────────────────
    private Map<String, Object> toMap(CartItem item) {
        Map<String, Object> m = new HashMap<>();
        m.put("cartItemId", item.getId());
        m.put("productId", item.getProduct().getId());
        m.put("productName", item.getProduct().getName());
        m.put("imageUrl", item.getProduct().getLogoUrl());
        m.put("sugar", item.getSugarSnapshot());
        m.put("ice", item.getIceSnapshot());
        m.put("size", item.getSizeSnapshot());
        m.put("quantity", item.getQuantity() != null ? item.getQuantity() : 1);
        m.put("toppingNames", item.getToppingNames() != null
                ? Arrays.asList(item.getToppingNames().split(","))
                : new ArrayList<>());
        m.put("unitPrice", item.getUnitPrice());
        m.put("toppingExtra", item.getToppingExtra());
        m.put("finalPrice", item.getFinalPrice());
        
        // 增加店家資訊，方便前端進行 store-level 分組
        if (item.getStore() != null) {
            m.put("storeId", item.getStore().getId());
            m.put("storeName", item.getStore().getStoreName());
            if (item.getStore().getBrand() != null) {
                m.put("storeLogoUrl", item.getStore().getBrand().getLogoUrl());
            }
        }
        return m;
    }
}
