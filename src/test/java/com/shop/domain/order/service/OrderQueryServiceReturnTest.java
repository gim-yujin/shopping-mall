package com.shop.domain.order.service;

import com.shop.domain.order.dto.AdminReturnResponse;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderItemStatus;
import com.shop.domain.order.repository.OrderItemRepository;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * [Step 3] OrderQueryService 반품 관리 조회 단위 테스트.
 *
 * <h3>검증 항목</h3>
 * <ul>
 *   <li>getReturnRequests: OrderItem → AdminReturnResponse 변환 정확성</li>
 *   <li>getReturnRequests: User 일괄 조회로 N+1 방지 확인</li>
 *   <li>getReturnRequests: 탈퇴 사용자(User 미존재) 방어 처리</li>
 *   <li>getReturnRequests: 빈 결과 처리</li>
 *   <li>getPendingReturnCount: 올바른 상태로 카운트 쿼리 호출</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderQueryServiceReturnTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private UserRepository userRepository;

    private OrderQueryService service;

    @BeforeEach
    void setUp() {
        service = new OrderQueryService(orderRepository, orderItemRepository, userRepository);
    }

    // ═══════════════════════════════════════════════════════════
    // getReturnRequests — 반품 대기 목록 조회
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getReturnRequests — 반품 대기 목록 조회")
    class GetReturnRequests {

        @Test
        @DisplayName("RETURN_REQUESTED 상태의 아이템이 AdminReturnResponse로 올바르게 변환된다")
        void returnRequestedItems_mappedToAdminReturnResponse() {
            // given
            Pageable pageable = PageRequest.of(0, 10);
            LocalDateTime requestedAt = LocalDateTime.of(2025, 3, 1, 14, 30);

            Order order = mockOrder(1L, "ORD-001", 101L);
            OrderItem item = mockReturnRequestedItem(100L, order, "상품A", 2,
                    "DEFECT", requestedAt);

            Page<OrderItem> itemPage = new PageImpl<>(List.of(item), pageable, 1);
            when(orderItemRepository.findByStatus(OrderItemStatus.RETURN_REQUESTED, pageable))
                    .thenReturn(itemPage);

            User user = mockUser(101L, "홍길동", "hong@test.com");
            when(userRepository.findAllById(Set.of(101L))).thenReturn(List.of(user));

            // when
            Page<AdminReturnResponse> result = service.getReturnRequests(pageable);

            // then
            assertThat(result.getTotalElements()).isEqualTo(1);
            AdminReturnResponse response = result.getContent().get(0);
            assertThat(response.orderId()).isEqualTo(1L);
            assertThat(response.orderNumber()).isEqualTo("ORD-001");
            assertThat(response.orderItemId()).isEqualTo(100L);
            assertThat(response.productName()).isEqualTo("상품A");
            assertThat(response.quantity()).isEqualTo(2);
            assertThat(response.returnReason()).isEqualTo("DEFECT");
            assertThat(response.returnRequestedAt()).isEqualTo(requestedAt);
            assertThat(response.userName()).isEqualTo("홍길동");
            assertThat(response.userEmail()).isEqualTo("hong@test.com");
        }

        /**
         * 여러 주문의 반품 아이템이 있을 때 User를 IN 쿼리로 일괄 조회하는지 검증한다.
         * N+1 문제가 발생하면 findAllById가 아닌 개별 조회가 호출될 것이다.
         */
        @Test
        @DisplayName("여러 사용자의 반품 아이템 — User를 한 번의 IN 쿼리로 일괄 조회한다")
        void multipleUsers_batchQueryForUsers() {
            // given
            Pageable pageable = PageRequest.of(0, 10);
            LocalDateTime now = LocalDateTime.now();

            Order order1 = mockOrder(1L, "ORD-001", 101L);
            Order order2 = mockOrder(2L, "ORD-002", 102L);
            OrderItem item1 = mockReturnRequestedItem(100L, order1, "상품A", 1, "DEFECT", now);
            OrderItem item2 = mockReturnRequestedItem(200L, order2, "상품B", 1, "WRONG_ITEM", now);

            Page<OrderItem> itemPage = new PageImpl<>(List.of(item1, item2), pageable, 2);
            when(orderItemRepository.findByStatus(OrderItemStatus.RETURN_REQUESTED, pageable))
                    .thenReturn(itemPage);

            User user1 = mockUser(101L, "홍길동", "hong@test.com");
            User user2 = mockUser(102L, "김철수", "kim@test.com");
            when(userRepository.findAllById(any())).thenReturn(List.of(user1, user2));

            // when
            Page<AdminReturnResponse> result = service.getReturnRequests(pageable);

            // then
            assertThat(result.getTotalElements()).isEqualTo(2);
            // findAllById가 정확히 1회만 호출됨 (N+1 방지 확인)
            verify(userRepository, times(1)).findAllById(any());
        }

        /**
         * 탈퇴 사용자의 반품 아이템이 있을 때 NPE 없이 기본값으로 처리되는지 검증한다.
         */
        @Test
        @DisplayName("탈퇴 사용자의 반품 아이템 — userName '(탈퇴)', userEmail 빈 문자열로 처리된다")
        void deletedUser_fallbackToDefaultValues() {
            // given
            Pageable pageable = PageRequest.of(0, 10);
            Order order = mockOrder(1L, "ORD-001", 999L); // 존재하지 않는 사용자
            OrderItem item = mockReturnRequestedItem(100L, order, "상품A", 1,
                    "CHANGE_OF_MIND", LocalDateTime.now());

            Page<OrderItem> itemPage = new PageImpl<>(List.of(item), pageable, 1);
            when(orderItemRepository.findByStatus(OrderItemStatus.RETURN_REQUESTED, pageable))
                    .thenReturn(itemPage);
            // findAllById가 빈 리스트 반환 (해당 userId의 User가 없음)
            when(userRepository.findAllById(any())).thenReturn(Collections.emptyList());

            // when
            Page<AdminReturnResponse> result = service.getReturnRequests(pageable);

            // then
            AdminReturnResponse response = result.getContent().get(0);
            assertThat(response.userName()).isEqualTo("(탈퇴)");
            assertThat(response.userEmail()).isEmpty();
        }

        /**
         * 반품 대기 아이템이 없을 때 빈 Page가 반환되는지 검증한다.
         */
        @Test
        @DisplayName("반품 대기 아이템 없음 — 빈 Page 반환, User 조회 미발생")
        void noReturnRequests_returnsEmptyPage() {
            // given
            Pageable pageable = PageRequest.of(0, 10);
            Page<OrderItem> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
            when(orderItemRepository.findByStatus(OrderItemStatus.RETURN_REQUESTED, pageable))
                    .thenReturn(emptyPage);

            // when
            Page<AdminReturnResponse> result = service.getReturnRequests(pageable);

            // then
            assertThat(result.getTotalElements()).isZero();
            assertThat(result.getContent()).isEmpty();
            // 빈 결과일 때 User 조회가 불필요한 빈 Set으로 호출됨
            verify(userRepository).findAllById(Set.of());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // getPendingReturnCount — 반품 대기 건수
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getPendingReturnCount — 반품 대기 건수")
    class GetPendingReturnCount {

        @Test
        @DisplayName("RETURN_REQUESTED 상태로 countByStatus를 호출한다")
        void callsCountByStatus_withReturnRequested() {
            when(orderItemRepository.countByStatus(OrderItemStatus.RETURN_REQUESTED))
                    .thenReturn(5L);

            long count = service.getPendingReturnCount();

            assertThat(count).isEqualTo(5);
            verify(orderItemRepository).countByStatus(OrderItemStatus.RETURN_REQUESTED);
        }

        @Test
        @DisplayName("반품 대기 건이 없으면 0을 반환한다")
        void noPendingReturns_returnsZero() {
            when(orderItemRepository.countByStatus(OrderItemStatus.RETURN_REQUESTED))
                    .thenReturn(0L);

            long count = service.getPendingReturnCount();

            assertThat(count).isZero();
        }
    }

    // ── 테스트 픽스처 빌더 ──────────────────────────────────────

    private Order mockOrder(Long orderId, String orderNumber, Long userId) {
        Order order = mock(Order.class);
        lenient().when(order.getOrderId()).thenReturn(orderId);
        lenient().when(order.getOrderNumber()).thenReturn(orderNumber);
        lenient().when(order.getUserId()).thenReturn(userId);
        return order;
    }

    private OrderItem mockReturnRequestedItem(Long itemId, Order order,
                                               String productName, int pendingQty,
                                               String returnReason,
                                               LocalDateTime requestedAt) {
        OrderItem item = mock(OrderItem.class);
        lenient().when(item.getOrderItemId()).thenReturn(itemId);
        lenient().when(item.getOrder()).thenReturn(order);
        lenient().when(item.getProductName()).thenReturn(productName);
        lenient().when(item.getPendingReturnQuantity()).thenReturn(pendingQty);
        lenient().when(item.getReturnReason()).thenReturn(returnReason);
        lenient().when(item.getReturnRequestedAt()).thenReturn(requestedAt);
        lenient().when(item.getStatus()).thenReturn(OrderItemStatus.RETURN_REQUESTED);
        return item;
    }

    private User mockUser(Long userId, String username, String email) {
        User user = mock(User.class);
        lenient().when(user.getUserId()).thenReturn(userId);
        lenient().when(user.getUsername()).thenReturn(username);
        lenient().when(user.getEmail()).thenReturn(email);
        return user;
    }
}
