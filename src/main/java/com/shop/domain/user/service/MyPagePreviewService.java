package com.shop.domain.user.service;

import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.dto.OrderListReadModel;
import com.shop.domain.user.dto.MyPagePreview;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.port.MyPageOrderPort;
import com.shop.global.common.PageDefaults;
import com.shop.global.concurrency.StructuredConcurrencyUtils;
import com.shop.global.resilience.ResilientCallExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;

/**
 * [Phase 25] 마이페이지 프리뷰 — 3개 서비스 호출 병렬화 + CQRS 전환.
 *
 * <p><b>문제:</b> MyPageController.myPage()에서 userService, orderPort,
 * couponService를 순차적으로 호출하여 응답 지연이 sum(T1+T2+T3)이었다.
 * 또한 orderPort.getOrdersByUser()가 2-query 엔티티 패턴을 사용하여
 * 불필요한 OrderItem 컬렉션을 모두 로딩했다.</p>
 *
 * <p><b>해결:</b>
 * <ul>
 *   <li>3개 서비스 호출을 {@link StructuredTaskScope.ShutdownOnFailure}로 병렬 실행
 *       — 응답 지연이 max(T1,T2,T3)로 단축</li>
 *   <li>getOrdersByUser() → getOrdersByUserFlat()로 전환
 *       — 2-query 패턴 제거, v_order_list 뷰 단일 쿼리</li>
 *   <li>쿠폰 서비스는 비필수 데이터이므로 장애 시 빈 목록으로 폴백</li>
 * </ul></p>
 *
 * @see com.shop.domain.order.service.CheckoutPreviewService 동일 패턴 (체크아웃 프리뷰)
 */
@Service
@Transactional(readOnly = true)
public class MyPagePreviewService {

    private static final Logger log = LoggerFactory.getLogger(MyPagePreviewService.class);

    private final UserService userService;
    private final MyPageOrderPort orderPort;
    private final CouponService couponService;
    private final ResilientCallExecutor resilientCallExecutor;

    public MyPagePreviewService(UserService userService,
                                 MyPageOrderPort orderPort,
                                 CouponService couponService,
                                 ResilientCallExecutor resilientCallExecutor) {
        this.userService = userService;
        this.orderPort = orderPort;
        this.couponService = couponService;
        this.resilientCallExecutor = resilientCallExecutor;
    }

    /**
     * 마이페이지에 필요한 모든 프리뷰 데이터를 병렬 조회한다.
     *
     * <p>사용자 정보와 주문 목록은 필수 데이터이므로 실패 시 예외를 전파한다.
     * 쿠폰 목록은 비필수이므로 장애 시 빈 목록으로 폴백한다.</p>
     *
     * @param userId 현재 로그인 사용자 ID
     * @return 마이페이지 프리뷰 데이터
     */
    @SuppressWarnings("preview")
    public MyPagePreview getPreview(Long userId) {
        User user;
        Page<OrderListReadModel> recentOrders;
        List<UserCoupon> coupons;

        try (var scope = new StructuredTaskScope.ShutdownOnFailure(
                "mypage-preview",
                StructuredConcurrencyUtils.propagatingThreadFactory())) {

            // 사용자 — 필수 데이터 (이름, 등급, 포인트 표시)
            Subtask<User> userTask = scope.fork(() ->
                    resilientCallExecutor.execute("userService",
                            () -> userService.findById(userId)));

            // 최근 주문 — 필수 데이터, CQRS flat 쿼리 사용 (2-query 패턴 제거)
            Subtask<Page<OrderListReadModel>> ordersTask = scope.fork(() ->
                    resilientCallExecutor.execute("orderPort",
                            () -> orderPort.getOrdersByUserFlat(userId,
                                    PageRequest.of(0, PageDefaults.MYPAGE_RECENT_ORDERS))));

            // 쿠폰 — 비필수 데이터, 장애 시 빈 목록 폴백
            Subtask<List<UserCoupon>> couponsTask = scope.fork(() ->
                    resilientCallExecutor.executeWithFallback("couponService",
                            () -> couponService.getAvailableCoupons(userId),
                            ex -> {
                                log.warn("[MyPagePreview] 쿠폰 서비스 장애 — "
                                                + "쿠폰 없이 마이페이지 표시. userId={}, error={}",
                                        userId, ex.getMessage());
                                return Collections.emptyList();
                            }));

            scope.join().throwIfFailed();

            user = userTask.get();
            recentOrders = ordersTask.get();
            coupons = couponsTask.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("마이페이지 프리뷰 조회 중단", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
        }

        return new MyPagePreview(user, recentOrders, coupons);
    }
}
