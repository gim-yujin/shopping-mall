package com.shop.domain.product.controller;

import com.shop.domain.order.service.OrderService;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.user.service.UserService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final ProductService productService;
    private final OrderService orderService;

    public AdminController(ProductService productService, OrderService orderService) {
        this.productService = productService;
        this.orderService = orderService;
    }

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("products", productService.findAll(PageRequest.of(0, 10)));
        model.addAttribute("recentOrders", orderService.getAllOrders(PageRequest.of(0, 10)));
        return "admin/dashboard";
    }

    @GetMapping("/orders")
    public String adminOrders(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(required = false) String status,
                              Model model) {
        if (status != null && !status.isBlank()) {
            model.addAttribute("orders", orderService.getOrdersByStatus(status, PageRequest.of(page, 20)));
        } else {
            model.addAttribute("orders", orderService.getAllOrders(PageRequest.of(page, 20)));
        }
        model.addAttribute("currentStatus", status);
        return "admin/orders";
    }

    @PostMapping("/orders/{orderId}/status")
    public String updateOrderStatus(@PathVariable Long orderId, @RequestParam String status,
                                    RedirectAttributes redirectAttributes) {
        orderService.updateOrderStatus(orderId, status);
        redirectAttributes.addFlashAttribute("successMessage", "주문 상태가 변경되었습니다.");
        return "redirect:/admin/orders";
    }

    @GetMapping("/products")
    public String adminProducts(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("products", productService.findAll(PageRequest.of(page, 20)));
        return "admin/products";
    }
}
