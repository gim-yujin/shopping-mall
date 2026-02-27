package com.shop.domain.product.service;

import com.shop.domain.order.event.ProductStockChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductStockChangedEventListenerTest {

    @Mock
    private ProductCacheEvictHelper productCacheEvictHelper;

    @Test
    @DisplayName("재고 변경 이벤트를 수신하면 productDetail 캐시를 무효화한다")
    void handle_evictsProductDetailCache() {
        ProductStockChangedEventListener listener = new ProductStockChangedEventListener(productCacheEvictHelper);

        listener.handle(new ProductStockChangedEvent(List.of(1L, 2L, 3L)));

        verify(productCacheEvictHelper).evictProductDetailCaches(List.of(1L, 2L, 3L));
    }
}
