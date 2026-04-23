package com.shop.domain.flashsale.service;

import com.shop.domain.flashsale.dto.FlashSaleDetailResponse;
import com.shop.domain.flashsale.dto.FlashSaleListItemResponse;
import com.shop.domain.flashsale.entity.FlashSale;
import com.shop.domain.flashsale.entity.FlashSaleItem;
import com.shop.domain.flashsale.repository.FlashSaleItemRepository;
import com.shop.domain.flashsale.repository.FlashSaleRepository;
import com.shop.domain.product.entity.Product;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 플래시 세일 읽기 전용 서비스.
 *
 * <p>캐시 정책은 <code>docs/backlog-flash-sale.md</code> §6을 따른다.</p>
 * <ul>
 *   <li><b>flashSaleActiveList</b>(TTL 1s) — 목록 응답. 시작/종료 상태 전환 가시 지연을 1초로 제한.</li>
 *   <li><b>flashSaleMeta</b>(TTL 200ms) — 상세 응답. 남은 수량은 근사치이며 확정 판정은 구매 CAS에서 수행.</li>
 * </ul>
 */
@Service
public class FlashSaleQueryService {

    private static final String LIST_CACHE_KEY = "all";
    private static final int UPCOMING_WINDOW_HOURS = 24;

    private final FlashSaleRepository flashSaleRepository;
    private final FlashSaleItemRepository flashSaleItemRepository;

    public FlashSaleQueryService(FlashSaleRepository flashSaleRepository,
                                 FlashSaleItemRepository flashSaleItemRepository) {
        this.flashSaleRepository = flashSaleRepository;
        this.flashSaleItemRepository = flashSaleItemRepository;
    }

    @Cacheable(value = "flashSaleActiveList", key = "'" + LIST_CACHE_KEY + "'", sync = true)
    @Transactional(readOnly = true)
    public List<FlashSaleListItemResponse> listActiveAndUpcoming() {
        LocalDateTime now = LocalDateTime.now();
        List<FlashSale> sales = flashSaleRepository.findActiveAndUpcoming(
                now.plusHours(UPCOMING_WINDOW_HOURS));
        if (sales.isEmpty()) {
            return List.of();
        }

        List<Long> saleIds = new ArrayList<>(sales.size());
        for (FlashSale sale : sales) {
            saleIds.add(sale.getFlashSaleId());
        }
        List<FlashSaleItem> items = flashSaleItemRepository.findAllByFlashSaleIdInWithProduct(saleIds);

        Map<Long, List<FlashSaleItem>> itemsBySaleId = new HashMap<>();
        for (FlashSaleItem item : items) {
            itemsBySaleId
                    .computeIfAbsent(item.getFlashSale().getFlashSaleId(), k -> new ArrayList<>())
                    .add(item);
        }

        List<FlashSaleListItemResponse> result = new ArrayList<>(sales.size());
        for (FlashSale sale : sales) {
            List<FlashSaleItem> saleItems = itemsBySaleId.getOrDefault(sale.getFlashSaleId(), List.of());
            saleItems.sort(Comparator.comparing(FlashSaleItem::getFlashSaleItemId));

            List<FlashSaleListItemResponse.Item> itemDtos = new ArrayList<>(saleItems.size());
            for (FlashSaleItem item : saleItems) {
                Product product = item.getProduct();
                itemDtos.add(new FlashSaleListItemResponse.Item(
                        item.getFlashSaleItemId(),
                        product.getProductId(),
                        product.getProductName(),
                        item.getSalePrice(),
                        product.getOriginalPrice(),
                        item.getRemainingQuantity(),
                        item.getAllocatedQuantity(),
                        item.getPerUserLimit()
                ));
            }

            result.add(new FlashSaleListItemResponse(
                    sale.getFlashSaleId(),
                    sale.getTitle(),
                    sale.getStatus(),
                    sale.getStartTime(),
                    sale.getEndTime(),
                    itemDtos
            ));
        }
        return result;
    }

    @Cacheable(value = "flashSaleMeta", key = "#flashSaleId", sync = true)
    @Transactional(readOnly = true)
    public FlashSaleDetailResponse getDetail(Long flashSaleId) {
        FlashSale sale = flashSaleRepository.findById(flashSaleId)
                .orElseThrow(() -> new ResourceNotFoundException("플래시 세일", flashSaleId));

        List<FlashSaleItem> items = flashSaleItemRepository.findAllByFlashSaleIdWithProduct(flashSaleId);
        items.sort(Comparator.comparing(FlashSaleItem::getFlashSaleItemId));

        List<FlashSaleDetailResponse.Item> itemDtos = new ArrayList<>(items.size());
        for (FlashSaleItem item : items) {
            Product product = item.getProduct();
            itemDtos.add(new FlashSaleDetailResponse.Item(
                    item.getFlashSaleItemId(),
                    product.getProductId(),
                    product.getProductName(),
                    product.getDescription(),
                    item.getSalePrice(),
                    product.getOriginalPrice(),
                    item.getRemainingQuantity(),
                    item.getAllocatedQuantity(),
                    item.getPerUserLimit(),
                    product.getThumbnailUrl()
            ));
        }

        return new FlashSaleDetailResponse(
                sale.getFlashSaleId(),
                sale.getTitle(),
                sale.getStatus(),
                sale.getStartTime(),
                sale.getEndTime(),
                itemDtos
        );
    }
}
