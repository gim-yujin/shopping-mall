package com.shop.domain.flashsale.service;

import com.shop.domain.category.entity.Category;
import com.shop.domain.flashsale.dto.FlashSalePurchaseResponse;
import com.shop.domain.flashsale.entity.FlashSale;
import com.shop.domain.flashsale.entity.FlashSaleItem;
import com.shop.domain.flashsale.entity.FlashSalePurchase;
import com.shop.domain.flashsale.entity.FlashSaleStatus;
import com.shop.domain.flashsale.exception.DuplicateFlashSalePurchaseException;
import com.shop.domain.flashsale.exception.FlashSaleSoldOutException;
import com.shop.domain.flashsale.exception.FlashSaleWindowClosedException;
import com.shop.domain.flashsale.repository.FlashSaleItemRepository;
import com.shop.domain.flashsale.repository.FlashSalePurchaseRepository;
import com.shop.domain.order.entity.Order;
import com.shop.domain.product.entity.Product;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlashSaleCommandServiceTest {

    @Mock
    private FlashSaleItemRepository itemRepository;

    @Mock
    private FlashSalePurchaseRepository purchaseRepository;

    @Mock
    private FlashSaleOrderFactory orderFactory;

    private FlashSaleCommandService commandService;

    @BeforeEach
    void setUp() {
        commandService = new FlashSaleCommandService(itemRepository, purchaseRepository, orderFactory, "cas");
    }

    @Test
    @DisplayName("CAS 성공 시 주문을 생성하고 구매 기록을 저장한 뒤 응답을 반환한다")
    void purchase_success() {
        FlashSaleItem item = activeItem(100L, 10L, 50);
        Order order = mock(500L, "2026-04-25-ABC", new BigDecimal("19900"));
        when(itemRepository.findByItemAndSale(eq(100L), eq(10L))).thenReturn(Optional.of(item));
        when(itemRepository.reserveAtomic(eq(100L), eq(1))).thenReturn(1);
        when(orderFactory.create(eq(7L), eq(item), eq(1))).thenReturn(order);

        FlashSalePurchaseResponse response = commandService.purchase(10L, 100L, 7L);

        assertThat(response.orderId()).isEqualTo(500L);
        assertThat(response.flashSaleItemId()).isEqualTo(100L);
        assertThat(response.quantity()).isEqualTo(1);

        ArgumentCaptor<FlashSalePurchase> captor = ArgumentCaptor.forClass(FlashSalePurchase.class);
        verify(purchaseRepository).save(captor.capture());
        FlashSalePurchase saved = captor.getValue();
        assertThat(saved.getFlashSaleId()).isEqualTo(10L);
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getOrderId()).isEqualTo(500L);
        verify(purchaseRepository).flush();
    }

    @Test
    @DisplayName("CAS가 0을 반환하면 FlashSaleSoldOutException이 발생하고 주문이 만들어지지 않는다")
    void purchase_soldOut() {
        FlashSaleItem item = activeItem(100L, 10L, 0);
        when(itemRepository.findByItemAndSale(eq(100L), eq(10L))).thenReturn(Optional.of(item));
        when(itemRepository.reserveAtomic(eq(100L), eq(1))).thenReturn(0);

        assertThatThrownBy(() -> commandService.purchase(10L, 100L, 7L))
                .isInstanceOf(FlashSaleSoldOutException.class);

        verify(orderFactory, never()).create(any(), any(), anyInt());
        verify(purchaseRepository, never()).save(any());
    }

    @Test
    @DisplayName("중복 구매(UNIQUE 위반) 시 DuplicateFlashSalePurchaseException을 던지고 restoreAtomic은 호출하지 않는다")
    void purchase_uniqueViolation_throwsDuplicateAndSkipsExplicitRestore() {
        FlashSaleItem item = activeItem(100L, 10L, 50);
        Order order = mock(500L, "2026-04-25-ABC", new BigDecimal("19900"));
        when(itemRepository.findByItemAndSale(eq(100L), eq(10L))).thenReturn(Optional.of(item));
        when(itemRepository.reserveAtomic(eq(100L), eq(1))).thenReturn(1);
        when(orderFactory.create(eq(7L), eq(item), eq(1))).thenReturn(order);
        when(purchaseRepository.save(any(FlashSalePurchase.class)))
                .thenReturn(FlashSalePurchase.record(10L, 100L, 7L, 500L));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("uk_fsp_user_sale"))
                .when(purchaseRepository).flush();

        assertThatThrownBy(() -> commandService.purchase(10L, 100L, 7L))
                .isInstanceOf(DuplicateFlashSalePurchaseException.class);

        // Phase 23-3: Hibernate 세션이 rollback-only로 전이했으므로 명시적 보상은 하지 않는다.
        // remaining_quantity 복원은 @Transactional 롤백이 책임진다.
        verify(itemRepository, never()).restoreAtomic(anyLong(), anyInt());
    }

    @Test
    @DisplayName("세일 상태가 SCHEDULED이면 WindowClosedException이 발생한다")
    void purchase_windowClosed_whenScheduled() {
        FlashSaleItem item = itemWithSaleStatus(100L, 10L, 50, FlashSaleStatus.SCHEDULED,
                LocalDateTime.now().plusMinutes(10), LocalDateTime.now().plusHours(1));
        when(itemRepository.findByItemAndSale(eq(100L), eq(10L))).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> commandService.purchase(10L, 100L, 7L))
                .isInstanceOf(FlashSaleWindowClosedException.class);

        verify(itemRepository, never()).reserveAtomic(anyLong(), anyInt());
    }

    @Test
    @DisplayName("세일 상태가 ENDED이면 WindowClosedException이 발생한다")
    void purchase_windowClosed_whenEnded() {
        FlashSaleItem item = itemWithSaleStatus(100L, 10L, 50, FlashSaleStatus.ENDED,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));
        when(itemRepository.findByItemAndSale(eq(100L), eq(10L))).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> commandService.purchase(10L, 100L, 7L))
                .isInstanceOf(FlashSaleWindowClosedException.class);
    }

    @Test
    @DisplayName("존재하지 않는 아이템 조회 시 ResourceNotFoundException이 발생한다")
    void purchase_itemNotFound() {
        when(itemRepository.findByItemAndSale(eq(999L), eq(10L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commandService.purchase(10L, 999L, 7L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── 테스트 헬퍼 ───────────────────────────────────────

    private FlashSaleItem activeItem(Long itemId, Long saleId, int remaining) {
        return itemWithSaleStatus(itemId, saleId, remaining, FlashSaleStatus.ACTIVE,
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusHours(1));
    }

    private FlashSaleItem itemWithSaleStatus(Long itemId, Long saleId, int remaining,
                                             FlashSaleStatus status,
                                             LocalDateTime start, LocalDateTime end) {
        FlashSale sale = FlashSale.schedule("테스트", start, end);
        ReflectionTestUtils.setField(sale, "flashSaleId", saleId);
        ReflectionTestUtils.setField(sale, "status", status);

        Category category;
        try {
            var ctor = Category.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            category = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ReflectionTestUtils.setField(category, "categoryId", 1);
        Product product = Product.create("상품", category, "설명",
                new BigDecimal("29900"), new BigDecimal("29900"), 1000);
        ReflectionTestUtils.setField(product, "productId", 7000L);

        FlashSaleItem item = FlashSaleItem.allocate(product, new BigDecimal("19900"), 100, 1);
        ReflectionTestUtils.setField(item, "flashSaleItemId", itemId);
        ReflectionTestUtils.setField(item, "flashSale", sale);
        ReflectionTestUtils.setField(item, "remainingQuantity", remaining);
        return item;
    }

    private Order mock(Long orderId, String orderNumber, BigDecimal total) {
        Order order = Order.createForFlashSale(orderNumber, 7L, total, "CARD");
        ReflectionTestUtils.setField(order, "orderId", orderId);
        return order;
    }

    // ── [§13-2 #3] 서킷 브레이커 폴백 ────────────────────────────────────

    @Test
    @DisplayName("폴백 — 서킷 OPEN(CallNotPermittedException) 시 SERVICE_UNAVAILABLE 로 변환")
    void purchaseFallback_circuitOpen_throwsServiceUnavailable() {
        CircuitBreaker cb = CircuitBreaker.ofDefaults("flashSalePurchase");
        CallNotPermittedException cause = CallNotPermittedException.createCallNotPermittedException(cb);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                commandService, "purchaseFallback", 10L, 100L, 7L, cause))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("일시적으로 어려운")
                .satisfies(t -> assertThat(((BusinessException) t).getCode())
                        .isEqualTo("SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("폴백 — 인프라 예외(DB 락 획득 실패) 는 원본 그대로 전파 (GlobalExceptionHandler 매핑 보존)")
    void purchaseFallback_infraFailure_propagatesOriginal() {
        CannotAcquireLockException dbFailure =
                new CannotAcquireLockException("lock_timeout exceeded");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                commandService, "purchaseFallback", 10L, 100L, 7L, dbFailure))
                .isSameAs(dbFailure);
    }

    @Test
    @DisplayName("폴백 — 비즈니스 예외(SoldOut 등) 도 원본 그대로 전파 (ignoreExceptions 와 무관하게 fallback 진입하므로)")
    void purchaseFallback_businessException_propagatesOriginal() {
        FlashSaleSoldOutException soldOut = new FlashSaleSoldOutException(100L);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                commandService, "purchaseFallback", 10L, 100L, 7L, soldOut))
                .isSameAs(soldOut);
    }
}
