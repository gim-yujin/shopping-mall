package com.shop.domain.order.controller;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.service.CartService;
import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.UserCoupon;
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
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        List<UserCoupon> availableCoupons = couponService.getAvailableCoupons(userId);
        model.addAttribute("availableCoupons", availableCoupons);
        model.addAttribute("couponDisplayNames", buildCouponDisplayNames(availableCoupons));
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
        model.addAttribute("orderStatusLabels", orderStatusLabels());
        model.addAttribute("orderStatusBadgeClasses", orderStatusBadgeClasses());
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
    private Map<String, String> orderStatusLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("PENDING", "결제대기");
        labels.put("PAID", "결제완료");
        labels.put("SHIPPED", "배송중");
        labels.put("DELIVERED", "배송완료");
        labels.put("CANCELLED", "취소");
        return labels;
    }

    private Map<String, String> orderStatusBadgeClasses() {
        Map<String, String> classes = new LinkedHashMap<>();
        classes.put("PENDING", "bg-yellow-100 text-yellow-700");
        classes.put("PAID", "bg-yellow-100 text-yellow-700");
        classes.put("SHIPPED", "bg-blue-100 text-blue-700");
        classes.put("DELIVERED", "bg-green-100 text-green-700");
        classes.put("CANCELLED", "bg-red-100 text-red-700");
        return classes;
    }

    private Map<Long, String> buildCouponDisplayNames(List<UserCoupon> availableCoupons) {
        Map<Long, String> displayNames = new LinkedHashMap<>();
        NumberFormat numberFormat = NumberFormat.getInstance(Locale.KOREA);

        for (UserCoupon userCoupon : availableCoupons) {
            Coupon coupon = userCoupon.getCoupon();
            String discountText = "PERCENT".equals(coupon.getDiscountType())
                    ? coupon.getDiscountValue().stripTrailingZeros().toPlainString() + "%"
                    : numberFormat.format(coupon.getDiscountValue()) + "원";

            String displayName = coupon.getCouponName()
                    + " (" + discountText
                    + " 할인, 최소주문 " + numberFormat.format(coupon.getMinOrderAmount()) + "원)";

            displayNames.put(userCoupon.getUserCouponId(), displayName);
        }

        return displayNames;
    }

}
