package com.shop.domain.product.controller;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.product.dto.AdminProductRequest;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.service.ProductService;
import com.shop.global.common.PageDefaults;
import com.shop.global.common.PagingParams;
import com.shop.global.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final ProductService productService;
    private final OrderService orderService;
    private final CategoryService categoryService;
    private final CouponService couponService;

    public AdminController(ProductService productService, OrderService orderService,
                           CategoryService categoryService, CouponService couponService) {
        this.productService = productService;
        this.orderService = orderService;
        this.categoryService = categoryService;
        this.couponService = couponService;
    }

    // ──────────── 대시보드 ────────────

    /**
     * [3.11] 관리자 대시보드에 쿠폰 통계를 포함.
     * 전체 쿠폰 수, 활성 쿠폰 수, 발급/사용률 등을 표시한다.
     */
    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("products", productService.findAll(PageRequest.of(0, PageDefaults.ADMIN_DASHBOARD_SIZE)));
        model.addAttribute("recentOrders", orderService.getAllOrders(PageRequest.of(0, PageDefaults.ADMIN_DASHBOARD_SIZE)));
        model.addAttribute("couponStats", couponService.getCouponStats());
        return "admin/dashboard";
    }

    // ──────────── 주문 관리 ────────────

    @GetMapping("/orders")
    public String adminOrders(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(required = false) String status,
                              Model model) {
        int normalizedPage = PagingParams.normalizePage(page);
        if (status != null && !status.isBlank()) {
            model.addAttribute("orders", orderService.getOrdersByStatus(status, PageRequest.of(normalizedPage, PageDefaults.ADMIN_LIST_SIZE)));
        } else {
            model.addAttribute("orders", orderService.getAllOrders(PageRequest.of(normalizedPage, PageDefaults.ADMIN_LIST_SIZE)));
        }
        model.addAttribute("currentStatus", status);
        model.addAttribute("orderStatuses", OrderStatus.codes());
        model.addAttribute("orderStatusLabels", OrderStatus.labelsByCode());
        model.addAttribute("orderStatusBadgeClasses", OrderStatus.badgeClassesByCode());
        return "admin/orders";
    }

    /**
     * [3.6] 관리자 주문 상태 변경.
     * SHIPPED 전환 시 택배사(carrier)와 송장번호(trackingNumber)를 함께 전달한다.
     */
    @PostMapping("/orders/{orderId}/status")
    public String updateOrderStatus(@PathVariable Long orderId,
                                    @RequestParam String status,
                                    @RequestParam(required = false) String carrier,
                                    @RequestParam(required = false) String trackingNumber,
                                    RedirectAttributes redirectAttributes) {
        try {
            orderService.updateOrderStatus(orderId, status, carrier, trackingNumber);
            redirectAttributes.addFlashAttribute("successMessage", "주문 상태가 변경되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/orders";
    }

    // ──────────── 상품 관리 — 목록 ────────────

    @GetMapping("/products")
    public String adminProducts(@RequestParam(defaultValue = "0") int page, Model model) {
        int normalizedPage = PagingParams.normalizePage(page);
        model.addAttribute("products",
                productService.findAllForAdmin(PageRequest.of(normalizedPage, PageDefaults.ADMIN_LIST_SIZE, Sort.by(Sort.Direction.DESC, "productId"))));
        return "admin/products";
    }

    // ──────────── 상품 관리 — 등록 ────────────

    @GetMapping("/products/new")
    public String newProductForm(Model model) {
        model.addAttribute("request", new AdminProductRequest());
        model.addAttribute("categories", categoryService.getAllActiveCategories());
        model.addAttribute("editMode", false);
        return "admin/product-form";
    }

    @PostMapping("/products")
    public String createProduct(@Valid @ModelAttribute("request") AdminProductRequest request,
                                BindingResult bindingResult,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("categories", categoryService.getAllActiveCategories());
            model.addAttribute("editMode", false);
            return "admin/product-form";
        }
        Product product = productService.createProduct(request);
        redirectAttributes.addFlashAttribute("successMessage",
                "상품 '" + product.getProductName() + "'이(가) 등록되었습니다.");
        return "redirect:/admin/products";
    }

    // ──────────── 상품 관리 — 수정 ────────────

    @GetMapping("/products/{id}/edit")
    public String editProductForm(@PathVariable Long id, Model model) {
        Product product = productService.findByIdForAdmin(id);
        AdminProductRequest request = new AdminProductRequest();
        request.setProductName(product.getProductName());
        request.setCategoryId(product.getCategory().getCategoryId());
        request.setDescription(product.getDescription());
        request.setPrice(product.getPrice());
        request.setOriginalPrice(product.getOriginalPrice());
        request.setStockQuantity(product.getStockQuantity());

        model.addAttribute("request", request);
        model.addAttribute("productId", id);
        model.addAttribute("product", product);
        model.addAttribute("categories", categoryService.getAllActiveCategories());
        model.addAttribute("editMode", true);
        return "admin/product-form";
    }

    @PostMapping("/products/{id}")
    public String updateProduct(@PathVariable Long id,
                                @Valid @ModelAttribute("request") AdminProductRequest request,
                                BindingResult bindingResult,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("productId", id);
            model.addAttribute("product", productService.findByIdForAdmin(id));
            model.addAttribute("categories", categoryService.getAllActiveCategories());
            model.addAttribute("editMode", true);
            return "admin/product-form";
        }
        Product product = productService.updateProduct(id, request);
        redirectAttributes.addFlashAttribute("successMessage",
                "상품 '" + product.getProductName() + "'이(가) 수정되었습니다.");
        return "redirect:/admin/products";
    }

    // ──────────── 상품 관리 — 활성/비활성 토글 ────────────

    @PostMapping("/products/{id}/toggle-active")
    public String toggleProductActive(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        productService.toggleProductActive(id);
        redirectAttributes.addFlashAttribute("successMessage", "상품 상태가 변경되었습니다.");
        return "redirect:/admin/products";
    }
}
