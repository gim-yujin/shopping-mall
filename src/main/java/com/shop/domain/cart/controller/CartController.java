package com.shop.domain.cart.controller;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.service.CartService;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.service.UserService;
import com.shop.global.security.SecurityUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/cart")
public class CartController {

    private final CartService cartService;
    private final UserService userService;
    private final OrderService orderService;

    public CartController(CartService cartService, UserService userService, OrderService orderService) {
        this.cartService = cartService;
        this.userService = userService;
        this.orderService = orderService;
    }

    @GetMapping
    public String cartPage(Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        List<Cart> items = cartService.getCartItems(userId);
        BigDecimal totalPrice = cartService.calculateTotal(items);

        User user = userService.findById(userId);
        BigDecimal shippingFee = orderService.calculateShippingFee(user.getTier(), totalPrice);

        model.addAttribute("cartItems", items);
        model.addAttribute("totalPrice", totalPrice);
        model.addAttribute("shippingFee", shippingFee);
        model.addAttribute("freeShippingThreshold", user.getTier().getFreeShippingThreshold());
        return "cart/index";
    }

    @PostMapping("/add")
    public String addToCart(@RequestParam Long productId,
                            @RequestParam(defaultValue = "1") int quantity,
                            RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        cartService.addToCart(userId, productId, quantity);
        redirectAttributes.addFlashAttribute("successMessage", "장바구니에 추가되었습니다.");
        return "redirect:/cart";
    }

    @PostMapping("/update")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateQuantity(@RequestParam Long productId,
                                                               @RequestParam int quantity) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        cartService.updateQuantity(userId, productId, quantity);
        List<Cart> items = cartService.getCartItems(userId);
        return ResponseEntity.ok(Map.of("success", true,
                "totalPrice", cartService.calculateTotal(items),
                "cartCount", items.size()));
    }

    @PostMapping("/remove")
    public String removeFromCart(@RequestParam Long productId, RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        cartService.removeFromCart(userId, productId);
        redirectAttributes.addFlashAttribute("successMessage", "상품이 삭제되었습니다.");
        return "redirect:/cart";
    }
}
