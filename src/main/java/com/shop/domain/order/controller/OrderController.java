package com.shop.domain.order.controller;

import com.shop.domain.order.dto.CheckoutPreview;
import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.entity.PaymentMethod;
import com.shop.domain.order.service.CheckoutPreviewService;
import com.shop.domain.order.service.OrderService;
import com.shop.global.common.PageDefaults;
import com.shop.global.common.PagingParams;
import com.shop.global.exception.BusinessException;
import com.shop.global.idempotency.IdempotencyRecord;
import com.shop.global.idempotency.IdempotencyService;
import com.shop.global.idempotency.OrderWriteIdempotencyGuard;
import com.shop.global.security.SecurityUtil;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 주문 SSR 컨트롤러.
 *
 * <h3>[P0] 멱등성 키 패턴 적용 — 폼 더블 서브밋 방지</h3>
 *
 * <p><b>문제:</b> 결제 버튼 더블 클릭, 브라우저 뒤로가기 후 재제출, 느린 네트워크에서의
 * 사용자 반복 클릭 등으로 동일한 주문이 중복 생성될 수 있다.</p>
 *
 * <p><b>해결:</b> 체크아웃 페이지 렌더링 시 UUID를 생성하여 hidden input에 포함한다.
 * 주문 생성 시 이 키를 멱등성 키로 사용하여 중복 주문을 차단한다.
 * JavaScript 비활성화 환경에서도 동작하는 서버 사이드 방어이다.</p>
 *
 * <p><b>기존 JS 더블 클릭 방지와의 역할 분담:</b></p>
 * <ul>
 *   <li>JS 비활성화 (disabled 속성) → UX 최적화, 즉각적인 피드백</li>
 *   <li>서버 멱등성 키 → 데이터 정합성 보장, JS 우회/실패 시에도 동작</li>
 * </ul>
 *
 * <h3>[Phase 3 코드 품질] 컨트롤러 비즈니스 로직 제거</h3>
 *
 * <p><b>문제:</b> checkoutPage()에서 CartService, UserService, CouponService,
 * OrderService를 순차적으로 호출하며 배송비/최종금액 계산 및 쿠폰 표시명 생성 등
 * 다단계 비즈니스 로직을 컨트롤러가 직접 수행했다. 컨트롤러가 4개 서비스에
 * 의존하여 결합도가 높았다.</p>
 *
 * <p><b>해결:</b> 체크아웃 프리뷰 로직을 {@link CheckoutPreviewService}로 이동하고,
 * 컨트롤러는 서비스 호출 + 모델 바인딩만 담당한다. 컨트롤러 의존성이
 * 4개(OrderService, CartService, UserService, CouponService) → 2개(OrderService, CheckoutPreviewService)로 감소.</p>
 */
