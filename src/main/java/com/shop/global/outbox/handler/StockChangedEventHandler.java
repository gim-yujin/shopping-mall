package com.shop.global.outbox.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.domain.product.service.ProductCacheEvictHelper;
import com.shop.global.outbox.OutboxEvent;
import com.shop.global.outbox.OutboxEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * [Phase 6] 상품 재고 변경 이벤트 핸들러.
 *
 * <p>기존 OutboxEventPoller.handleStockChanged()에서 추출한 핸들러.
 * Strategy 패턴 적용으로 OutboxEventPoller의 switch 분기에서 독립되었다.</p>
 *
 * <p><b>멱등성:</b> 캐시 무효화는 본질적으로 멱등하다.
 * 이미 제거된 캐시를 다시 제거해도 부작용이 없으므로
 * at-least-once 재처리에 안전하다.</p>
 */
@Component
public class StockChangedEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(StockChangedEventHandler.class);

    private final ProductCacheEvictHelper productCacheEvictHelper;
    private final ObjectMapper objectMapper;

    public StockChangedEventHandler(ProductCacheEvictHelper productCacheEvictHelper,
                                     ObjectMapper objectMapper) {
        this.productCacheEvictHelper = productCacheEvictHelper;
        this.objectMapper = objectMapper;
    }

    @Override
    public String supportedEventType() {
        return OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED;
    }

    /**
     * 상품 상세 캐시를 무효화한다.
     *
     * @param event payload 형식: {"productIds":[1,2,3]}
     */
    @Override
    public void handle(OutboxEvent event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    event.getPayload(), new TypeReference<>() { });
            @SuppressWarnings("unchecked")
            List<Number> rawIds = (List<Number>) payload.get("productIds");
            if (rawIds == null || rawIds.isEmpty()) {
                log.warn("PRODUCT_STOCK_CHANGED 이벤트에 productIds가 없음 - eventId={}",
                        event.getEventId());
                return;
            }
            List<Long> productIds = rawIds.stream().map(Number::longValue).toList();
            productCacheEvictHelper.evictProductDetailCaches(productIds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Outbox 이벤트 페이로드 파싱 실패 - eventId=" + event.getEventId(), e);
        }
    }
}
