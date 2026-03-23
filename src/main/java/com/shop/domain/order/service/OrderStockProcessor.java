package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.InsufficientStockException;
import com.shop.global.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
class OrderStockProcessor {

    private final ProductRepository productRepository;
    private final EntityManager entityManager;

    OrderStockProcessor(ProductRepository productRepository, EntityManager entityManager) {
        this.productRepository = productRepository;
        this.entityManager = entityManager;
    }

    StockDeductionResult deductStockAndBuildOrderLines(List<Cart> cartItems, BigDecimal tierDiscountRate) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal tierDiscountTotal = BigDecimal.ZERO;
        List<OrderLine> orderLines = new ArrayList<>();
        List<InventorySnapshot> inventorySnapshots = new ArrayList<>();

        List<Long> productIds = new ArrayList<>(cartItems.size());
        for (Cart cart : cartItems) {
            productIds.add(cart.getProduct().getProductId());
            entityManager.detach(cart.getProduct());
        }

        List<Product> lockedProducts = productRepository.findAllByIdInWithLock(productIds);
        Map<Long, Product> productMap = new LinkedHashMap<>(lockedProducts.size());
        for (Product product : lockedProducts) {
            productMap.put(product.getProductId(), product);
        }

        for (Cart cart : cartItems) {
            Long productId = cart.getProduct().getProductId();
            Product product = productMap.get(productId);
            if (product == null) {
                throw new ResourceNotFoundException("상품", productId);
            }

            if (product.getStockQuantity() < cart.getQuantity()) {
                throw new InsufficientStockException(product.getProductName(),
                        cart.getQuantity(), product.getStockQuantity());
            }

            int beforeStock = product.getStockQuantity();
            product.decreaseStock(cart.getQuantity());

            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity()));
            totalAmount = totalAmount.add(subtotal);

            orderLines.add(new OrderLine(
                    product.getProductId(),
                    product.getProductName(),
                    cart.getQuantity(),
                    product.getPrice(),
                    subtotal
            ));

            BigDecimal itemTierDiscount = subtotal.multiply(tierDiscountRate)
                    .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.FLOOR);
            tierDiscountTotal = tierDiscountTotal.add(itemTierDiscount);

            inventorySnapshots.add(new InventorySnapshot(
                    product.getProductId(), cart.getQuantity(), beforeStock, product.getStockQuantity()));
        }

        return new StockDeductionResult(totalAmount, tierDiscountTotal, orderLines, inventorySnapshots);
    }

    record StockDeductionResult(BigDecimal totalAmount, BigDecimal tierDiscountTotal,
                                List<OrderLine> orderLines, List<InventorySnapshot> inventorySnapshots) {
    }

    record OrderLine(Long productId, String productName, int quantity,
                     BigDecimal unitPrice, BigDecimal subtotal) {
    }

    record InventorySnapshot(Long productId, int quantity, int beforeStock, int afterStock) {
    }
}
