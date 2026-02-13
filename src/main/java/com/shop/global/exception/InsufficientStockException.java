package com.shop.global.exception;

public class InsufficientStockException extends BusinessException {
    public InsufficientStockException(String productName, int requested, int available) {
        super("INSUFFICIENT_STOCK",
            productName + " 재고가 부족합니다. (요청: " + requested + ", 재고: " + available + ")");
    }
}
