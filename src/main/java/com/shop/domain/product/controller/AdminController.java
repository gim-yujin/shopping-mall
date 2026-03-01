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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

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
     *
     * <p>[Step 5] 반품 대기 건수(pendingReturnCount)를 추가하여
     * 관리자가 대시보드에서 즉시 미처리 반품 건수를 확인할 수 있도록 한다.
     * 반품 건수 카드에서 반품 관리 페이지(/admin/returns)로 바로 이동 가능하다.</p>
     */
    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("products", productService.findAll(PageRequest.of(0, PageDefaults.ADMIN_DASHBOARD_SIZE)));
        model.addAttribute("recentOrders", orderService.getAllOrders(PageRequest.of(0, PageDefaults.ADMIN_DASHBOARD_SIZE)));
        model.addAttribute("couponStats", couponService.getCouponStats());
        // [Step 5] 반품 대기 건수 — 대시보드 카드에 표시
        model.addAttribute("pendingReturnCount", orderService.getPendingReturnCount());
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

    // ──────────── [Step 5] 반품 관리 ────────────
    //
    // 설계 문서 §8.2, §9.2에 따라 관리자 반품 관리 엔드포인트를 구현한다.
    //
    // 문제: Step 1~4에서 반품 신청 → 승인/거절 서비스 로직과 사용자 UI를 완성했지만,
    //       관리자가 반품 대기 건을 조회하고 처리할 수 있는 페이지가 없었다.
    //       관리자는 DB를 직접 확인하거나 주문 상세를 하나씩 열어 반품 상태를 확인해야 했다.
    //
    // 해결: 세 가지 엔드포인트를 추가한다.
    //   1. GET /admin/returns — 반품 대기(RETURN_REQUESTED) 목록을 페이징 조회
    //   2. POST /admin/returns/{orderItemId}/approve — 관리자 반품 승인
    //   3. POST /admin/returns/{orderItemId}/reject — 관리자 반품 거절 (거절 사유 필수)
    //
    // 대시보드에는 pendingReturnCount를 표시하여 관리자가 즉시 미처리 건수를 파악하고
    // 반품 관리 페이지로 이동할 수 있도록 한다.
    //
    // 보안: /admin/** 경로는 SecurityConfig에서 ROLE_ADMIN만 접근 가능하도록 이미 설정되어 있다.

    /**
     * 반품 대기 목록 페이지.
     *
     * <p>RETURN_REQUESTED 상태의 OrderItem을 신청일 순으로 페이징 조회한다.
     * OrderQueryService.getReturnRequests()가 AdminReturnResponse DTO로 변환하여
     * 주문번호, 상품명, 반품 수량, 사유, 신청일, 사용자 정보를 포함한다.</p>
     *
     * <p>partial index {@code idx_order_items_status_return_requested}를 활용하여
     * 전체 order_items 스캔 없이 반품 대기 건만 빠르게 조회한다.</p>
     */
    @GetMapping("/returns")
    public String returnList(@RequestParam(defaultValue = "0") int page, Model model) {
        int normalizedPage = PagingParams.normalizePage(page);
        model.addAttribute("returns", orderService.getReturnRequests(normalizedPage));
        return "admin/returns";
    }

    /**
     * 반품 승인.
     *
     * <p>RETURN_REQUESTED → RETURNED 전이를 수행한다.
     * 승인 시 재고 복구, 환불 금액 계산, 포인트 비례 환불, 등급 재계산,
     * 캐시 무효화가 모두 실행된다 (PartialCancellationService.approveReturn 위임).</p>
     *
     * <p>orderId는 Order 비관적 잠금 획득에 필요하며, orderItemId는 대상 아이템을 식별한다.
     * 동시에 같은 아이템에 대해 승인/거절이 요청되면 비관적 잠금이 직렬화한다.</p>
     */
    @PostMapping("/returns/{orderItemId}/approve")
    public String approveReturn(@PathVariable Long orderItemId,
                                 @RequestParam Long orderId,
                                 RedirectAttributes redirectAttributes) {
        try {
            orderService.approveReturn(orderId, orderItemId);
            redirectAttributes.addFlashAttribute("successMessage", "반품이 승인되었습니다.");
        } catch (BusinessException e) {
            log.warn("반품 승인 실패: orderId={}, orderItemId={}, reason={}",
                    orderId, orderItemId, e.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/returns";
    }

    /**
     * 반품 거절.
     *
     * <p>RETURN_REQUESTED → RETURN_REJECTED 전이를 수행한다.
     * 거절 시 재고/환불 변경 없이 상태만 전이하고 거절 사유를 기록한다.
     * 사용자 주문 상세 페이지에서 거절 사유가 표시되며, 재신청 폼이 노출된다.</p>
     *
     * <p>거절 사유(rejectReason)는 필수 파라미터이다.
     * 빈 값이 전달되면 기본 메시지로 대체한다.</p>
     */
    @PostMapping("/returns/{orderItemId}/reject")
    public String rejectReturn(@PathVariable Long orderItemId,
                                @RequestParam Long orderId,
                                @RequestParam(defaultValue = "") String rejectReason,
                                RedirectAttributes redirectAttributes) {
        try {
            // 거절 사유가 비어있으면 기본 메시지 설정
            String reason = (rejectReason == null || rejectReason.isBlank())
                    ? "반품 요건을 충족하지 않습니다."
                    : rejectReason.trim();
            orderService.rejectReturn(orderId, orderItemId, reason);
            redirectAttributes.addFlashAttribute("successMessage", "반품이 거절되었습니다.");
        } catch (BusinessException e) {
            log.warn("반품 거절 실패: orderId={}, orderItemId={}, reason={}",
                    orderId, orderItemId, e.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/returns";
    }
}
