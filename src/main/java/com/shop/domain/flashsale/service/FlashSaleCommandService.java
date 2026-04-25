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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li><b>L5 UNIQUE</b>: {@code uk_fsp_user_sale} 위반 시 {@code ONE_PER_USER}</li>
 * </ol>
 *
 * <p>CAS 재시도는 하지 않는다(선착순 공정성 보존). UNIQUE 위반 시 보상은 {@code @Transactional}
 * 롤백에만 맡긴다. 명시적 {@code restoreAtomic} 호출은 사용하지 않는데, Hibernate 세션이
 * {@link org.springframework.dao.DataIntegrityViolationException} 이후
 * "rollback-only / don't flush after exception" 상태로 전이하기 때문이다 — 같은 세션으로
 * 추가 JPQL UPDATE를 발행하면 {@code AssertionFailure}가 터지면서 오히려 정합성을 해친다.
 * Phase 23-3 IT(`FlashSaleConcurrencyIT.sameUser_onePerUser_compensatesRemaining`)가
 * 트랜잭션 롤백만으로 remaining_quantity가 정확히 복원되는지를 검증한다.</p>
 */
@Service
public class FlashSaleCommandService {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleCommandService.class);
    private static final int FIXED_QUANTITY = 1;

    static final String STRATEGY_CAS = "cas";
    static final String STRATEGY_PESSIMISTIC = "pessimistic";

    private final FlashSaleItemRepository itemRepository;
    private final FlashSalePurchaseRepository purchaseRepository;
    private final FlashSaleOrderFactory orderFactory;
    private final String lockStrategy;

    @PersistenceContext
    private EntityManager entityManager;

    public FlashSaleCommandService(FlashSaleItemRepository itemRepository,
                                   FlashSalePurchaseRepository purchaseRepository,
                                   FlashSaleOrderFactory orderFactory,
                                   @Value("${flash-sale.lock-strategy:cas}") String lockStrategy) {
        this.itemRepository = itemRepository;
        this.purchaseRepository = purchaseRepository;
        this.orderFactory = orderFactory;
        if (!STRATEGY_CAS.equalsIgnoreCase(lockStrategy)
                && !STRATEGY_PESSIMISTIC.equalsIgnoreCase(lockStrategy)) {
            throw new IllegalArgumentException("flash-sale.lock-strategy 는 cas 또는 pessimistic 이어야 한다: " + lockStrategy);
        }
        this.lockStrategy = lockStrategy.toLowerCase();
        log.info("event=flash_sale_lock_strategy strategy={}", this.lockStrategy);
    }

    @Transactional
    public FlashSalePurchaseResponse purchase(Long flashSaleId, Long flashSaleItemId, Long userId) {
        FlashSaleItem item = itemRepository.findByItemAndSale(flashSaleItemId, flashSaleId)
                .orElseThrow(() -> new ResourceNotFoundException("플래시 세일 상품", flashSaleItemId));

        FlashSale sale = item.getFlashSale();
        if (!sale.isOpenAt(LocalDateTime.now())) {
            throw new FlashSaleWindowClosedException(flashSaleId);
        }

        FlashSaleItem reservedItem = STRATEGY_PESSIMISTIC.equals(lockStrategy)
                ? reservePessimistic(flashSaleId, flashSaleItemId, userId, item)
                : reserveCas(flashSaleId, flashSaleItemId, userId, item);

        try {
            Order order = orderFactory.create(userId, reservedItem, FIXED_QUANTITY);
            purchaseRepository.save(FlashSalePurchase.record(
                    flashSaleId, reservedItem.getFlashSaleItemId(), userId, order.getOrderId()));
            purchaseRepository.flush();
            log.info("event=flash_sale_reserve_success sale_id={} item_id={} user_id={} order_id={} strategy={}",
                    flashSaleId, flashSaleItemId, userId, order.getOrderId(), lockStrategy);
            return FlashSalePurchaseResponse.of(order, reservedItem, FIXED_QUANTITY);
        } catch (DataIntegrityViolationException e) {
            // 보상은 @Transactional 롤백이 처리한다. Hibernate 세션이 이미
            // rollback-only로 전이했으므로 명시적 JPQL UPDATE(restoreAtomic)는 호출하지 않는다.
            log.info("event=flash_sale_purchase_duplicate sale_id={} user_id={} strategy={}",
                    flashSaleId, userId, lockStrategy);
            throw new DuplicateFlashSalePurchaseException(flashSaleId, userId);
        }
    }

    private FlashSaleItem reserveCas(Long flashSaleId, Long flashSaleItemId, Long userId, FlashSaleItem item) {
        int reserved = itemRepository.reserveAtomic(flashSaleItemId, FIXED_QUANTITY);
        if (reserved == 0) {
            log.info("event=flash_sale_reserve_soldout sale_id={} item_id={} user_id={} strategy=cas",
                    flashSaleId, flashSaleItemId, userId);
            throw new FlashSaleSoldOutException(flashSaleItemId);
        }
        return item;
    }

    /**
     * [Phase 23-4 — 벤치 전용] SELECT FOR UPDATE로 행 락을 잡고 dirty checking으로 차감.
     * 운영 기본 경로가 아니다(§5-5). 트랜잭션 종료까지 행 락을 점유하여 같은 row 경합 시 직렬화된다.
     *
     * <p>구현 주의: 진입부 {@code findByItemAndSale}이 이미 entity를 1차 캐시에 올렸기 때문에
     * 별도 쿼리 메서드(@Lock @Query)로 다시 로드해도 캐시된 stale 인스턴스가 반환되어
     * dirty checking 시 {@code ObjectOptimisticLockingFailureException}이 터진다.
     * {@link EntityManager#refresh(Object, LockModeType)}는 캐시를 우회해 row 락 획득과
     * 동시에 최신 버전·수량을 다시 로드하므로 본 경로에서 옳은 메커니즘이다.</p>
     */
    private FlashSaleItem reservePessimistic(Long flashSaleId, Long flashSaleItemId, Long userId, FlashSaleItem item) {
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        if (item.getRemainingQuantity() < FIXED_QUANTITY) {
            log.info("event=flash_sale_reserve_soldout sale_id={} item_id={} user_id={} strategy=pessimistic",
                    flashSaleId, flashSaleItemId, userId);
            throw new FlashSaleSoldOutException(flashSaleItemId);
        }
        item.decreaseRemainingForLockedReserve(FIXED_QUANTITY);
        return item;
    }
}
