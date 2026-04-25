package com.shop.domain.flashsale.dto;

import com.shop.domain.flashsale.entity.FlashSaleItem;
import com.shop.domain.order.entity.Order;

import java.math.BigDecimal;

/**
 * [Phase 23-2] 플래시 세일 구매 성공 응답.
 *
 * @param orderId          주문 고유 ID
 * @param orderNumber      사용자에게 표시되는 주문 번호
 * @param flashSaleItemId  구매한 플래시 세일 상품 ID
 * @param productName      상품명
 * @param salePrice        단가(세일 적용가)
 * @param quantity         구매 수량
 * @param totalAmount      총 결제 금액
 */
public record FlashSalePurchaseResponse(
        Long orderId,
        String orderNumber,
        Long flashSaleItemId,
        String productName,
        BigDecimal salePrice,
        int quantity,
        BigDecimal totalAmount
) {

    public static FlashSalePurchaseResponse of(Order order, FlashSaleItem item, int quantity) {
        return new FlashSalePurchaseResponse(
                order.getOrderId(),
                order.getOrderNumber(),
                item.getFlashSaleItemId(),
                item.getProduct().getProductName(),
                item.getSalePrice(),
                quantity,
                order.getTotalAmount()
        );
    }
}
