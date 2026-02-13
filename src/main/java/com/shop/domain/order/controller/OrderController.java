package com.shop.domain.order.controller;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.service.CartService;
import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.service.UserService;
import com.shop.global.exception.BusinessException;
import com.shop.global.security.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.List;

@Controller
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final CartService cartService;
    private final UserService userService;
    private final CouponService couponService;

    public OrderController(OrderService orderService, CartService cartService,
                           UserService userService, CouponService couponService) {
        this.orderService = orderService;
        this.cartService = cartService;
        this.userService = userService;
        this.couponService = couponService;
    }

    @GetMapping("/checkout")
    public String checkoutPage(Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        List<Cart> items = cartService.getCartItems(userId);
        if (items.isEmpty()) {
            return "redirect:/cart";
        }
        User user = userService.findById(userId);
        model.addAttribute("cartItems", items);
        model.addAttribute("totalPrice", cartService.calculateTotal(items));
        model.addAttribute("user", user);
        model.addAttribute("availableCoupons", couponService.getAvailableCoupons(userId));
        return "order/checkout";
    }

    @PostMapping
    public String createOrder(@Valid OrderCreateRequest request, RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        try {
            Order order = orderService.createOrder(userId, request);
            redirectAttributes.addFlashAttribute("successMessage", "주문이 완료되었습니다.");
            return "redirect:/orders/" + order.getOrderId();
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/orders/checkout";
        }
    }

    @GetMapping
    public String orderList(@RequestParam(defaultValue = "0") int page, Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        model.addAttribute("orders", orderService.getOrdersByUser(userId, PageRequest.of(page, 10)));
        return "order/list";
    }

    @GetMapping("/{orderId}")
    public String orderDetail(@PathVariable Long orderId, Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        model.addAttribute("order", orderService.getOrderDetail(orderId, userId));
        return "order/detail";
    }

    @PostMapping("/{orderId}/cancel")
    public String cancelOrder(@PathVariable Long orderId, RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        try {
            orderService.cancelOrder(orderId, userId);
            redirectAttributes.addFlashAttribute("successMessage", "주문이 취소되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/orders/" + orderId;
    }
}
