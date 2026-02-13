package com.shop.domain.wishlist.controller;

import com.shop.domain.wishlist.service.WishlistService;
import com.shop.global.security.SecurityUtil;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Controller
@RequestMapping("/wishlist")
public class WishlistController {

    private final WishlistService wishlistService;

    public WishlistController(WishlistService wishlistService) {
        this.wishlistService = wishlistService;
    }

    @GetMapping
    public String wishlistPage(@RequestParam(defaultValue = "0") int page, Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        model.addAttribute("wishlists", wishlistService.getWishlist(userId, PageRequest.of(page, 20)));
        return "wishlist/index";
    }

    @PostMapping("/toggle")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleWishlist(@RequestParam Long productId) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        boolean wishlisted = wishlistService.toggleWishlist(userId, productId);
        return ResponseEntity.ok(Map.of("wishlisted", wishlisted));
    }
}
