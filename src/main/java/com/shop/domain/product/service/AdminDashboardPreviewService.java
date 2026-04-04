package com.shop.domain.product.service;

import com.shop.domain.coupon.dto.CouponStats;
import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.dto.OrderListReadModel;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.product.dto.AdminDashboardPreview;
import com.shop.domain.product.entity.Product;
import com.shop.global.common.PageDefaults;
import com.shop.global.concurrency.StructuredConcurrencyUtils;
import com.shop.global.resilience.ResilientCallExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;

/**
 * [Phase 25] 관리자 대시보드 프리뷰 — 4개 서비스 호출 병렬화.
 *
 * <p><b>문제:</b> AdminController.dashboard()에서 상품, 주문, 쿠폰 통계, 반품 건수를
 * 순차적으로 조회하여 응답 지연이 sum(T1+T2+T3+T4)이었다.</p>
 *
 * <p><b>해결:</b> {@link StructuredTaskScope.ShutdownOnFailure}로 4개 호출을 병렬 실행.
 * 응답 지연이 max(T1,T2,T3,T4)로 단축된다.
 * 관리자 대시보드는 모든 데이터가 필수이므로 폴백 없이 실패 시 즉시 중단한다.</p>
 *
 * @see com.shop.domain.order.service.CheckoutPreviewService 동일 패턴 (체크아웃 프리뷰)
 */
@Service
@Transactional(readOnly = true)
public class AdminDashboardPreviewService {

    private static final Logger log = LoggerFactory.getLogger(AdminDashboardPreviewService.class);

    private final ProductService productService;
    private final OrderService orderService;
    private final CouponService couponService;
    private final ResilientCallExecutor resilientCallExecutor;

    public AdminDashboardPreviewService(ProductService productService,
                                         OrderService orderService,
                                         CouponService couponService,
                                         ResilientCallExecutor resilientCallExecutor) {
        this.productService = productService;
        this.orderService = orderService;
        this.couponService = couponService;
        this.resilientCallExecutor = resilientCallExecutor;
    }

    /**
     * 관리자 대시보드에 필요한 모든 데이터를 병렬 조회한다.
     *
     * <p>4개 서비스 호출 모두 필수 데이터이므로 폴백 없이 실행한다.
     * 하나라도 실패하면 ShutdownOnFailure가 나머지 작업을 취소하고 예외를 전파한다.</p>
     *
     * @return 대시보드 프리뷰 데이터
     */
    @SuppressWarnings("preview")
    public AdminDashboardPreview getPreview() {
        Page<Product> products;
        Page<OrderListReadModel> recentOrders;
        CouponStats couponStats;
        long pendingReturnCount;

        try (var scope = new StructuredTaskScope.ShutdownOnFailure(
                "admin-dashboard",
                StructuredConcurrencyUtils.propagatingThreadFactory())) {

            Subtask<Page<Product>> productsTask = scope.fork(() ->
                    resilientCallExecutor.execute("productService",
                            () -> productService.findAllForAdmin(
                                    PageRequest.of(0, PageDefaults.ADMIN_DASHBOARD_SIZE))));

            Subtask<Page<OrderListReadModel>> ordersTask = scope.fork(() ->
                    resilientCallExecutor.execute("orderService",
                            () -> orderService.getAllOrdersFlat(
                                    PageRequest.of(0, PageDefaults.ADMIN_DASHBOARD_SIZE))));

            Subtask<CouponStats> couponTask = scope.fork(() ->
                    resilientCallExecutor.execute("couponService",
                            () -> couponService.getCouponStats()));

            Subtask<Long> returnCountTask = scope.fork(() ->
                    resilientCallExecutor.execute("orderService",
                            () -> orderService.getPendingReturnCount()));

            scope.join().throwIfFailed();

            products = productsTask.get();
            recentOrders = ordersTask.get();
            couponStats = couponTask.get();
            pendingReturnCount = returnCountTask.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("관리자 대시보드 조회 중단", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
        }

        return new AdminDashboardPreview(products, recentOrders, couponStats, pendingReturnCount);
    }
}
