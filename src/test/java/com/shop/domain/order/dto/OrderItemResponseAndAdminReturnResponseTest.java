package com.shop.domain.order.dto;

import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderItemStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [Step 3] DTO 매핑 단위 테스트.
 *
 * <p>OrderItemResponse.from() 팩토리 메서드가 엔티티의 상태 필드를 올바르게
 * DTO로 변환하는지 검증한다. REST API 응답에 상태 정보가 정확히 포함되어야
 * 클라이언트가 반품 진행 상태를 올바르게 표시할 수 있다.</p>
 */
class OrderItemResponseAndAdminReturnResponseTest {

    // ═══════════════════════════════════════════════════════════
    // OrderItemResponse.from() — 상태 필드 매핑 검증
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("OrderItemResponse.from() 매핑")
    class OrderItemResponseMapping {

        /**
         * NORMAL 상태 아이템의 기본 매핑을 검증한다.
         * 반품 관련 필드(returnReason, rejectReason 등)는 null이어야 한다.
         */
        @Test
        @DisplayName("NORMAL 상태 아이템 — 기존 필드 + 신규 상태 필드가 모두 올바르게 매핑된다")
        void normalItem_allFieldsMappedCorrectly() {
            OrderItem item = createMockOrderItem(
                    OrderItemStatus.NORMAL, null, null, 0, null, null);

            OrderItemResponse response = OrderItemResponse.from(item);

            // 기존 필드 검증
            assertThat(response.orderItemId()).isEqualTo(100L);
            assertThat(response.productId()).isEqualTo(10L);
            assertThat(response.productName()).isEqualTo("테스트 상품");
            assertThat(response.quantity()).isEqualTo(3);
            assertThat(response.cancelledQuantity()).isEqualTo(0);
            assertThat(response.returnedQuantity()).isEqualTo(0);
            assertThat(response.remainingQuantity()).isEqualTo(3);
            assertThat(response.unitPrice()).isEqualByComparingTo("10000");
            assertThat(response.subtotal()).isEqualByComparingTo("30000");
            assertThat(response.cancelledAmount()).isEqualByComparingTo("0");
            assertThat(response.returnedAmount()).isEqualByComparingTo("0");

            // Step 3 신규 상태 필드 검증
            assertThat(response.status()).isEqualTo("NORMAL");
            assertThat(response.statusLabel()).isEqualTo("정상");
            assertThat(response.statusBadgeClass()).isEqualTo("bg-green-100 text-green-700");
            assertThat(response.returnReason()).isNull();
            assertThat(response.rejectReason()).isNull();
            assertThat(response.pendingReturnQuantity()).isEqualTo(0);
            assertThat(response.returnRequestedAt()).isNull();
            assertThat(response.returnedAt()).isNull();
        }

        /**
         * RETURN_REQUESTED 상태 아이템의 매핑을 검증한다.
         * 반품 사유와 신청일시가 포함되어야 한다.
         */
        @Test
        @DisplayName("RETURN_REQUESTED 상태 — 반품 사유, 대기 수량, 신청일시가 매핑된다")
        void returnRequestedItem_includesReturnFields() {
            LocalDateTime requestedAt = LocalDateTime.of(2025, 3, 1, 14, 30);
            OrderItem item = createMockOrderItem(
                    OrderItemStatus.RETURN_REQUESTED, "DEFECT", null,
                    1, requestedAt, null);

            OrderItemResponse response = OrderItemResponse.from(item);

            assertThat(response.status()).isEqualTo("RETURN_REQUESTED");
            assertThat(response.statusLabel()).isEqualTo("반품신청");
            assertThat(response.statusBadgeClass()).isEqualTo("bg-orange-100 text-orange-700");
            assertThat(response.returnReason()).isEqualTo("DEFECT");
            assertThat(response.rejectReason()).isNull();
            assertThat(response.pendingReturnQuantity()).isEqualTo(1);
            assertThat(response.returnRequestedAt()).isEqualTo(requestedAt);
            assertThat(response.returnedAt()).isNull();
        }

        /**
         * RETURN_REJECTED 상태 아이템의 매핑을 검증한다.
         * 반품 사유와 거절 사유가 모두 포함되어야 한다.
         */
        @Test
        @DisplayName("RETURN_REJECTED 상태 — 거절 사유가 매핑된다")
        void returnRejectedItem_includesRejectReason() {
            LocalDateTime requestedAt = LocalDateTime.of(2025, 3, 1, 14, 30);
            OrderItem item = createMockOrderItem(
                    OrderItemStatus.RETURN_REJECTED, "CHANGE_OF_MIND", "사용 흔적 발견",
                    0, requestedAt, null);

            OrderItemResponse response = OrderItemResponse.from(item);

            assertThat(response.status()).isEqualTo("RETURN_REJECTED");
            assertThat(response.statusLabel()).isEqualTo("반품거절");
            assertThat(response.returnReason()).isEqualTo("CHANGE_OF_MIND");
            assertThat(response.rejectReason()).isEqualTo("사용 흔적 발견");
            assertThat(response.pendingReturnQuantity()).isEqualTo(0);
        }

