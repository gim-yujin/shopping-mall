package com.shop.domain.flashsale.service;

import com.shop.domain.flashsale.entity.FlashSalePurchase;
import com.shop.domain.flashsale.repository.FlashSaleItemRepository;
import com.shop.domain.flashsale.repository.FlashSalePurchaseRepository;
import com.shop.global.event.FlashSaleOrderCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * [Phase 23-5] 플래시 세일 주문 취소의 flashsale 도메인 측 보상 핸들러.
 *
 * <p>{@link com.shop.domain.order.service.OrderCancellationService}는 도메인 의존성
 * 규칙(`order ↔ flashsale` 양방향 금지) 때문에 본 도메인을 직접 호출할 수 없다.
 * 대신 {@link FlashSaleOrderCancelledEvent}를 발행하고, 본 핸들러가 동기 리스너로
 * 같은 트랜잭션 안에서 두 가지 보상을 수행한다:</p>
 *
 * <ol>
 *   <li>{@code flash_sale_items.remaining_quantity} 를 +1 복원
 *       — burst 트래픽이 차지했던 슬롯이 다시 사용 가능해진다.</li>
 *   <li>{@code flash_sale_purchases} 행 삭제
 *       — {@code uk_fsp_user_sale} 가 풀려 같은 사용자가 다시 시도할 수 있다.</li>
 * </ol>
 *
 * <p>설계 결정:</p>
 * <ul>
 *   <li><b>{@code @EventListener} (동기)</b> — {@code @TransactionalEventListener(AFTER_COMMIT)}
 *       을 쓰면 cancel 트랜잭션이 커밋된 뒤 본 핸들러가 별도 TX로 돈다. 이 핸들러가 실패할 경우
 *       사용자는 "취소됐다"고 보지만 remaining/purchase는 원복되지 않는 상태가 되고,
 *       이후 같은 사용자가 다른 사람에게 우회 구매를 막아 1인 1구매 제약을 망가뜨린다.
 *       동기 리스너는 publish 호출 즉시 같은 TX 안에서 실행되어 atomic 보장을 유지한다
 *       (실패 시 cancel TX 자체가 롤백되어 사용자에게 에러가 노출됨 — 더 안전한 실패 모드).</li>
 *   <li><b>멱등성</b> — purchase 행이 없는 케이스(이미 처리됐거나 데이터 정합 이상)는 no-op으로 통과시키고
 *       경고 로그만 남긴다. 보상의 부재가 더 큰 문제(현재 행이 없으면 복원 대상도 없으므로 안전).</li>
 *   <li><b>동시성</b> — Order는 호출부에서 비관적 락으로 잡혀 있어 본 핸들러는 동일 주문에 대해
 *       동시에 두 번 호출되지 않는다. flash_sale_purchases 행 삭제도 단일 TX이라 race가 없다.</li>
 * </ul>
 */
@Component
public class FlashSalePurchaseCancellationHandler {

    private static final Logger log = LoggerFactory.getLogger(FlashSalePurchaseCancellationHandler.class);
    private static final int RESTORE_QUANTITY = 1;

    private final FlashSalePurchaseRepository purchaseRepository;
    private final FlashSaleItemRepository itemRepository;

    public FlashSalePurchaseCancellationHandler(FlashSalePurchaseRepository purchaseRepository,
                                                FlashSaleItemRepository itemRepository) {
        this.purchaseRepository = purchaseRepository;
        this.itemRepository = itemRepository;
    }

    @EventListener
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void onFlashSaleOrderCancelled(FlashSaleOrderCancelledEvent event) {
        Long orderId = event.orderId();
        Optional<FlashSalePurchase> opt = purchaseRepository.findByOrderId(orderId);
        if (opt.isEmpty()) {
            log.warn("event=flash_sale_cancel_no_purchase_found order_id={} — 이미 보상이 끝났거나 데이터 정합 이상", orderId);
            return;
        }
        FlashSalePurchase purchase = opt.get();
        int restored = itemRepository.restoreAtomic(purchase.getFlashSaleItemId(), RESTORE_QUANTITY);
        if (restored != 1) {
            log.warn("event=flash_sale_cancel_restore_unexpected order_id={} item_id={} restored={}",
                    orderId, purchase.getFlashSaleItemId(), restored);
        }
        purchaseRepository.delete(purchase);
        log.info("event=flash_sale_cancel_compensated order_id={} sale_id={} item_id={} user_id={}",
                orderId, purchase.getFlashSaleId(), purchase.getFlashSaleItemId(), purchase.getUserId());
    }
}
