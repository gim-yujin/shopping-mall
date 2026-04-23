package com.shop.domain.flashsale.service;

import com.shop.domain.category.entity.Category;
import com.shop.domain.flashsale.dto.FlashSaleDetailResponse;
import com.shop.domain.flashsale.dto.FlashSaleListItemResponse;
import com.shop.domain.flashsale.entity.FlashSale;
import com.shop.domain.flashsale.entity.FlashSaleItem;
import com.shop.domain.flashsale.entity.FlashSaleStatus;
import com.shop.domain.flashsale.repository.FlashSaleItemRepository;
import com.shop.domain.flashsale.repository.FlashSaleRepository;
import com.shop.domain.product.entity.Product;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlashSaleQueryServiceTest {

    @Mock
    private FlashSaleRepository flashSaleRepository;

    @Mock
    private FlashSaleItemRepository flashSaleItemRepository;

    @InjectMocks
    private FlashSaleQueryService flashSaleQueryService;

    @Test
    @DisplayName("목록 조회 시 진행중/예정 세일을 각 세일별 아이템 리스트와 함께 반환한다")
    void listActiveAndUpcoming_groupsItemsPerSale() {
        FlashSale active = newFlashSale(10L, "오전 특가", FlashSaleStatus.ACTIVE,
                LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(1));
        FlashSale upcoming = newFlashSale(20L, "오후 특가", FlashSaleStatus.SCHEDULED,
                LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(3));

        Product p1 = newProduct(100L, "A 상품", new BigDecimal("30000"));
        Product p2 = newProduct(200L, "B 상품", new BigDecimal("50000"));

        FlashSaleItem i1 = newItem(1001L, active, p1, new BigDecimal("19900"), 50, 50);
        FlashSaleItem i2 = newItem(1002L, upcoming, p2, new BigDecimal("39900"), 30, 30);

        when(flashSaleRepository.findActiveAndUpcoming(any(LocalDateTime.class)))
                .thenReturn(new java.util.ArrayList<>(List.of(active, upcoming)));
        when(flashSaleItemRepository.findAllByFlashSaleIdInWithProduct(anyList()))
                .thenReturn(new java.util.ArrayList<>(List.of(i1, i2)));

        List<FlashSaleListItemResponse> result = flashSaleQueryService.listActiveAndUpcoming();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).flashSaleId()).isEqualTo(10L);
        assertThat(result.get(0).items()).hasSize(1);
        assertThat(result.get(0).items().get(0).productName()).isEqualTo("A 상품");
        assertThat(result.get(1).items().get(0).salePrice()).isEqualByComparingTo("39900");
    }

    @Test
    @DisplayName("목록 조회 결과가 비어 있으면 빈 리스트를 반환한다")
    void listActiveAndUpcoming_emptyWhenNoSales() {
        when(flashSaleRepository.findActiveAndUpcoming(any(LocalDateTime.class))).thenReturn(List.of());

        List<FlashSaleListItemResponse> result = flashSaleQueryService.listActiveAndUpcoming();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("상세 조회 시 상품 정보·남은 수량·썸네일을 포함한다")
    void getDetail_includesProductMetaAndRemaining() {
        FlashSale sale = newFlashSale(77L, "상세 테스트", FlashSaleStatus.ACTIVE,
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusHours(1));
        Product p = newProduct(500L, "상세상품", new BigDecimal("99000"));
        FlashSaleItem item = newItem(7001L, sale, p, new BigDecimal("49000"), 100, 42);

        when(flashSaleRepository.findById(eq(77L))).thenReturn(Optional.of(sale));
        when(flashSaleItemRepository.findAllByFlashSaleIdWithProduct(eq(77L)))
                .thenReturn(new java.util.ArrayList<>(List.of(item)));

        FlashSaleDetailResponse detail = flashSaleQueryService.getDetail(77L);

        assertThat(detail.flashSaleId()).isEqualTo(77L);
        assertThat(detail.items()).hasSize(1);
        FlashSaleDetailResponse.Item dto = detail.items().get(0);
        assertThat(dto.productName()).isEqualTo("상세상품");
        assertThat(dto.remainingApprox()).isEqualTo(42);
        assertThat(dto.allocatedQuantity()).isEqualTo(100);
        assertThat(dto.thumbnailUrl()).isEqualTo("/images/product-placeholder.svg");
    }

    @Test
    @DisplayName("존재하지 않는 세일 ID 조회 시 ResourceNotFoundException을 던진다")
    void getDetail_throwsWhenNotFound() {
        when(flashSaleRepository.findById(eq(999L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> flashSaleQueryService.getDetail(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private FlashSale newFlashSale(Long id, String title, FlashSaleStatus status,
                                   LocalDateTime start, LocalDateTime end) {
        FlashSale s = FlashSale.schedule(title, start, end);
        ReflectionTestUtils.setField(s, "flashSaleId", id);
        ReflectionTestUtils.setField(s, "status", status);
        return s;
    }

    private Product newProduct(Long id, String name, BigDecimal originalPrice) {
        Category c;
        try {
            var ctor = Category.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            c = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ReflectionTestUtils.setField(c, "categoryId", 1);
        Product p = Product.create(name, c, "설명", originalPrice, originalPrice, 1000);
        ReflectionTestUtils.setField(p, "productId", id);
        return p;
    }

    private FlashSaleItem newItem(Long id, FlashSale sale, Product product,
                                  BigDecimal salePrice, int allocated, int remaining) {
        FlashSaleItem item = FlashSaleItem.allocate(product, salePrice, allocated, 1);
        ReflectionTestUtils.setField(item, "flashSaleItemId", id);
        ReflectionTestUtils.setField(item, "flashSale", sale);
        ReflectionTestUtils.setField(item, "remainingQuantity", remaining);
        return item;
    }
}