        /**
         * RETURNED 상태 아이템의 매핑을 검증한다.
         * 반품 완료 일시가 포함되어야 한다.
         */
        @Test
        @DisplayName("RETURNED 상태 — 반품 완료 일시가 매핑된다")
        void returnedItem_includesReturnedAt() {
            LocalDateTime requestedAt = LocalDateTime.of(2025, 3, 1, 14, 30);
            LocalDateTime returnedAt = LocalDateTime.of(2025, 3, 2, 10, 0);
            OrderItem item = createMockOrderItem(
                    OrderItemStatus.RETURNED, "WRONG_ITEM", null,
                    0, requestedAt, returnedAt);

            OrderItemResponse response = OrderItemResponse.from(item);

            assertThat(response.status()).isEqualTo("RETURNED");
            assertThat(response.statusLabel()).isEqualTo("반품완료");
            assertThat(response.statusBadgeClass()).isEqualTo("bg-gray-100 text-gray-700");
            assertThat(response.returnedAt()).isEqualTo(returnedAt);
            assertThat(response.pendingReturnQuantity()).isEqualTo(0);
        }

        /**
         * CANCELLED 상태 아이템의 매핑을 검증한다.
         */
        @Test
        @DisplayName("CANCELLED 상태 — 취소 배지 클래스가 매핑된다")
        void cancelledItem_hasCancelledBadge() {
            OrderItem item = createMockOrderItem(
                    OrderItemStatus.CANCELLED, null, null, 0, null, null);

            OrderItemResponse response = OrderItemResponse.from(item);

            assertThat(response.status()).isEqualTo("CANCELLED");
            assertThat(response.statusLabel()).isEqualTo("취소");
            assertThat(response.statusBadgeClass()).isEqualTo("bg-red-100 text-red-700");
        }

        // ── 테스트 헬퍼 ──

        private OrderItem createMockOrderItem(OrderItemStatus status,
                                              String returnReason,
                                              String rejectReason,
                                              int pendingReturnQuantity,
                                              LocalDateTime returnRequestedAt,
                                              LocalDateTime returnedAt) {
            OrderItem item = mock(OrderItem.class);
            lenient().when(item.getOrderItemId()).thenReturn(100L);
            lenient().when(item.getProductId()).thenReturn(10L);
            lenient().when(item.getProductName()).thenReturn("테스트 상품");
            lenient().when(item.getQuantity()).thenReturn(3);
            lenient().when(item.getCancelledQuantity()).thenReturn(0);
            lenient().when(item.getReturnedQuantity()).thenReturn(0);
            lenient().when(item.getRemainingQuantity()).thenReturn(3);
            lenient().when(item.getUnitPrice()).thenReturn(new BigDecimal("10000"));
            lenient().when(item.getSubtotal()).thenReturn(new BigDecimal("30000"));
            lenient().when(item.getCancelledAmount()).thenReturn(BigDecimal.ZERO);
            lenient().when(item.getReturnedAmount()).thenReturn(BigDecimal.ZERO);
            // Step 3 신규 필드
            lenient().when(item.getStatus()).thenReturn(status);
            lenient().when(item.getReturnReason()).thenReturn(returnReason);
            lenient().when(item.getRejectReason()).thenReturn(rejectReason);
            lenient().when(item.getPendingReturnQuantity()).thenReturn(pendingReturnQuantity);
            lenient().when(item.getReturnRequestedAt()).thenReturn(returnRequestedAt);
            lenient().when(item.getReturnedAt()).thenReturn(returnedAt);
            return item;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // AdminReturnResponse — 반품 사유 레이블 변환
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AdminReturnResponse 반품 사유 레이블")
    class AdminReturnResponseLabel {

        @Test
        @DisplayName("각 반품 사유 코드가 올바른 한글 레이블로 변환된다")
        void allReasonCodes_convertToKoreanLabels() {
            assertThat(createResponse("DEFECT").getReturnReasonLabel()).isEqualTo("상품 불량/파손");
            assertThat(createResponse("WRONG_ITEM").getReturnReasonLabel()).isEqualTo("오배송");
            assertThat(createResponse("CHANGE_OF_MIND").getReturnReasonLabel()).isEqualTo("단순 변심");
            assertThat(createResponse("SIZE_ISSUE").getReturnReasonLabel()).isEqualTo("사이즈 불일치");
            assertThat(createResponse("OTHER").getReturnReasonLabel()).isEqualTo("기타");
        }

        @Test
        @DisplayName("알 수 없는 사유 코드는 원문 그대로 반환된다")
        void unknownReasonCode_returnsAsIs() {
            assertThat(createResponse("CUSTOM_REASON").getReturnReasonLabel())
                    .isEqualTo("CUSTOM_REASON");
        }

        @Test
        @DisplayName("null 사유는 빈 문자열을 반환한다")
        void nullReason_returnsEmptyString() {
            assertThat(createResponse(null).getReturnReasonLabel()).isEmpty();
        }

        private AdminReturnResponse createResponse(String returnReason) {
            return new AdminReturnResponse(
                    1L, "ORD-001", 100L, "상품A", 1,
                    returnReason, LocalDateTime.now(), "홍길동", "test@test.com"
            );
        }
    }
}
