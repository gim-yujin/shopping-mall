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

@Controller
@RequestMapping("/cart")
public class CartController {

    private final CartService cartService;
    private final UserService userService;
    private final OrderService orderService;

    public record CartUpdateResponse(boolean success, BigDecimal totalPrice, int cartCount) { }

    public CartController(final CartService cartService, final UserService userService, final OrderService orderService) {
        this.cartService = cartService;
        this.userService = userService;
        this.orderService = orderService;
    }

    @GetMapping
    public String cartPage(final Model model) {
        final Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        final List<Cart> items = cartService.getCartItems(userId);
        final BigDecimal totalPrice = cartService.calculateTotal(items);

        final User user = userService.findById(userId);
        final BigDecimal shippingFee = orderService.calculateShippingFee(user.getTier(), totalPrice);

        model.addAttribute("cartItems", items);
        model.addAttribute("totalPrice", totalPrice);
        model.addAttribute("shippingFee", shippingFee);
        model.addAttribute("freeShippingThreshold", user.getTier().getFreeShippingThreshold());
        return "cart/index";
    }

    @PostMapping("/add")
    public String addToCart(@RequestParam final Long productId,
                            @RequestParam(defaultValue = "1") final int quantity,
                            final RedirectAttributes redirectAttrs) {
        final Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        cartService.addToCart(userId, productId, quantity);
        redirectAttrs.addFlashAttribute("successMessage", "장바구니에 추가되었습니다.");
        return "redirect:/cart";
    }

    @PostMapping("/update")
    @ResponseBody
    public ResponseEntity<CartUpdateResponse> updateQuantity(@RequestParam final Long productId,
                                                               @RequestParam final int quantity) {
        final Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        cartService.updateQuantity(userId, productId, quantity);
        final List<Cart> items = cartService.getCartItems(userId);
        return ResponseEntity.ok(new CartUpdateResponse(true, cartService.calculateTotal(items), items.size()));
    }

    @PostMapping("/remove")
    public String removeFromCart(@RequestParam final Long productId, final RedirectAttributes redirectAttrs) {
        final Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        cartService.removeFromCart(userId, productId);
        redirectAttrs.addFlashAttribute("successMessage", "상품이 삭제되었습니다.");
        return "redirect:/cart";
    }
}