@Controller
@RequestMapping("/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final CheckoutPreviewService checkoutPreviewService;
    private final IdempotencyService idempotencyService;
    private final OrderWriteIdempotencyGuard orderWriteIdempotencyGuard;

    public OrderController(OrderService orderService,
                           CheckoutPreviewService checkoutPreviewService,
                           IdempotencyService idempotencyService,
                           OrderWriteIdempotencyGuard orderWriteIdempotencyGuard) {
        this.orderService = orderService;
        this.checkoutPreviewService = checkoutPreviewService;
        this.idempotencyService = idempotencyService;
        this.orderWriteIdempotencyGuard = orderWriteIdempotencyGuard;
    }

    /**
     * 주문/결제 페이지.
     *
     * <p>[Phase 3 코드 품질] 비즈니스 로직을 CheckoutPreviewService로 위임.
     * 기존에는 이 메서드에서 장바구니 조회, 사용자 조회, 배송비 계산, 최종금액 계산,
     * 쿠폰 조회, 쿠폰 표시명 생성을 직접 수행했다 (6단계).
     * 이제 CheckoutPreviewService.getPreview() 한 번 호출로 대체된다.</p>
     *
     * [P1-6] cartItemIds 파라미터 추가: 장바구니 선택 주문 지원.
     * 장바구니 페이지에서 체크된 항목의 ID가 전달되면 해당 항목만 표시한다.
     * 파라미터가 없으면 전체 장바구니를 표시한다 (기존 동작 호환).
     */
    @GetMapping("/checkout")
    public String checkoutPage(@RequestParam(required = false) List<Long> cartItemIds, Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        CheckoutPreview preview = checkoutPreviewService.getPreview(userId, cartItemIds);
        if (preview == null) {
            return "redirect:/cart";
        }

        model.addAttribute("cartItems", preview.cartItems());
        model.addAttribute("cartItemIds", preview.cartItemIds());
        model.addAttribute("totalPrice", preview.totalPrice());
        model.addAttribute("estimatedShippingFee", preview.estimatedShippingFee());
        model.addAttribute("estimatedFinalAmount", preview.estimatedFinalAmount());
        model.addAttribute("user", preview.user());
        model.addAttribute("pointBalance", preview.pointBalance());
        model.addAttribute("availableCoupons", preview.availableCoupons());
        model.addAttribute("couponDisplayNames", preview.couponDisplayNames());
        model.addAttribute("paymentMethods", Arrays.asList(PaymentMethod.values()));

        // [P0] 멱등성 키: 체크아웃 페이지 렌더링마다 새 UUID를 생성하여 hidden input에 포함한다.
        // 폼 제출 시 이 키가 함께 전송되어 더블 서브밋, 브라우저 뒤로가기 후 재제출을 차단한다.
        // 페이지를 새로고침(GET)하면 새 UUID가 발급되므로 사용자는 정상적으로 재주문 가능하다.
        model.addAttribute("idempotencyKey", UUID.randomUUID().toString());

        return "order/checkout";
    }

    /**
     * 주문 생성 (멱등성 보장).
     *
     * <p>[P0] hidden input으로 전달된 멱등성 키를 사용하여 폼 더블 서브밋을 차단한다.
     * 체크아웃 페이지 렌더링 시 UUID가 생성되므로, 페이지 새로고침(GET) 없이
     * 뒤로가기 → 재제출하면 동일한 키가 전송되어 중복 주문이 방지된다.</p>
     *
     * <p><b>멱등성 키가 없는 경우:</b> 기존 비멱등 동작으로 폴백한다.
     * 구버전 폼 템플릿이나 테스트에서 idempotencyKey를 전달하지 않아도 동작한다.</p>
     */
    @PostMapping
    public String createOrder(@Valid OrderCreateRequest request,
                              BindingResult bindingResult,
                              @RequestParam(name = "idempotencyKey", required = false) String idempotencyKey,
                              RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", "입력값을 확인해주세요.");
            redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.orderCreateRequest", bindingResult);
            redirectAttributes.addFlashAttribute("orderCreateRequest", request);
            return "redirect:/orders/checkout";
        }

        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();

        // 멱등성 키가 없으면 기존 비멱등 동작으로 폴백 (하위 호환)
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            orderWriteIdempotencyGuard.handleMissingKey("ssr", "create", userId);
            return createOrderWithoutIdempotency(userId, request, redirectAttributes);
        }

        // ── 멱등성 키 패턴 적용 ────────────────────────────

        // 1단계: 기존 레코드 확인
        Optional<IdempotencyRecord> existing = idempotencyService.findExisting(userId, idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyRecord prev = existing.get();

            if (prev.isCompleted()) {
                // 이전 성공 결과가 있으면 같은 주문 상세 페이지로 리다이렉트
                log.info("SSR 멱등성 키 중복 요청 (COMPLETED) - userId={}, key={}, orderId={}",
                        userId, idempotencyKey, prev.getResourceId());
                redirectAttributes.addFlashAttribute("successMessage", "주문이 완료되었습니다.");
                return "redirect:/orders/" + prev.getResourceId();
            }

            if (prev.isProcessing()) {
                // 이전 요청 처리 중 — 체크아웃으로 돌려보내고 안내 메시지 표시
                log.warn("SSR 멱등성 키 중복 요청 (PROCESSING) - userId={}, key={}", userId, idempotencyKey);
                redirectAttributes.addFlashAttribute("errorMessage",
                        "이전 주문 요청이 처리 중입니다. 잠시 후 주문 내역을 확인해주세요.");
                return "redirect:/orders";
            }

            // FAILED — 재시도 허용 (아래 initRecord에서 retryAfterFailure 사용)
            log.info("SSR 멱등성 키 재시도 (FAILED) - userId={}, key={}", userId, idempotencyKey);
        }

        // 2단계: PROCESSING 레코드 생성
        IdempotencyRecord record;
        boolean isRetry = existing.isPresent();
        try {
            record = isRetry
                    ? idempotencyService.retryAfterFailure(userId, idempotencyKey, "ORDER")
                    : idempotencyService.initRecord(userId, idempotencyKey, "ORDER");
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 키로 폼이 제출된 경우
            log.info("SSR 멱등성 키 동시 삽입 충돌 - userId={}, key={}", userId, idempotencyKey);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "주문 요청이 중복되었습니다. 잠시 후 주문 내역을 확인해주세요.");
            return "redirect:/orders";
        }

        // 3단계: 주문 생성 실행
        try {
            Order order = idempotencyService.executeWithCompletion(
                    record.getRecordId(),
                    () -> orderService.createOrder(userId, request),
                    Order::getOrderId,
                    HttpStatus.CREATED.value());

            redirectAttributes.addFlashAttribute("successMessage", "주문이 완료되었습니다.");
            return "redirect:/orders/" + order.getOrderId();

        } catch (BusinessException e) {
            // 5단계: 비즈니스 예외 → FAILED 전환 (같은 키로 재시도 가능)
            idempotencyService.markFailed(record.getRecordId());
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/orders/checkout";
        } catch (Exception e) {
            // 예상치 못한 예외 → FAILED 전환 후 재throw
            idempotencyService.markFailed(record.getRecordId());
            throw e;
        }
    }

    /**
     * 멱등성 키 없이 주문을 생성한다 (하위 호환용).
     *
     * <p>구버전 폼 템플릿이나 테스트에서 idempotencyKey를 전달하지 않는 경우
     * 기존 동작을 그대로 유지한다.</p>
     */
    private String createOrderWithoutIdempotency(Long userId, OrderCreateRequest request,
                                                  RedirectAttributes redirectAttributes) {
        try {
            Order order = orderService.createOrder(userId, request);
            redirectAttributes.addFlashAttribute("successMessage", "주문이 완료되었습니다.");
            return "redirect:/orders/" + order.getOrderId();
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/orders/checkout";
        }
    }

    /**
     * [Phase 18] CQRS: 주문 목록에 경량 읽기 모델(OrderListReadModel) 사용.
     * 기존 Page&lt;Order&gt; + fetchOrderItems() 2-쿼리 패턴을 단일 쿼리로 대체하여
     * 아이템 수를 위한 추가 쿼리가 불필요하다.
     */
    @GetMapping
    public String orderList(@RequestParam(defaultValue = "0") int page, Model model) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        int normalizedPage = PagingParams.normalizePage(page);
        model.addAttribute("orders", orderService.getOrdersByUserFlat(userId,
                PageRequest.of(normalizedPage, PageDefaults.DEFAULT_LIST_SIZE)));
        model.addAttribute("orderStatusLabels", OrderStatus.labelsByCode());
        model.addAttribute("orderStatusBadgeClasses", OrderStatus.badgeClassesByCode());
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


    @PostMapping("/{orderId}/partial-cancel")
    public String partialCancel(@PathVariable Long orderId,
                                @RequestParam Long orderItemId,
                                @RequestParam Integer quantity,
                                RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        try {
            orderService.partialCancel(orderId, userId, orderItemId, quantity);
            redirectAttributes.addFlashAttribute("successMessage", "부분 취소가 완료되었습니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/orders/" + orderId;
    }

    @PostMapping("/{orderId}/return")
    public String requestReturn(@PathVariable Long orderId,
                                @RequestParam Long orderItemId,
                                @RequestParam Integer quantity,
                                @RequestParam(defaultValue = "OTHER") String returnReason,
                                RedirectAttributes redirectAttributes) {
        Long userId = SecurityUtil.getCurrentUserId().orElseThrow();
        try {
            orderService.requestReturn(orderId, userId, orderItemId, quantity, returnReason);
            redirectAttributes.addFlashAttribute("successMessage", "반품 신청이 접수되었습니다. 관리자 승인 후 처리됩니다.");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/orders/" + orderId;
    }

}
