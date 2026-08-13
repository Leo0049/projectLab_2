package com.example.demo.controller;

import com.example.demo.entity.Store;
import com.example.demo.exception.CustomException;
import com.example.demo.entity.User;
import com.example.demo.entity.UserFavorite;
import com.example.demo.repository.StoreRepository;
import com.example.demo.repository.UserFavoriteRepository;
import com.example.demo.repository.UserRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user-favorites")
@RequiredArgsConstructor
public class UserFavoriteController {

    private final UserFavoriteRepository userFavoriteRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;

    /**
     * 身分一律取自 JwtAuthenticationFilter 寫入的 currentUserId。
     *
     * ⚠️ 這三支端點原本完全沒有擁有權檢查，userId 直接吃路徑／查詢參數／request body。
     * 實測任一登入顧客可以讀取他人的收藏清單、查詢他人是否收藏某店，
     * 而且能用他人的 userId 呼叫 toggle——把別人收藏的店家直接取消掉。
     * 與 UserController.requireSelf / OrderController.requireSelf 同一套規則。
     */
    private void requireSelf(Long targetUserId, Long currentUserId) {
        if (currentUserId == null || !currentUserId.equals(targetUserId))
            throw new CustomException("403", "無權存取其他使用者的收藏");
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<UserFavoriteDTO>> getUserFavorites(@PathVariable Long userId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        requireSelf(userId, currentUserId);
        List<UserFavorite> favorites = userFavoriteRepository.findByUserId(userId);

        List<UserFavoriteDTO> dtos = favorites.stream()
                .map(f -> {
                    Store store = f.getStore();
                    if (store == null)
                        return null;
                    return new UserFavoriteDTO(store, f.getCreatedAt());
                })
                .filter(d -> d != null)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    @Data
    public static class SimpleStoreDTO {
        private Long id;
        private String storeName;
        private String coverUrl;
        private String brandLogoUrl;
        private String brandName;
        private String address;
        private Double avgRating;

        public SimpleStoreDTO(Store s) {
            this.id = s.getId();
            this.storeName = s.getStoreName();
            this.coverUrl = s.getCoverUrl();
            this.address = s.getAddress();
            this.avgRating = s.getAvgRating() != null ? s.getAvgRating().doubleValue() : 4.8;
            if (s.getBrand() != null) {
                try {
                    this.brandName = s.getBrand().getName();
                    this.brandLogoUrl = s.getBrand().getLogoUrl();
                } catch (Exception e) {
                    this.brandName = null;
                    this.brandLogoUrl = null;
                }
            }
        }
    }

    @Data
    public static class UserFavoriteDTO {
        private SimpleStoreDTO store;
        private LocalDateTime createdAt;

        public UserFavoriteDTO(Store store, LocalDateTime createdAt) {
            this.store = new SimpleStoreDTO(store);
            this.createdAt = createdAt;
        }
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Boolean>> checkFavorite(
            @RequestParam(required = false) Long userId,
            @RequestParam Long storeId,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        // 查詢參數的 userId 只為相容舊前端而保留，一律不採信
        if (currentUserId == null)
            throw new CustomException("403", "請先登入");
        boolean isFavorited = userFavoriteRepository.existsByUserIdAndStoreId(currentUserId, storeId);
        Map<String, Boolean> response = new HashMap<>();
        response.put("isFavorited", isFavorited);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggleFavorite(@RequestBody FavoriteToggleRequest request,
            @RequestAttribute(value = "currentUserId", required = false) Long currentUserId) {
        // body 的 userId 同樣不採信
        if (currentUserId == null)
            throw new CustomException("403", "請先登入");
        Optional<UserFavorite> existing = userFavoriteRepository
                .findByUserIdAndStoreId(currentUserId, request.getStoreId());
        Map<String, Object> response = new HashMap<>();

        if (existing.isPresent()) {
            userFavoriteRepository.delete(existing.get());
            response.put("status", "removed");
        } else {
            User user = userRepository.findById(currentUserId).orElse(null);
            Store store = storeRepository.findById(request.getStoreId()).orElse(null);
            if (user == null || store == null) {
                response.put("status", "error");
                response.put("message", "User or Store not found");
                return ResponseEntity.badRequest().body(response);
            }
            UserFavorite uf = new UserFavorite();
            uf.setUser(user);
            uf.setStore(store);
            uf.setCreatedAt(LocalDateTime.now());
            userFavoriteRepository.save(uf);
            response.put("status", "added");
        }

        return ResponseEntity.ok(response);
    }

    @Data
    public static class FavoriteToggleRequest {
        private Long userId;
        private Long storeId;
    }
}



    
    
