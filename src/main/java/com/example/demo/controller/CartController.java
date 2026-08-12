package com.example.demo.controller;

import com.example.demo.common.JwtUtils;
import com.example.demo.common.Result;
import com.example.demo.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/cart")
@Tag(name = "購物車", description = "購物車品項管理、同步與摘要")
public class CartController {

    @Autowired
    private CartService cartService;
    @Autowired
    private JwtUtils jwtUtils;

    private Long getUserId(HttpServletRequest request) {
        String token = request.getHeader("Authorization").replace("Bearer ", "");
        return jwtUtils.getUserIdFromToken(token);
    }

    @Operation(summary = "取得購物車", description = "取得目前使用者的購物車內容，含品項、金額、所屬店家。")
    @GetMapping
    public Result getCart(HttpServletRequest request) {
        return Result.success(cartService.getCart(getUserId(request)));
    }

    @Operation(summary = "新增品項至購物車", description = "Body:\n```json\n{\n  \"storeId\": 1,\n  \"productId\": 1,\n  \"sugar\": \"微糖\",\n  \"ice\": \"少冰\",\n  \"size\": \"M\",\n  \"toppingNames\": [\"珍珠\", \"椰果\"]\n}\n```\n\n⚠️ 若購物車內已有其他店家商品，回傳 409。")
    @PostMapping("/items")
    public Result addItem(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        return Result.success(cartService.addItem(getUserId(request), req));
    }

    @Operation(summary = "修改購物車品項", description = "Body 同新增品項，欄位皆為選填。")
    @PutMapping("/items/{itemId}")
    public Result updateItem(HttpServletRequest request,
            @PathVariable Long itemId,
            @RequestBody Map<String, Object> req) {
        return Result.success(cartService.updateItem(getUserId(request), itemId, req));
    }

    @Operation(summary = "刪除單一購物車品項")
    @DeleteMapping("/items/{itemId}")
    public Result deleteItem(HttpServletRequest request, @PathVariable Long itemId) {
        cartService.deleteItem(getUserId(request), itemId);
        return Result.success("已刪除");
    }

    @Operation(summary = "清空購物車", description = "刪除目前使用者購物車內所有品項，可透過 storeId 指定特定店家。")
    @DeleteMapping
    public Result clearCart(HttpServletRequest request, @RequestParam(required = false) Long storeId) {
        cartService.clearCart(getUserId(request), storeId);
        return Result.success("購物車已清空");
    }

    @Operation(summary = "購物車摘要", description = "回傳品項數量與總金額，用於頂部購物車 badge 顯示。")
    @GetMapping("/summary")
    public Result getCartSummary(HttpServletRequest request) {
        return Result.success(cartService.getCartSummary(getUserId(request)));
    }
}
