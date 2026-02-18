package com.shop.domain.product.controller;

import com.shop.domain.order.service.OrderService;
import com.shop.domain.product.service.ProductService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        model.addAttribute("orderStatuses", List.of("PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"));
        model.addAttribute("orderStatusLabels", orderStatusLabels());
        model.addAttribute("orderStatusBadgeClasses", orderStatusBadgeClasses());
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

}
