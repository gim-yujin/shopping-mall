package com.shop.domain.flashsale.service;

import com.shop.domain.flashsale.dto.FlashSalePurchaseResponse;
import com.shop.domain.flashsale.entity.FlashSale;
import com.shop.domain.flashsale.entity.FlashSaleItem;
import com.shop.domain.flashsale.entity.FlashSalePurchase;
import com.shop.domain.flashsale.exception.DuplicateFlashSalePurchaseException;
import com.shop.domain.flashsale.exception.FlashSaleSoldOutException;
import com.shop.domain.flashsale.exception.FlashSaleWindowClosedException;
import com.shop.domain.flashsale.repository.FlashSaleItemRepository;
import com.shop.domain.flashsale.repository.FlashSalePurchaseRepository;
import com.shop.domain.order.entity.Order;
import com.shop.global.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * [Phase 23-2] 플래시 세일 구매 명령 서비스.
 *
 * <p>단일 {@code @Transactional} 경계 안에서 다음 5계층을 통과시킨다:</p>
 * <ol>
 *   <li><b>L2 재검</b>: 캐시 우회 DB 조회로 세일 상태·시간 검증</li>
 *   <li><b>L4 CAS</b>: {@code reserveAtomic} 단일 시도 — 0 반환 시 {@code SOLD_OUT}</li>
 *   <li>주문 발행: {@link FlashSaleOrderFactory}가 최소 Order + 단일 OrderItem 저장</li>
 *   <li>감사 로그: {@code FlashSalePurchase} 저장 & 즉시 flush</li>
 *   <li><b>L5 UNIQUE</b>: {@code uk_fsp_user_sale} 위반 시 보상 복구 후 {@code ONE_PER_USER}</li>
 * </ol>
 *
 * <p>CAS 재시도는 하지 않는다(선착순 공정성 보존). 예약 후 UNIQUE 위반 외 모든 예외는
 * 트랜잭션 롤백에 맡기고, UNIQUE 위반만 즉시 {@code restoreAtomic}으로 보상한다
 * (즉시 flush가 일어났을 수 있어 명시적 복구가 안전망으로 동작).</p>
 */
@Service
public class FlashSaleCommandService {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleCommandService.class);
    private static final int FIXED_QUANTITY = 1;

    private final FlashSaleItemRepository itemRepository;
    private final FlashSalePurchaseRepository purchaseRepository;
    private final FlashSaleOrderFactory orderFactory;

    public FlashSaleCommandService(FlashSaleItemRepository itemRepository,
                                   FlashSalePurchaseRepository purchaseRepository,
                                   FlashSaleOrderFactory orderFactory) {
        this.itemRepository = itemRepository;
        this.purchaseRepository = purchaseRepository;
        this.orderFactory = orderFactory;
    }

    @Transactional
    public FlashSalePurchaseResponse purchase(Long flashSaleId, Long flashSaleItemId, Long userId) {
        FlashSaleItem item = itemRepository.findByItemAndSale(flashSaleItemId, flashSaleId)
                .orElseThrow(() -> new ResourceNotFoundException("플래시 세일 상품", flashSaleItemId));

        FlashSale sale = item.getFlashSale();
        if (!sale.isOpenAt(LocalDateTime.now())) {
            throw new FlashSaleWindowClosedException(flashSaleId);
        }

        int reserved = itemRepository.reserveAtomic(flashSaleItemId, FIXED_QUANTITY);
        if (reserved == 0) {
            log.info("event=flash_sale_reserve_soldout sale_id={} item_id={} user_id={}",
                    flashSaleId, flashSaleItemId, userId);
            throw new FlashSaleSoldOutException(flashSaleItemId);
        }

        try {
            Order order = orderFactory.create(userId, item, FIXED_QUANTITY);
            purchaseRepository.save(FlashSalePurchase.record(flashSaleId, userId, order.getOrderId()));
            purchaseRepository.flush();
            log.info("event=flash_sale_reserve_success sale_id={} item_id={} user_id={} order_id={}",
                    flashSaleId, flashSaleItemId, userId, order.getOrderId());
            return FlashSalePurchaseResponse.of(order, item, FIXED_QUANTITY);
        } catch (DataIntegrityViolationException e) {
            itemRepository.restoreAtomic(flashSaleItemId, FIXED_QUANTITY);
            log.info("event=flash_sale_purchase_duplicate sale_id={} user_id={}", flashSaleId, userId);
            throw new DuplicateFlashSalePurchaseException(flashSaleId, userId);
        }
    }
}
